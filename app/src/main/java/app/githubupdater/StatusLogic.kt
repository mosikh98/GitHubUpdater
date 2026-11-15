package app.githubupdater

import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException

object VersionUtil {
    fun normalize(v: String) = v.trim().removePrefix("v").removePrefix("V")

    private fun nums(s: String): List<Long> =
        Regex("\\d+").findAll(s).mapNotNull { it.value.toLongOrNull() }.toList()

    /** True when [latest] looks strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = normalize(latest)
        val b = normalize(current)
        if (a.equals(b, ignoreCase = true)) return false
        val pa = nums(a)
        val pb = nums(b)
        if (pa.isEmpty() || pb.isEmpty()) return true
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }
}

object StatusLogic {
    @Suppress("DEPRECATION")
    fun installedVersion(pm: PackageManager, pkg: String): String? = try {
        pm.getPackageInfo(pkg, 0).versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun compute(repo: TrackedRepo, installedVersion: String?): Status {
        val latest = repo.latest
            ?: return if (repo.error != null) Status.Error else Status.Unchecked
        if (repo.packageName == null) return Status.PackageUnknown
        if (installedVersion == null) return Status.NotInstalled
        // If the installed build is the one we downloaded for a tag, trust that tag.
        val effective =
            if (repo.apkTag != null && repo.apkVersion == installedVersion) repo.apkTag
            else installedVersion
        return if (VersionUtil.isNewer(latest.tag, effective)) Status.UpdateAvailable
        else Status.UpToDate
    }
}

object UpdateChecker {
    suspend fun checkRepo(store: RepoStore, key: String) {
        val repo = store.load().firstOrNull { it.key == key } ?: return
        var release: ReleaseInfo? = null
        var error: String? = null
        try {
            release = GitHubApi.latestRelease(repo.owner, repo.name, store.token)
            if (release == null) error = "no_release"
        } catch (e: GitHubApi.ApiException) {
            error = e.code
        } catch (e: IOException) {
            error = "network"
        } catch (e: Exception) {
            error = "other"
        }
        // Once per repo: try to learn the Android package name from its Gradle files.
        var pkg: String? = null
        var tried = false
        if (repo.packageName == null && !repo.pkgTried && error == null) {
            try {
                pkg = GitHubApi.inferPackageName(repo.owner, repo.name, store.token)
                tried = true
            } catch (e: Exception) {
                // network / rate limit: try again on the next check
            }
        }
        val now = System.currentTimeMillis()
        store.update { list ->
            list.map {
                if (it.key == key) it.copy(
                    latest = release ?: it.latest,
                    error = error,
                    lastChecked = now,
                    packageName = it.packageName ?: pkg,
                    pkgTried = it.pkgTried || tried,
                ) else it
            }
        }
    }
}

/** Matches repos to installed apps by name. Only unambiguous one-to-one matches are returned. */
object AppMatcher {
    private val noise = listOf("android", "mobile", "app", "apk")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun repoKey(name: String): String {
        var n = name.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in noise }.joinToString("")
        for (w in noise) if (n.endsWith(w) && n.length >= w.length + 4) n = n.removeSuffix(w)
        return n
    }

    private fun score(repo: String, app: AppEntry): Int {
        if (repo.length < 4) return 0
        fun m(x: String) = when {
            x.length < 4 -> 0
            x == repo -> 3
            repo.contains(x) || x.contains(repo) -> 2
            else -> 0
        }
        return maxOf(m(norm(app.label)), m(norm(app.pkg.substringAfterLast('.'))))
    }

    /** Returns repo key -> package name. */
    fun match(repos: List<TrackedRepo>, apps: List<AppEntry>): Map<String, String> {
        val candidates = repos.associate { r ->
            val rk = repoKey(r.name)
            val scored = apps.map { it to score(rk, it) }.filter { it.second >= 2 }
            val top = scored.maxOfOrNull { it.second }
            r.key to scored.filter { it.second == top }.map { it.first.pkg }
        }
        val usage = candidates.values.flatten().groupingBy { it }.eachCount()
        val result = HashMap<String, String>()
        for ((key, pkgs) in candidates) {
            if (pkgs.size == 1 && usage[pkgs[0]] == 1) result[key] = pkgs[0]
        }
        return result
    }
}
