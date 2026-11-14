package app.githubupdater

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val repos: List<TrackedRepo> = emptyList(),
    val installed: Map<String, String?> = emptyMap(),
    val checking: Set<String> = emptySet(),
    val progress: Map<String, Float> = emptyMap(),
    val refreshingAll: Boolean = false,
    val token: String = "",
    val autoCheck: Boolean = true,
)

class UiMessage(@StringRes val resId: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    private val store = RepoStore(app)

    private val _state = MutableStateFlow(UiState(token = store.token, autoCheck = store.autoCheck))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        reload()
        checkAll()
    }

    private fun msg(@StringRes id: Int) {
        _messages.trySend(UiMessage(id))
    }

    private fun reload() {
        val repos = store.load()
        val pm = ctx.packageManager
        val installed = repos.associate { r ->
            r.key to r.packageName?.let { StatusLogic.installedVersion(pm, it) }
        }
        _state.update { it.copy(repos = repos, installed = installed) }
    }

    /** Called when the app comes back to the foreground (an install may have finished). */
    fun refreshInstalled() = reload()

    fun checkAll() {
        if (_state.value.refreshingAll) return
        viewModelScope.launch {
            _state.update { it.copy(refreshingAll = true) }
            store.load().forEach { checkKey(it.key) }
            _state.update { it.copy(refreshingAll = false) }
        }
    }

    fun checkOne(key: String) {
        viewModelScope.launch { checkKey(key) }
    }

    private suspend fun checkKey(key: String) {
        _state.update { it.copy(checking = it.checking + key) }
        UpdateChecker.checkRepo(store, key)
        reload()
        _state.update { it.copy(checking = it.checking - key) }
    }

    /** Returns a string resource id describing the problem, or null on success. */
    fun addRepo(input: String, pkg: String): Int? {
        val (owner, name) = GitHubApi.parseRepo(input) ?: return R.string.invalid_repo
        val p = pkg.trim().ifEmpty { null }
        if (p != null && !PACKAGE_REGEX.matches(p)) return R.string.invalid_package
        val repo = TrackedRepo(owner, name, packageName = p)
        if (store.load().any { it.key == repo.key }) return R.string.already_added
        store.update { it + repo }
        reload()
        checkOne(repo.key)
        return null
    }

    fun setPackage(key: String, pkg: String): Int? {
        val p = pkg.trim().ifEmpty { null }
        if (p != null && !PACKAGE_REGEX.matches(p)) return R.string.invalid_package
        store.update { list -> list.map { if (it.key == key) it.copy(packageName = p) else it } }
        reload()
        return null
    }

    /** Installed (non-system) apps, with the ones that look like [repo] first. */
    @Suppress("DEPRECATION")
    fun installedApps(repo: TrackedRepo): List<AppEntry> {
        val pm = ctx.packageManager
        val tokens = repo.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        fun score(a: AppEntry): Int {
            val text = (a.label + " " + a.pkg).lowercase()
            return tokens.count { text.contains(it) }
        }
        return pm.getInstalledApplications(0)
            .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedWith(compareByDescending<AppEntry> { score(it) }.thenBy { it.label.lowercase() })
    }

    fun remove(key: String) {
        store.update { list -> list.filter { it.key != key } }
        reload()
    }

    fun saveSettings(token: String, autoCheck: Boolean) {
        store.token = token
        store.autoCheck = autoCheck
        Scheduler.apply(ctx, autoCheck)
        _state.update { it.copy(token = store.token, autoCheck = autoCheck) }
    }

    fun downloadAndInstall(key: String) {
        val repo = _state.value.repos.firstOrNull { it.key == key } ?: return
        val latest = repo.latest ?: return
        if (key in _state.value.progress) return
        val asset = Installer.pickAsset(latest.assets)
        if (asset == null) {
            msg(R.string.no_apk)
            return
        }
        if (!Installer.canInstall(ctx)) {
            msg(R.string.allow_install)
            Installer.openInstallSettings(ctx)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(progress = it.progress + (key to 0f)) }
            try {
                val file = Installer.download(ctx, asset) { p ->
                    _state.update { s -> s.copy(progress = s.progress + (key to p)) }
                }
                val info = Installer.readApkInfo(ctx, file)
                store.update { list ->
                    list.map {
                        if (it.key == key) it.copy(
                            packageName = info?.first ?: it.packageName,
                            apkTag = latest.tag,
                            apkVersion = info?.second,
                        ) else it
                    }
                }
                reload()
                Installer.install(ctx, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg(R.string.download_failed)
            } finally {
                _state.update { it.copy(progress = it.progress - key) }
            }
        }
    }

    private companion object {
        val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
