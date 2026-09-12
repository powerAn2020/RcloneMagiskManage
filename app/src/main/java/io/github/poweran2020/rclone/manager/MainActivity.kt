package io.github.poweran2020.rclone.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import io.github.poweran2020.rclone.manager.data.AppLanguage
import io.github.poweran2020.rclone.manager.data.AppPreferences
import io.github.poweran2020.rclone.manager.data.ThemeMode
import io.github.poweran2020.rclone.manager.ui.component.RootPermissionDialog
import io.github.poweran2020.rclone.manager.ui.component.TokenEditorDialog
import io.github.poweran2020.rclone.manager.ui.screens.CryptScreen
import io.github.poweran2020.rclone.manager.ui.screens.DashboardScreen
import io.github.poweran2020.rclone.manager.ui.screens.FilesScreen
import io.github.poweran2020.rclone.manager.ui.screens.JobsScreen
import io.github.poweran2020.rclone.manager.ui.screens.MountsScreen
import io.github.poweran2020.rclone.manager.ui.screens.RemotesScreen
import io.github.poweran2020.rclone.manager.ui.screens.SecurityScreen
import io.github.poweran2020.rclone.manager.ui.screens.SettingsScreen
import io.github.poweran2020.rclone.manager.ui.theme.RcloneTheme
import io.github.poweran2020.rclone.manager.util.LocaleUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RootStatus {
    CHECKING,
    GRANTED,
    DENIED
}

class MainActivity : ComponentActivity() {
    private val client = GatewayClient()
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tokenStore: TokenStore
    private lateinit var appPreferences: AppPreferences
    private val tokenState = mutableStateOf("")
    private val rootStatusState = mutableStateOf(RootStatus.CHECKING)
    private val themeModeState = mutableStateOf(ThemeMode.SYSTEM)
    private val appLanguageState = mutableStateOf(AppLanguage.SYSTEM)

