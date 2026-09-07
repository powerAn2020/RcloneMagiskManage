package io.github.poweran2020.rclone.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.topjohnwu.superuser.Shell
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
    private val tokenState = mutableStateOf("")
    private val rootStatusState = mutableStateOf(RootStatus.CHECKING)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = TokenStore(this)
        tokenState.value = tokenStore.read()

        checkRootPermission(initial = true)

        setContent {
            RcloneTheme {
                RcloneApp(
                    client = client,
                    tokenStore = tokenStore,
                    bearer = tokenState.value,
                    rootStatus = rootStatusState.value,
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
                runCatching {
                    Shell.getShell().isRoot
                }.getOrDefault(false)
            }
            rootStatusState.value = if (isRoot) RootStatus.GRANTED else RootStatus.DENIED
        }
    }

    private fun openRootManager() {
        val managerPackages = listOf(
            "me.weishu.kernelsu",
            "com.topjohnwu.magisk",
            "org.apatch"
        )
        for (pkg in managerPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
                ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
            val resolved = runCatching {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (resolved) return
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

    val navTabs = listOf("首页", "远端", "文件", "任务", "更多")
    val navIcons = listOf(
        Icons.Default.Home,
        Icons.Default.Cloud,
        Icons.Default.Folder,
        Icons.Default.PlayArrow,
        Icons.Default.MoreHoriz
    )
    val moreSubTabs = listOf("挂载管理", "加密档案", "安全配对", "系统运维")

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
                        label = { Text(label) }
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
                                onEditToken = { showTokenEditor = true },
                                onShowMessage = showMessage
                            )
                        }
                    }
                }
            }
        }

        TokenEditorDialog(
            show = showTokenEditor,
            initialValue = bearer,
            onSave = { onTokenChanged(it); showMessage("Token 已保存并加密到 Keystore") },
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

