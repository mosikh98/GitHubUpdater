package app.githubupdater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object GitHubApi {
    /** [code] is one of: not_found, rate_limit, bad_token, other. */
    class ApiException(val code: String) : Exception(code)

    private class Resp(val code: Int, val body: String)

    fun parseRepo(input: String): Pair<String, String>? {
        var s = input.trim().removeSuffix("/").removeSuffix(".git")
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("github.com/")
        val parts = s.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val ok = Regex("^[A-Za-z0-9_.-]+$")
        if (!ok.matches(parts[0]) || !ok.matches(parts[1])) return null
        return parts[0] to parts[1]
    }

    /** Returns the newest published release, or null if the repo has none. */
    suspend fun latestRelease(owner: String, repo: String, token: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            val base = "https://api.github.com/repos/$owner/$repo/releases"
            val r = get("$base/latest", token)
            if (r.code == 200) return@withContext parseRelease(JSONObject(r.body))
            if (r.code != 404) throw failure(r.code)

            // /latest ignores pre-releases; fall back to the newest non-draft release.
            val list = get("$base?per_page=10", token)
            if (list.code == 404) throw ApiException("not_found")
            if (list.code != 200) throw failure(list.code)
            val arr = JSONArray(list.body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("draft")) return@withContext parseRelease(o)
            }
            null
        }

    /**
     * Reads `applicationId` from the repo's app/build.gradle(.kts), like Obtainium does
     * (it also handles .kts files and nested folders such as V2rayNG/app/).
     * Returns null if not found; throws on network / rate-limit problems so the caller can retry later.
     */
    suspend fun inferPackageName(owner: String, repo: String, token: String): String? =
        withContext(Dispatchers.IO) {
            val base = "https://api.github.com/repos/$owner/$repo"
            val tree = get("$base/git/trees/HEAD?recursive=1", token)
            if (tree.code == 403 || tree.code == 429) throw failure(tree.code)
            if (tree.code != 200) return@withContext null
            val arr = JSONObject(tree.body).optJSONArray("tree") ?: return@withContext null
            val candidates = (0 until arr.length())
                .mapNotNull { arr.getJSONObject(it).str("path") }
                .filter { it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") }
                .filter { it.substringBeforeLast('/', "").substringAfterLast('/') == "app" }
                .sortedBy { p -> p.count { it == '/' } }
                .take(4)
            val regex = Regex("""applicationId\s*=?\s*["']([^"'$]+)["']""")
            for (path in candidates) {
                val encoded = path.split('/').joinToString("/") {
                    URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                }
                val r = get("$base/contents/$encoded", token)
                if (r.code == 403 || r.code == 429) throw failure(r.code)
                if (r.code != 200) continue
                val content = JSONObject(r.body).str("content") ?: continue
                val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                val found = regex.find(text)?.groupValues?.get(1)
                if (found != null) return@withContext found
            }
            null
        }

    private fun failure(code: Int) = when (code) {
        401 -> ApiException("bad_token")
        403, 429 -> ApiException("rate_limit")
        404 -> ApiException("not_found")
        else -> ApiException("other")
    }

    private fun get(url: String, token: String): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "GitHubUpdater-Android")
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun parseRelease(o: JSONObject): ReleaseInfo {
        val assets = ArrayList<AssetInfo>()
        val arr = o.optJSONArray("assets")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                assets.add(
                    AssetInfo(
                        a.str("name") ?: "",
                        a.str("browser_download_url") ?: "",
                        a.optLong("size"),
                    )
                )
            }
        }
        return ReleaseInfo(
            tag = o.str("tag_name") ?: "",
            title = o.str("name") ?: "",
            body = o.str("body") ?: "",
            htmlUrl = o.str("html_url") ?: "",
            publishedAt = o.str("published_at") ?: "",
            assets = assets,
        )
    }
}