    override fun attachBaseContext(newBase: Context) {
        val prefs = AppPreferences(newBase)
        val lang = prefs.getAppLanguage()
        super.attachBaseContext(LocaleUtil.getLocalizedContext(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(45)
        )
        appPreferences = AppPreferences(this)
        val initialLang = appPreferences.getAppLanguage()
        LocaleUtil.applyLocale(this, initialLang)
        themeModeState.value = appPreferences.getThemeMode()
        appLanguageState.value = initialLang
        tokenStore = TokenStore(this)
        tokenState.value = tokenStore.read()

        checkRootPermission(initial = true)

        setContent {
            val currentLanguage = appLanguageState.value
            val localizedContext = remember(currentLanguage) {
                LocaleUtil.getLocalizedContext(this@MainActivity, currentLanguage)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                androidx.compose.ui.platform.LocalConfiguration provides localizedContext.resources.configuration,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                RcloneTheme(themeMode = themeModeState.value) {
                    RcloneApp(
                        client = client,
                        tokenStore = tokenStore,
                        bearer = tokenState.value,
                        rootStatus = rootStatusState.value,
                        themeMode = themeModeState.value,
                        onThemeModeChanged = { newMode ->
                            themeModeState.value = newMode
                            appPreferences.setThemeMode(newMode)
                        },
                        appLanguage = currentLanguage,
                        onAppLanguageChanged = { newLang ->
                            if (appLanguageState.value != newLang) {
                                appLanguageState.value = newLang
                                appPreferences.setAppLanguage(newLang)
                                LocaleUtil.applyLocale(this@MainActivity, newLang)
                                recreate()
                            }
                        },
                        onRetryRoot = { checkRootPermission(forceRefresh = true) },
                        onOpenRootManager = { openRootManager() },
                        onExitApp = { finish() },
                        onTokenChanged = { newToken ->
                            tokenState.value = newToken
                            tokenStore.write(newToken)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkRootPermission(forceRefresh = rootStatusState.value == RootStatus.DENIED)
    }

    private fun checkRootPermission(forceRefresh: Boolean = false, initial: Boolean = false) {
        activityScope.launch {
            if (rootStatusState.value == RootStatus.DENIED || initial) {
                rootStatusState.value = RootStatus.CHECKING
            }
            val isRoot = withContext(Dispatchers.IO) {
                if (forceRefresh) {
                    runCatching { Shell.getCachedShell()?.close() }
                }
                val hasRoot = runCatching {
                    Shell.getShell().isRoot
                }.getOrDefault(false)
                if (hasRoot) {
                    if (initial) {
                        runCatching { client.ensureServiceRunning() }
                    }
                    true
                } else {
                    runCatching { client.health().isSuccess }.getOrDefault(false)
                }
            }
            rootStatusState.value = if (isRoot) RootStatus.GRANTED else RootStatus.DENIED
        }
    }

    private fun openRootManager() {
        val rootPackages = listOf(
            "me.weishu.kernelsu",
            "com.topjohnwu.magisk",
            "org.apatch"
        )
        for (pkg in rootPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                runCatching { startActivity(launchIntent) }
                return
            }
        }
        runCatching {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RcloneApp(
    client: GatewayClient,
    tokenStore: TokenStore,
    bearer: String,
    rootStatus: RootStatus,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChanged: (AppLanguage) -> Unit,
    onRetryRoot: () -> Unit,
    onOpenRootManager: () -> Unit,
    onExitApp: () -> Unit,
    onTokenChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var moreSubTab by remember { mutableIntStateOf(0) }
    var activeRemoteForFiles by remember { mutableStateOf<String?>(null) }
    var showTokenEditor by remember { mutableStateOf(false) }

    val navTabs = listOf(
        stringResource(R.string.nav_dashboard),
        stringResource(R.string.nav_remotes),
        stringResource(R.string.nav_files),
        stringResource(R.string.nav_jobs),
        stringResource(R.string.nav_more)
    )
    val navIcons = listOf(
        Icons.Default.Home,
        Icons.Default.Cloud,
        Icons.Default.Folder,
        Icons.Default.PlayArrow,
        Icons.Default.MoreHoriz
    )
    val moreSubTabs = listOf(
        stringResource(R.string.subtab_mounts),
        stringResource(R.string.subtab_crypt),
        stringResource(R.string.subtab_security),
        stringResource(R.string.subtab_settings)
    )

    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                navTabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(navIcons[index], contentDescription = label) },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        val screenPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = padding.calculateBottomPadding()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    padding = screenPadding,
                    client = client,
                    bearer = bearer,
                    onNavigateTab = { target ->
                        if (target == 4) {
                            moreSubTab = 0
                        }
                        selectedTab = target
                    },
                    onEditToken = { showTokenEditor = true },
                    onTokenUpdated = onTokenChanged,
                    onShowMessage = showMessage
                )
                1 -> RemotesScreen(
                    padding = screenPadding,
                    client = client,
                    bearer = bearer,
                    onNavigateToFileBrowser = { remoteId ->
                        activeRemoteForFiles = remoteId
                        selectedTab = 2
                    },
                    onShowMessage = showMessage
                )
                2 -> FilesScreen(
                    padding = screenPadding,
                    client = client,
                    bearer = bearer,
                    initialRemoteId = activeRemoteForFiles,
                    onShowMessage = showMessage
                )
                3 -> JobsScreen(
                    padding = screenPadding,
                    client = client,
                    bearer = bearer,
                    onShowMessage = showMessage
                )
                4 -> Column(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                    ScrollableTabRow(
                        selectedTabIndex = moreSubTab,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 16.dp
                    ) {
                        moreSubTabs.forEachIndexed { index, title ->
                            Tab(
                                selected = moreSubTab == index,
                                onClick = { moreSubTab = index },
                                text = { Text(title, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (moreSubTab) {
                            0 -> MountsScreen(
                                padding = PaddingValues(0.dp),
                                client = client,
                                bearer = bearer,
                                onShowMessage = showMessage
                            )
                            1 -> CryptScreen(
                                padding = PaddingValues(0.dp),
                                client = client,
                                bearer = bearer,
                                onShowMessage = showMessage
                            )
                            2 -> SecurityScreen(
                                padding = PaddingValues(0.dp),
                                client = client,
                                bearer = bearer,
                                tokenStore = tokenStore,
                                onTokenUpdated = onTokenChanged,
                                onShowMessage = showMessage
                            )
                            3 -> SettingsScreen(
                                padding = PaddingValues(0.dp),
                                client = client,
                                bearer = bearer,
                                themeMode = themeMode,
                                onThemeModeChanged = onThemeModeChanged,
                                appLanguage = appLanguage,
                                onAppLanguageChanged = onAppLanguageChanged,
                                onEditToken = { showTokenEditor = true },
                                onShowMessage = showMessage
                            )
                        }
                    }
                }
            }
        }

        val context = LocalContext.current
        TokenEditorDialog(
            show = showTokenEditor,
            initialValue = bearer,
            onSave = { onTokenChanged(it); showMessage(context.getString(R.string.status_success)) },
            onDismiss = { showTokenEditor = false }
        )

        RootPermissionDialog(
            show = rootStatus == RootStatus.DENIED,
            isRetrying = rootStatus == RootStatus.CHECKING,
            onRetry = onRetryRoot,
            onOpenManager = onOpenRootManager,
            onExit = onExitApp
        )
    }
}
