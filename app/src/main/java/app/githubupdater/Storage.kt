package app.githubupdater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

class RepoStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("github_updater", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v.trim()).apply() }

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(v) { prefs.edit().putBoolean("auto_check", v).apply() }

    fun load(): List<TrackedRepo> = synchronized(LOCK) { seedOnce(); read() }

    fun update(transform: (List<TrackedRepo>) -> List<TrackedRepo>) = synchronized(LOCK) {
        seedOnce()
        write(transform(read()))
    }

    private fun write(list: List<TrackedRepo>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("repos", arr.toString()).apply()
    }

    /** Adds the default repos once (existing entries are kept, nothing is re-added later). */
    private fun seedOnce() {
        if (prefs.getBoolean("seeded_v1", false)) return
        val current = read()
        val have = current.map { it.key }.toSet()
        write(current + DEFAULT_REPOS.filter { it.key !in have })
        prefs.edit().putBoolean("seeded_v1", true).apply()
    }

    fun notified(): Set<String> = synchronized(LOCK) {
        prefs.getStringSet("notified", emptySet())?.toSet() ?: emptySet()
    }

    fun addNotified(items: Collection<String>) = synchronized(LOCK) {
        prefs.edit().putStringSet("notified", notified() + items).apply()
    }

    private fun read(): List<TrackedRepo> {
        val raw = prefs.getString("repos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { repoFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun TrackedRepo.toJson(): JSONObject = JSONObject().also { o ->
        o.put("owner", owner)
        o.put("name", name)
        packageName?.let { o.put("packageName", it) }
        error?.let { o.put("error", it) }
        o.put("lastChecked", lastChecked)
        apkTag?.let { o.put("apkTag", it) }
        apkVersion?.let { o.put("apkVersion", it) }
        if (pkgTried) o.put("pkgTried", true)
        latest?.let { r ->
            val a = JSONArray()
            r.assets.forEach { s ->
                a.put(JSONObject().put("name", s.name).put("url", s.url).put("size", s.size))
            }
            o.put(
                "latest",
                JSONObject()
                    .put("tag", r.tag).put("title", r.title).put("body", r.body)
                    .put("htmlUrl", r.htmlUrl).put("publishedAt", r.publishedAt)
                    .put("assets", a)
            )
        }
    }

    private fun repoFromJson(o: JSONObject): TrackedRepo {
        val latest = o.optJSONObject("latest")?.let { r ->
            val a = r.optJSONArray("assets") ?: JSONArray()
            ReleaseInfo(
                tag = r.str("tag") ?: "",
                title = r.str("title") ?: "",
                body = r.str("body") ?: "",
                htmlUrl = r.str("htmlUrl") ?: "",
                publishedAt = r.str("publishedAt") ?: "",
                assets = (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    AssetInfo(s.str("name") ?: "", s.str("url") ?: "", s.optLong("size"))
                },
            )
        }
        return TrackedRepo(
            owner = o.getString("owner"),
            name = o.getString("name"),
            packageName = o.str("packageName"),
            latest = latest,
            error = o.str("error"),
            lastChecked = o.optLong("lastChecked"),
            apkTag = o.str("apkTag"),
            apkVersion = o.str("apkVersion"),
            pkgTried = o.optBoolean("pkgTried"),
        )
    }

    private companion object {
        val LOCK = Any()
    }
}
