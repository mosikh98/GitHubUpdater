package app.githubupdater

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF3FB950))
        else -> lightColorScheme(primary = Color(0xFF1A7F37))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val Ltr = TextStyle(textDirection = TextDirection.Ltr)

@Composable
private fun errorText(code: String?): String = when (code) {
    "not_found" -> stringResource(R.string.err_not_found)
    "no_release" -> stringResource(R.string.err_no_release)
    "rate_limit" -> stringResource(R.string.err_rate_limit)
    "bad_token" -> stringResource(R.string.err_bad_token)
    "network" -> stringResource(R.string.err_network)
    else -> stringResource(R.string.err_other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var editRepo by remember { mutableStateOf<TrackedRepo?>(null) }
    var removeRepo by remember { mutableStateOf<TrackedRepo?>(null) }
    var pickRepo by remember { mutableStateOf<TrackedRepo?>(null) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(context.getString(it.resId)) }
    }

    val order = { r: TrackedRepo ->
        if (StatusLogic.compute(r, state.installed[r.key]) == Status.UpdateAvailable) 0 else 1
    }
    val repos = state.repos.sortedBy(order)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { vm.checkAll() }) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.settings))
                        }
                    },
                )
                if (state.refreshingAll) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_repo))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (repos.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.empty_text),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(repos, key = { it.key }) { repo ->
                    RepoCard(
                        repo = repo,
                        installed = state.installed[repo.key],
                        checking = repo.key in state.checking,
                        progress = state.progress[repo.key],
                        onInstall = { vm.downloadAndInstall(repo.key) },
                        onPickApp = { pickRepo = repo },
                        onRemove = { removeRepo = repo },
                        onOpen = {
                            val url = repo.latest?.htmlUrl?.ifBlank { null }
                                ?: "https://github.com/${repo.fullName}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        var repoText by remember { mutableStateOf("") }
        var pkgText by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_repo)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = repoText,
                        onValueChange = { repoText = it; error = null },
                        label = { Text(stringResource(R.string.repo_hint)) },
                        singleLine = true,
                        textStyle = Ltr,
                        isError = error != null,
                        supportingText = { error?.let { Text(stringResource(it)) } },
                    )
                    OutlinedTextField(
                        value = pkgText,
                        onValueChange = { pkgText = it },
                        label = { Text(stringResource(R.string.package_hint)) },
                        supportingText = { Text(stringResource(R.string.package_help)) },
                        singleLine = true,
                        textStyle = Ltr,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = vm.addRepo(repoText, pkgText)
                    if (e == null) showAdd = false else error = e
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    editRepo?.let { repo ->
        var pkgText by remember(repo.key) { mutableStateOf(repo.packageName ?: "") }
        var error by remember(repo.key) { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { editRepo = null },
            title = { Text(repo.fullName, style = Ltr) },
            text = {
                OutlinedTextField(
                    value = pkgText,
                    onValueChange = { pkgText = it; error = null },
                    label = { Text(stringResource(R.string.edit_package)) },
                    supportingText = { Text(error?.let { stringResource(it) } ?: stringResource(R.string.package_help)) },
                    isError = error != null,
                    singleLine = true,
                    textStyle = Ltr,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = vm.setPackage(repo.key, pkgText)
                    if (e == null) editRepo = null else error = e
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editRepo = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    removeRepo?.let { repo ->
        AlertDialog(
            onDismissRequest = { removeRepo = null },
            title = { Text(stringResource(R.string.confirm_remove_title)) },
            text = { Text(stringResource(R.string.confirm_remove_text, repo.fullName)) },
            confirmButton = {
                TextButton(onClick = { vm.remove(repo.key); removeRepo = null }) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeRepo = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pickRepo?.let { repo ->
        val apps = remember(repo.key) { vm.installedApps(repo) }
        AppPickerDialog(
            apps = apps,
            onPick = { vm.setPackage(repo.key, it.pkg); pickRepo = null },
            onManual = { editRepo = repo; pickRepo = null },
            onDismiss = { pickRepo = null },
        )
    }

    if (showSettings) {
        var token by remember { mutableStateOf(state.token) }
        var auto by remember { mutableStateOf(state.autoCheck) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.token_label)) },
                        supportingText = { Text(stringResource(R.string.token_help)) },
                        singleLine = true,
                        textStyle = Ltr,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_check), Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveSettings(token, auto)
                    showSettings = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RepoCard(
    repo: TrackedRepo,
    installed: String?,
    checking: Boolean,
    progress: Float?,
    onInstall: () -> Unit,
    onPickApp: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val status = StatusLogic.compute(repo, installed)
    val latest = repo.latest
    var expanded by remember { mutableStateOf(false) }

    val (label, color) = when (status) {
        Status.UpdateAvailable -> stringResource(R.string.status_update) to MaterialTheme.colorScheme.primary
        Status.UpToDate -> stringResource(R.string.status_uptodate) to MaterialTheme.colorScheme.onSurfaceVariant
        Status.NotInstalled -> stringResource(R.string.status_not_installed) to MaterialTheme.colorScheme.tertiary
        Status.PackageUnknown -> stringResource(R.string.status_package_unknown) to MaterialTheme.colorScheme.tertiary
        Status.Unchecked -> stringResource(R.string.status_unchecked) to MaterialTheme.colorScheme.onSurfaceVariant
        Status.Error -> stringResource(R.string.status_error) to MaterialTheme.colorScheme.error
    }

    val asset = latest?.let { Installer.pickAsset(it.assets) }
    val canAct = asset != null && progress == null &&
        (status == Status.UpdateAvailable || status == Status.NotInstalled || status == Status.PackageUnknown)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status == Status.UpdateAvailable)
                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    repo.fullName,
                    style = MaterialTheme.typography.titleSmall.merge(Ltr),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (checking) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    label,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }

            val info = listOfNotNull(
                installed?.let { stringResource(R.string.installed_version, it) },
                latest?.let { stringResource(R.string.latest_version, it.tag) },
                latest?.publishedAt?.takeIf { it.isNotBlank() }?.substringBefore('T'),
            ).joinToString("  •  ")
            if (info.isNotEmpty()) {
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (repo.error != null) {
                Text(
                    errorText(repo.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (expanded) {
                if (asset != null) {
                    Text(
                        asset.name,
                        style = MaterialTheme.typography.bodySmall.merge(Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (latest != null && latest.body.isNotBlank()) {
                    Text(
                        latest.body.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                }
            }

            if (progress != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                ) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else if (latest != null && asset == null) {
                Text(
                    stringResource(R.string.no_apk),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canAct) {
                    Button(onClick = onInstall, contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Text(stringResource(if (status == Status.UpdateAvailable) R.string.update_now else R.string.download_install))
                    }
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.open_github)) }
                    IconButton(onClick = onPickApp, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Edit, stringResource(R.string.pick_app))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, stringResource(R.string.remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_app)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp)
                        ) {
                            Text(app.label)
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall.merge(Ltr),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManual) { Text(stringResource(R.string.edit_package)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
