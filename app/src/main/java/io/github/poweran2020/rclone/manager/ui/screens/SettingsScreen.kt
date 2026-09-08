package io.github.poweran2020.rclone.manager.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.github.poweran2020.rclone.manager.data.ThemeMode
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
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.PathPickerDialog
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
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
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

    var backupToRestore by remember { mutableStateOf<BackupItem?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupItem?>(null) }
    var showAllBackups by remember { mutableStateOf(false) }

    var legacyPathInput by remember { mutableStateOf(TextFieldValue("")) }
    var isMigrating by remember { mutableStateOf(false) }
    var showMigrationErrorsDialog by remember { mutableStateOf(false) }
    var showLegacyPathPicker by remember { mutableStateOf(false) }

    var keepOnUninstall by remember { mutableStateOf(false) }
    var isUpdatingKeepState by remember { mutableStateOf(false) }

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
            client.migrationStatus(bearer).onSuccess {
                val m = parseMigrationStatus(it)
                migration = m
                if (legacyPathInput.text.isEmpty() && !m.detectedLegacyPath.isNullOrEmpty()) {
                    legacyPathInput = TextFieldValue(m.detectedLegacyPath)
                }
            }
            launch {
                keepOnUninstall = client.isKeepOnUninstallEnabled()
            }
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
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(text = "界面外观主题")
                    Text(
                        text = "选择应用显示配色风格，支持浅色、深色及跟随系统夜间模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.values().forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChanged(mode) },
                                label = { Text(mode.title, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }
        }

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
                    InfoRow(
                        label = "局域网 (LAN) 监听",
                        value = if (info.lanEnabled) "已启用 (TLS)" else "未启用 (仅本地 IPC)"
                    )
                    InfoRow(label = "双向证书认证 (mTLS)", value = if (info.mtlsRequired) "强制启用" else "禁用")
                } ?: run {
                    Text("正在连接并读取网关环境…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionTitle(text = "安全模式")
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
                            "Safe Mode (安全模式)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                MaterialTextField(
                    value = logRetentionDays,
                    onValueChange = { logRetentionDays = it },
                    label = "日志保留天数 (1..365 天)"
                )
                MaterialTextField(
                    value = logMaxBytes,
                    onValueChange = { logMaxBytes = it },
                    label = "单个日志轮转阈值 (字节，如 10485760)"
                )
                MaterialTextField(
                    value = cacheMaxBytes,
                    onValueChange = { cacheMaxBytes = it },
                    label = "挂载缓存容量上限 (字节，如 34359738368)"
                )
                MaterialTextField(
                    value = maxConcurrentJobs,
                    onValueChange = { maxConcurrentJobs = it },
                    label = "全局最大并发任务数 (1..4)"
                )

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
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(text = "模块卸载与数据保护")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "卸载模块时保留数据",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "在数据目录创建 KEEP_ON_UNINSTALL，卸载 Magisk/KernelSU 模块时跳过删除数据目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = keepOnUninstall,
                            enabled = !isUpdatingKeepState,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    isUpdatingKeepState = true
                                    client.setKeepOnUninstallEnabled(checked)
                                        .onSuccess {
                                            keepOnUninstall = checked
                                            onShowMessage(if (checked) "已开启：卸载模块时保留数据" else "已关闭：卸载模块时将清除数据")
                                        }
                                        .onFailure {
                                            onShowMessage("设置失败: ${it.message}")
                                        }
                                    isUpdatingKeepState = false
                                }
                            }
                        )
                    }
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SQLite WAL 状态备份",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "支持数据库快照热备份与前置保护还原。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    Text(
                        "暂无备份文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val displayed = if (showAllBackups) backups else backups.take(5)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        displayed.forEach { b ->
                            ContentCard(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = PaddingValues(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(b.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            if (b.bundle) {
                                                StatusBadge(status = "带密钥")
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${formatBytes(b.size)} · ${formatEpochTime(b.timestamp)}${if (b.sha256.isNotEmpty()) " · SHA:" + b.sha256.take(8) else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(
                                            onClick = { backupToRestore = b },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("还原", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = { backupToDelete = b },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (backups.size > 5) {
                            TextButton(
                                onClick = { showAllBackups = !showAllBackups },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(if (showAllBackups) "收起多余备份" else "查看全部备份 (${backups.size})")
                            }
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "旧版 Magisk 模块历史配置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        migration?.let { m ->
                            StatusBadge(
                                status = if (m.alreadyMigrated) "已迁移完成" else if (!m.detectedLegacyPath.isNullOrEmpty()) "发现待迁移配置" else "未检测到配置"
                            )
                        }
                    }

                    migration?.let { m ->
                        InfoRow(
                            label = "自动探测路径",
                            value = m.detectedLegacyPath ?: "未在常见模块目录找到配置"
                        )
                        InfoRow(label = "成功转换任务数", value = "${m.migratedJobs} 个")
                        InfoRow(label = "解析异常/忽略记录", value = "${m.errorCount} 条")

                        OutlinedTextField(
                            value = legacyPathInput,
                            onValueChange = { legacyPathInput = it },
                            label = { Text("旧模块目录 (包含 rclone.conf / sync / copy)") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showLegacyPathPicker = true }) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = "浏览选择本地目录",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Text("常见旧模块路径预设:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val detected = m.detectedLegacyPath
                            if (!detected.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = { legacyPathInput = TextFieldValue(detected) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("探测目录: $detected", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val presets = listOf(
                                "/data/adb/modules/rclone" to "标准 rclone 模块",
                                "/data/adb/modules" to "Magisk 模块目录",
                                "/data/local/tmp/legacy_test" to "测试目录"
                            )
                            presets.forEach { (path, label) ->
                                OutlinedButton(
                                    onClick = { legacyPathInput = TextFieldValue(path) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isMigrating = true
                                    scope.launch {
                                        client.runMigration(legacyPathInput.text.trim().ifEmpty { null }, bearer).fold(
                                            onSuccess = {
                                                onShowMessage("历史配置已成功迁移导入")
                                                loadAllSettings()
                                            },
                                            onFailure = { onShowMessage("迁移失败: ${it.message}") }
                                        )
                                        isMigrating = false
                                    }
                                },
                                enabled = !isMigrating && legacyPathInput.text.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isMigrating) "正在迁移…" else if (m.alreadyMigrated) "重新/增量迁移" else "开始配置迁移")
                            }

                            if (m.errors.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { showMigrationErrorsDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("查看异常 (${m.errors.size})")
                                }
                            }
                        }
                    } ?: run {
                        Text("正在查询配置迁移状态…", style = MaterialTheme.typography.bodySmall)
                    }
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
                            Text(
                                "Root 操作与安全审计",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "全链路操作事件已记录，敏感文件名经由 SHA-256 哈希脱敏。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                    Text(
                                        log.operation,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    StatusBadge(status = log.result)
                                }
                                Text(
                                    "时间: ${formatEpochTime(log.timestamp)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                log.clientId?.let { Text("客户端: $it", style = MaterialTheme.typography.bodySmall) }
                                log.latencyMs?.let { Text("耗时: ${it}ms", style = MaterialTheme.typography.bodySmall) }
                                log.pathHash?.let {
                                    Text(
                                        "路径 Hash: ${it.take(16)}…",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
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

    backupToRestore?.let { b ->
        DangerousConfirmDialog(
            show = true,
            title = "确认还原数据库？",
            message = "将使用备份 [${b.name}] 覆盖当前运行中的数据库及密钥配置。网关将自动创建 pre-restore 安全快照并重新载入连接。",
            confirmLabel = "确认还原",
            tokenBadge = "restore",
            showTokenValue = false,
            onDismiss = { backupToRestore = null },
            onConfirm = {
                val targetName = b.name
                backupToRestore = null
                scope.launch {
                    client.restoreBackup(targetName, bearer).fold(
                        onSuccess = {
                            onShowMessage("数据库已成功还原！已重新加载状态")
                            loadAllSettings()
                        },
                        onFailure = { onShowMessage("还原失败: ${it.message}") }
                    )
                }
            }
        )
    }

    backupToDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认删除备份？", fontWeight = FontWeight.Bold) },
            text = { Text("即将永久删除备份文件 [${b.name}]${if (b.bundle) " 及其私钥 Bundle 目录" else ""}，此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        val targetName = b.name
                        backupToDelete = null
                        scope.launch {
                            client.deleteBackup(targetName, bearer).fold(
                                onSuccess = {
                                    onShowMessage("备份已删除")
                                    client.backups(bearer).onSuccess { backups = parseBackups(it) }
                                },
                                onFailure = { onShowMessage("删除失败: ${it.message}") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showMigrationErrorsDialog) {
        AlertDialog(
            onDismissRequest = { showMigrationErrorsDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("配置迁移异常明细 (${migration?.errors?.size ?: 0})", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(migration?.errors ?: emptyList()) { err ->
                        ContentCard(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(10.dp)
                        ) {
                            Text("文件: ${err.file}:${err.line}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text("原因: ${err.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMigrationErrorsDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }

    if (showLegacyPathPicker) {
        PathPickerDialog(
            title = "选择旧模块配置目录",
            initialPath = legacyPathInput.text.ifBlank { migration?.detectedLegacyPath ?: "/data/adb/modules" },
            remotes = emptyList(),
            client = client,
            bearer = bearer,
            onDismiss = { showLegacyPathPicker = false },
            onConfirm = { chosen ->
                val p = if (chosen.contains(":")) chosen.substringAfter(":") else chosen
                legacyPathInput = TextFieldValue(p)
                showLegacyPathPicker = false
            }
        )
    }
}
