package io.github.poweran2020.rclone.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.R
import io.github.poweran2020.rclone.manager.data.model.GatewayErrorParser
import io.github.poweran2020.rclone.manager.data.model.SystemInfoItem
import io.github.poweran2020.rclone.manager.data.model.parseJobs
import io.github.poweran2020.rclone.manager.data.model.parseMounts
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.data.model.parseSystemInfo
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import io.github.poweran2020.rclone.manager.ui.component.TextButton
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onNavigateTab: (Int) -> Unit,
    onEditToken: () -> Unit,
    onTokenUpdated: (String) -> Unit = {},
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var healthStatus by remember { mutableStateOf("CONNECTING") }
    var showAdminGrantDialog by remember { mutableStateOf(false) }
    var healthError by remember { mutableStateOf<String?>(null) }
    var systemInfo by remember { mutableStateOf<SystemInfoItem?>(null) }
    var remotesCount by remember { mutableStateOf(0) }
    var activeJobsCount by remember { mutableStateOf(0) }
    var activeMountsCount by remember { mutableStateOf(0) }

    val refreshDashboard = {
        scope.launch {
            isRefreshing = true
            client.health().fold(
                onSuccess = {
                    if (it.contains("ok", true)) {
                        healthStatus = "ONLINE"
                        healthError = null
                    } else {
                        healthStatus = "OFFLINE"
                        healthError = it
                    }
                },
                onFailure = {
                    healthStatus = "OFFLINE"
                    healthError = it.message ?: "连接失败"
                }
            )
            if (bearer.isNotBlank()) {
                client.info(bearer).onSuccess { systemInfo = parseSystemInfo(it) }
                client.remotes(bearer).onSuccess { remotesCount = parseRemotes(it).size }
                client.jobs(bearer).onSuccess {
                    activeJobsCount = parseJobs(it).count { j -> j.status.uppercase() in setOf("RUNNING", "QUEUED") }
                }
                client.mounts(bearer).onSuccess {
                    activeMountsCount = parseMounts(it).count { m -> m.status.uppercase() == "RUNNING" }
                }
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(bearer) {
        refreshDashboard()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    text = stringResource(R.string.dashboard_service_status_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { refreshDashboard() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isRefreshing) stringResource(R.string.action_refreshing) else stringResource(R.string.action_refresh),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Root Gateway", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Unix Socket: /data/adb/rclone-manage", style = MaterialTheme.typography.bodySmall)
                    }
                    StatusBadge(status = healthStatus)
                }
                Spacer(Modifier.height(8.dp))
                val isOffline = healthStatus.contains("OFFLINE", true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                client.startGatewayService().fold(
                                    onSuccess = { onShowMessage(it) },
                                    onFailure = {
                                        val friendly = GatewayErrorParser.parse(it.message ?: "")
                                        onShowMessage("启动失败: ${friendly.title}")
                                    }
                                )
                                kotlinx.coroutines.delay(1200)
                                refreshDashboard()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshing,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_start), maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                client.stopGatewayService().fold(
                                    onSuccess = { onShowMessage(it) },
                                    onFailure = {
                                        val friendly = GatewayErrorParser.parse(it.message ?: "")
                                        onShowMessage("停止失败: ${friendly.title}")
                                    }
                                )
                                kotlinx.coroutines.delay(600)
                                refreshDashboard()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshing && !isOffline,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_stop), maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                client.restartGatewayService().fold(
                                    onSuccess = { onShowMessage(it) },
                                    onFailure = {
                                        val friendly = GatewayErrorParser.parse(it.message ?: "")
                                        onShowMessage("重启失败: ${friendly.title}")
                                    }
                                )
                                kotlinx.coroutines.delay(1500)
                                refreshDashboard()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isRefreshing,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_restart), maxLines = 1, softWrap = false)
                    }
                }
                if (!healthError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    val clipboard = LocalClipboardManager.current
                    val friendly = remember(healthError) { GatewayErrorParser.parse(healthError!!) }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = friendly.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(friendly.rawDetails))
                                        onShowMessage("已复制底层异常日志")
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.dashboard_copy_log),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            Text(
                                text = friendly.suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.dashboard_raw_log_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = friendly.rawDetails,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                systemInfo?.let { info ->
                    InfoRow(label = stringResource(R.string.dashboard_rclone_version), value = info.rcloneVersion)
                    InfoRow(label = stringResource(R.string.dashboard_gateway_version), value = info.gatewayVersion)
                    InfoRow(label = stringResource(R.string.dashboard_root_elevate), value = if (info.root) stringResource(R.string.status_authorized) else stringResource(R.string.status_unauthorized))
                    InfoRow(label = stringResource(R.string.dashboard_lan_tls), value = if (info.lanEnabled) stringResource(R.string.status_enabled) else stringResource(R.string.status_disabled_safe))
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.dashboard_resources_title))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ContentCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(1) }
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.dashboard_remotes_stat), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.count_unit, remotesCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                ContentCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(3) }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(stringResource(R.string.dashboard_active_jobs_stat), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.count_unit, activeJobsCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                ContentCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(4) }
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.dashboard_active_mounts_stat), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.count_unit, activeMountsCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.dashboard_security_creds_title))
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (bearer.isBlank()) stringResource(R.string.dashboard_token_unconfigured) else stringResource(R.string.dashboard_token_configured),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.dashboard_token_stored_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (bearer.isBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showAdminGrantDialog = true }
                            ) {
                                Text(stringResource(R.string.dashboard_btn_auto_pair))
                            }
                            OutlinedButton(onClick = onEditToken) {
                                Text(stringResource(R.string.action_setup))
                            }
                        }
                    } else {
                        Button(onClick = onEditToken) {
                            Text(stringResource(R.string.action_change))
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.dashboard_quick_actions))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(1) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.dashboard_btn_manage_remotes))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(2) }
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(stringResource(R.string.dashboard_btn_browse_files))
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_security_arch_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.dashboard_security_arch_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAdminGrantDialog) {
        AlertDialog(
            onDismissRequest = { showAdminGrantDialog = false },
            title = { Text(stringResource(R.string.dashboard_pair_dialog_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.dashboard_pair_dialog_desc))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdminGrantDialog = false
                        scope.launch {
                            isRefreshing = true
                            client.autoPair(grantAdmin = true).fold(
                                onSuccess = {
                                    onTokenUpdated(it)
                                    onShowMessage("配对成功！已获取管理员特权令牌")
                                },
                                onFailure = { onShowMessage("自动配对失败: ${it.message}") }
                            )
                            refreshDashboard()
                        }
                    }
                ) {
                    Text(stringResource(R.string.dashboard_pair_grant_admin))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(stringResource(R.string.action_cancel), onClick = { showAdminGrantDialog = false })
                    OutlinedButton(
                        onClick = {
                            showAdminGrantDialog = false
                            scope.launch {
                                isRefreshing = true
                                client.autoPair(grantAdmin = false).fold(
                                    onSuccess = {
                                        onTokenUpdated(it)
                                        onShowMessage("配对成功！已获取常规权限令牌")
                                    },
                                    onFailure = { onShowMessage("自动配对失败: ${it.message}") }
                                )
                                refreshDashboard()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dashboard_pair_grant_regular))
                    }
                }
            }
        )
    }
}
