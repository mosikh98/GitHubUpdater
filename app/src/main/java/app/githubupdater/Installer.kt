package app.githubupdater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Installer {
    private val aliases = mapOf(
        "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64"),
        "armeabi-v7a" to listOf("armeabi-v7a", "armv7", "armeabi", "arm32"),
        "x86_64" to listOf("x86_64", "amd64", "x64"),
        "x86" to listOf("x86"),
    )

    private fun matchesAbi(name: String, abi: String): Boolean =
        aliases[abi].orEmpty().any { name.contains(it) } &&
            !(abi == "x86" && name.contains("x86_64"))

    /** Picks the APK that best fits this device (its ABI first, then universal). */
    fun pickAsset(assets: List<AssetInfo>): AssetInfo? {
        val apks = assets.filter { it.name.endsWith(".apk", true) && it.url.isNotBlank() }
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        fun score(asset: AssetInfo): Int {
            val n = asset.name.lowercase()
            val idx = abis.indexOfFirst { matchesAbi(n, it) }
            return when {
                idx >= 0 -> 100 - idx
                n.contains("universal") -> 60
                aliases.keys.any { matchesAbi(n, it) } -> 0
                else -> 50
            }
        }
        return apks.maxByOrNull { score(it) }
    }

    suspend fun download(context: Context, asset: AssetInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "apks").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val safe = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmp = File(dir, "$safe.part")
            val out = File(dir, safe)

            val c = URL(asset.url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "GitHubUpdater-Android")
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else asset.size
                var done = 0L
                var last = -1
                c.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            } finally {
                c.disconnect()
            }
            if (!tmp.renameTo(out)) error("rename failed")
            out
        }

    /** Returns (packageName, versionName) of an APK file, or null if unreadable. */
    @Suppress("DEPRECATION")
    fun readApkInfo(context: Context, file: File): Pair<String, String?>? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageArchiveInfo(file.path, 0)
        }
        return info?.let { it.packageName to it.versionName }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
