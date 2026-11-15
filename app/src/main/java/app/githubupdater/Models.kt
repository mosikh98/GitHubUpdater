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
    /** True once we already tried to read the package name from the repo's Gradle files. */
    val pkgTried: Boolean = false,
) {
    val fullName: String get() = "$owner/$name"
    val key: String get() = fullName.lowercase()
}

/** Repos added on first launch. [packageName] is only filled in where it is well known. */
val DEFAULT_REPOS = listOf(
    TrackedRepo("CluvexStudio", "ZedSecure"),
    TrackedRepo("patterniha", "PattNG"),
    TrackedRepo("WhiteDNS", "WhiteVPN"),
    TrackedRepo("mlmvpn", "mlmvpn_android"),
    TrackedRepo("2dust", "v2rayng", packageName = "com.v2ray.ang"),
    TrackedRepo("GFW-knocker", "MahsaNG"),
    TrackedRepo("bia-pain-bache", "BPB-VPN"),
    TrackedRepo("WhiteDNS", "WhiteAestherMobile"),
    TrackedRepo("WhiteDNS", "WhiteDNS-Android"),
    TrackedRepo("WhiteDNS", "WhiteDNS-cleanip-finder"),
    TrackedRepo("hiddify", "hiddify-app", packageName = "app.hiddify.com"),
)

enum class Status { Unchecked, Error, PackageUnknown, NotInstalled, UpdateAvailable, UpToDate }

data class AppEntry(val label: String, val pkg: String)
