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
        val now = System.currentTimeMillis()
        store.update { list ->
            list.map {
                if (it.key == key) it.copy(latest = release ?: it.latest, error = error, lastChecked = now)
                else it
            }
        }
    }
}
