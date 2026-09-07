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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.AuditLogItem
import io.github.poweran2020.rclone.manager.data.model.BackupItem
import io.github.poweran2020.rclone.manager.data.model.MigrationStatusItem
import io.github.poweran2020.rclone.manager.data.model.SystemInfoItem
import io.github.poweran2020.rclone.manager.data.model.SystemSettingsItem
import io.github.poweran2020.rclone.manager.data.model.formatBytes
import io.github.poweran2020.rclone.manager.data.model.formatEpochTime
import io.github.poweran2020.rclone.manager.data.model.parseAuditLogs
import io.github.poweran2020.rclone.manager.data.model.parseBackups
import io.github.poweran2020.rclone.manager.data.model.parseMigrationStatus
import io.github.poweran2020.rclone.manager.data.model.parseSystemInfo
import io.github.poweran2020.rclone.manager.data.model.parseSystemSettings
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.PreferenceRow
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import io.github.poweran2020.rclone.manager.ui.component.TogglePreference
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onEditToken: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isSafeMode by remember { mutableStateOf(false) }
    var systemInfo by remember { mutableStateOf<SystemInfoItem?>(null) }
    var settings by remember { mutableStateOf<SystemSettingsItem?>(null) }
    var backups by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
    var migration by remember { mutableStateOf<MigrationStatusItem?>(null) }
    var auditLogs by remember { mutableStateOf<List<AuditLogItem>>(emptyList()) }

    var showAuditDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var isClearingLogs by remember { mutableStateOf(false) }
    var showSafeModeWarning by remember { mutableStateOf(false) }

    // Editable settings
    var logRetentionDays by remember { mutableStateOf(TextFieldValue("14")) }
    var logMaxBytes by remember { mutableStateOf(TextFieldValue("10485760")) }
    var cacheMaxBytes by remember { mutableStateOf(TextFieldValue("34359738368")) }
    var maxConcurrentJobs by remember { mutableStateOf(TextFieldValue("2")) }

    val loadAllSettings = {
        scope.launch {
            client.safeMode(bearer).onSuccess {
                isSafeMode = JSONObject(it).optBoolean("enabled", false)
            }
            client.info(bearer).onSuccess { systemInfo = parseSystemInfo(it) }
            client.settings(bearer).onSuccess {
                val s = parseSystemSettings(it)
                settings = s
                logRetentionDays = TextFieldValue(s.logRetentionDays.toString())
                logMaxBytes = TextFieldValue(s.logMaxBytes.toString())
                cacheMaxBytes = TextFieldValue(s.cacheMaxBytes.toString())
                maxConcurrentJobs = TextFieldValue(s.maxConcurrentJobs.toString())
            }
            client.backups(bearer).onSuccess { backups = parseBackups(it) }
            client.migrationStatus(bearer).onSuccess { migration = parseMigrationStatus(it) }
        }
    }

    LaunchedEffect(bearer) {
        loadAllSettings()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(text = "系统与服务信息")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                systemInfo?.let { info ->
                    InfoRow(label = "服务标识", value = info.service)
                    InfoRow(label = "rclone 核心版本", value = info.rcloneVersion)
                    InfoRow(label = "Root Gateway 版本", value = info.gatewayVersion)
                    InfoRow(label = "API 合约版本", value = info.apiVersion)
                    InfoRow(label = "Root 运行状态", value = if (info.root) "已授权 (Root)" else "非 Root 模式")
                    InfoRow(label = "局域网 (LAN) 监听", value = if (info.lanEnabled) "已启用 (TLS)" else "未启用 (仅本地 IPC)")
                    InfoRow(label = "双向证书认证 (mTLS)", value = if (info.mtlsRequired) "强制启用" else "禁用")
                } ?: run {
                    Text("正在连接并读取网关环境…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionTitle(text = "紧急与安全模式")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mode (安全模式)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isSafeMode) "已进入安全模式！所有调度任务暂停，挂载停止。" else "正常运行模式，计划任务与开机恢复正常生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSafeMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSafeMode,
                        onCheckedChange = { targetState ->
                            if (targetState) {
                                showSafeModeWarning = true
                            } else {
                                scope.launch {
                                    client.setSafeMode(false, bearer).fold(
                                        onSuccess = {
                                            isSafeMode = false
                                            onShowMessage("已退出安全模式")
                                        },
                                        onFailure = { onShowMessage("退出失败: ${it.message}") }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        item {
            SectionTitle(text = "Gateway 运行时参数调优")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                MaterialTextField(value = logRetentionDays, onValueChange = { logRetentionDays = it }, label = "日志保留天数 (1..365 天)")
                MaterialTextField(value = logMaxBytes, onValueChange = { logMaxBytes = it }, label = "单个日志轮转阈值 (字节，如 10485760)")
                MaterialTextField(value = cacheMaxBytes, onValueChange = { cacheMaxBytes = it }, label = "挂载缓存容量上限 (字节，如 34359738368)")
                MaterialTextField(value = maxConcurrentJobs, onValueChange = { maxConcurrentJobs = it }, label = "全局最大并发任务数 (1..4)")

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val json = JSONObject().apply {
                            logRetentionDays.text.toLongOrNull()?.let { put("logRetentionDays", it) }
                            logMaxBytes.text.toLongOrNull()?.let { put("logMaxBytes", it) }
                            cacheMaxBytes.text.toLongOrNull()?.let { put("cacheMaxBytes", it) }
                            maxConcurrentJobs.text.toLongOrNull()?.let { put("maxConcurrentJobs", it) }
                        }
                        scope.launch {
                            client.updateSettings(json, bearer).fold(
                                onSuccess = { onShowMessage("运行时设置已更新") },
                                onFailure = { onShowMessage("保存设置失败: ${it.message}") }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存设置")
                }
            }
        }

        item {
            SectionTitle(text = "数据库状态与备份 (${backups.size})")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SQLite WAL 状态备份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            scope.launch {
                                client.createBackup(bearer).fold(
                                    onSuccess = {
                                        onShowMessage("备份已成功创建")
                                        client.backups(bearer).onSuccess { backups = parseBackups(it) }
                                    },
                                    onFailure = { onShowMessage("创建备份失败: ${it.message}") }
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即备份")
                    }
                }
                if (backups.isEmpty()) {
                    Text("暂无备份文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        backups.take(5).forEach { b ->
                            InfoRow(label = b.name, value = "${formatBytes(b.size)} · ${formatEpochTime(b.timestamp)}")
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(text = "历史配置迁移")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                migration?.let { m ->
                    InfoRow(label = "上游旧配置迁移状态", value = if (m.alreadyMigrated) "已迁移 (完成)" else "尚未检测到迁移")
                    InfoRow(label = "成功转换任务数", value = "${m.migratedJobs} 个")
                    InfoRow(label = "解析异常忽略数", value = "${m.errorCount} 条")
                } ?: run {
                    Text("正在查询配置迁移状态…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionTitle(text = "日志管理与安全审计")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Root 操作与安全审计", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("全链路操作事件已记录，敏感文件名经由 SHA-256 哈希脱敏。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.auditLogs(bearer).fold(
                                        onSuccess = {
                                            auditLogs = parseAuditLogs(it)
                                            showAuditDialog = true
                                        },
                                        onFailure = { onShowMessage("获取审计日志失败: ${it.message}") }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("查看日志")
                        }
                        Button(
                            onClick = { showClearLogsDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清理日志")
                        }
                    }
                }
            }
        }
    }

    if (showSafeModeWarning) {
        AlertDialog(
            onDismissRequest = { showSafeModeWarning = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认进入 Safe Mode？", fontWeight = FontWeight.Bold) },
            text = {
                Text("进入 Safe Mode 后，所有计划中的任务将停止启动，已运行的 Mount worker 将被安全回收。此操作用于紧急排障或设备异常修复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            client.setSafeMode(true, bearer).fold(
                                onSuccess = {
                                    isSafeMode = true
                                    onShowMessage("已开启安全模式")
                                    showSafeModeWarning = false
                                },
                                onFailure = { onShowMessage("开启安全模式失败: ${it.message}") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafeModeWarning = false }) { Text("取消") }
            }
        )
    }

    if (showAuditDialog) {
        AlertDialog(
            onDismissRequest = { showAuditDialog = false },
            title = { Text("审计事件日志 (最近 ${auditLogs.size} 条)", fontWeight = FontWeight.Bold) },
            text = {
                if (auditLogs.isEmpty()) {
                    Text("暂无审计事件。")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(auditLogs) { log ->
                            ContentCard(insideMargin = PaddingValues(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(log.operation, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    StatusBadge(status = log.result)
                                }
                                Text("时间: ${formatEpochTime(log.timestamp)}", style = MaterialTheme.typography.bodySmall)
                                log.clientId?.let { Text("客户端: $it", style = MaterialTheme.typography.bodySmall) }
                                log.latencyMs?.let { Text("耗时: ${it}ms", style = MaterialTheme.typography.bodySmall) }
                                log.pathHash?.let {
                                    Text("路径 Hash: ${it.take(16)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAuditDialog = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAuditDialog = false
                        showClearLogsDialog = true
                    }
                ) {
                    Text("清理日志", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isClearingLogs) showClearLogsDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认清理所有日志？", fontWeight = FontWeight.Bold) },
            text = {
                Text("此操作将截断清空网关运行日志（gateway.log、job-*.log 等）并清空历史安全审计事件记录，释放存储空间。该操作不可撤销。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isClearingLogs = true
                        scope.launch {
                            client.clearLogs(bearer).fold(
                                onSuccess = { res ->
                                    isClearingLogs = false
                                    showClearLogsDialog = false
                                    val obj = runCatching { JSONObject(res) }.getOrNull()
                                    val files = obj?.optInt("filesCleared", 0) ?: 0
                                    val audit = obj?.optInt("auditRecordsCleared", 0) ?: 0
                                    auditLogs = emptyList()
                                    onShowMessage("日志清理完成：已清理 $files 个日志文件，清除 $audit 条审计记录")
                                },
                                onFailure = { err ->
                                    isClearingLogs = false
                                    onShowMessage("清理日志失败: ${err.message}")
                                }
                            )
                        }
                    },
                    enabled = !isClearingLogs,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isClearingLogs) "正在清理…" else "确认清理")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearLogsDialog = false },
                    enabled = !isClearingLogs
                ) {
                    Text("取消")
                }
            }
        )
    }
}
