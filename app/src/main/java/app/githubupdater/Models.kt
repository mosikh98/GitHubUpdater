package app.githubupdater

data class AssetInfo(val name: String, val url: String, val size: Long)

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val assets: List<AssetInfo>,
)

data class TrackedRepo(
    val owner: String,
    val name: String,
    val packageName: String? = null,
    val latest: ReleaseInfo? = null,
    val error: String? = null,
    val lastChecked: Long = 0L,
    /** Release tag + the APK versionName we last downloaded for it. */
    val apkTag: String? = null,
    val apkVersion: String? = null,
) {
    val fullName: String get() = "$owner/$name"
    val key: String get() = fullName.lowercase()
}

enum class Status { Unchecked, Error, NotInstalled, UpdateAvailable, UpToDate }
