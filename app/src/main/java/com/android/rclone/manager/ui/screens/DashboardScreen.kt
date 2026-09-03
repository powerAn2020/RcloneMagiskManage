package com.android.rclone.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.android.rclone.manager.GatewayClient
import com.android.rclone.manager.data.model.SystemInfoItem
import com.android.rclone.manager.data.model.parseJobs
import com.android.rclone.manager.data.model.parseMounts
import com.android.rclone.manager.data.model.parseRemotes
import com.android.rclone.manager.data.model.parseSystemInfo
import com.android.rclone.manager.ui.component.ContentCard
import com.android.rclone.manager.ui.component.InfoRow
import com.android.rclone.manager.ui.component.SectionTitle
import com.android.rclone.manager.ui.component.StatusBadge
import com.android.rclone.manager.ui.component.TextButton
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onNavigateTab: (Int) -> Unit,
    onEditToken: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var healthStatus by remember { mutableStateOf("正在连接 Gateway…") }
    var systemInfo by remember { mutableStateOf<SystemInfoItem?>(null) }
    var remotesCount by remember { mutableStateOf(0) }
    var activeJobsCount by remember { mutableStateOf(0) }
    var activeMountsCount by remember { mutableStateOf(0) }

    val refreshDashboard = {
        scope.launch {
            isRefreshing = true
            client.health().fold(
                onSuccess = { healthStatus = if (it.contains("ok", true)) "ONLINE" else it },
                onFailure = { healthStatus = "OFFLINE: ${it.message}" }
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
                SectionTitle(text = "系统与服务状态")
                OutlinedButton(
                    onClick = { refreshDashboard() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text(if (isRefreshing) "刷新中…" else "刷新")
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
                    Column {
                        Text("Root Gateway", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Unix Socket: /data/adb/rclone-manage", style = MaterialTheme.typography.bodySmall)
                    }
                    StatusBadge(status = healthStatus)
                }
                Spacer(Modifier.height(4.dp))
                systemInfo?.let { info ->
                    InfoRow(label = "rclone 版本", value = info.rcloneVersion)
                    InfoRow(label = "Gateway 版本", value = info.gatewayVersion)
                    InfoRow(label = "Root 提权", value = if (info.root) "已授权" else "未授权")
                    InfoRow(label = "LAN TLS", value = if (info.lanEnabled) "已启用" else "已禁用 (安全默认)")
                }
            }
        }

        item {
            SectionTitle(text = "资源与运行统计")
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
                    Text("远端配置", style = MaterialTheme.typography.labelMedium)
                    Text("$remotesCount 个", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                ContentCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(3) }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text("活跃任务", style = MaterialTheme.typography.labelMedium)
                    Text("$activeJobsCount 个", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                ContentCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(4) }
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("活跃挂载", style = MaterialTheme.typography.labelMedium)
                    Text("$activeMountsCount 个", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            SectionTitle(text = "安全凭据")
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
                            text = if (bearer.isBlank()) "Gateway Token 未配置" else "Gateway Token 已配置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Token 使用 Android Keystore 加密存储，仅本地持有。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onEditToken) {
                        Text(if (bearer.isBlank()) "设置" else "更换")
                    }
                }
            }
        }

        item {
            SectionTitle(text = "快捷操作")
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
                    Text("管理远端")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateTab(2) }
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("浏览文件")
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Text("安全与控制架构声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "• App 仅调用固定的 typed request 客户端合约，严禁暴露 raw rclone RC。\n" +
                    "• 密码/Token 均在 Gateway 侧使用 XChaCha20-Poly1305 加密保存，API 绝不返回明文。\n" +
                    "• 文件删除、远端删除均受到 60 秒一次性确认令牌与 dry-run 保护。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
