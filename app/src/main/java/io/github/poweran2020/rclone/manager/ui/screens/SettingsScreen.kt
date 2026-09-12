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
import androidx.compose.material.icons.filled.Terminal
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
import io.github.poweran2020.rclone.manager.ui.component.CoreLogDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.R
import io.github.poweran2020.rclone.manager.data.AppLanguage

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChanged: (AppLanguage) -> Unit,
    onEditToken: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSafeMode by remember { mutableStateOf(false) }
    var systemInfo by remember { mutableStateOf<SystemInfoItem?>(null) }
    var settings by remember { mutableStateOf<SystemSettingsItem?>(null) }
    var backups by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
    var migration by remember { mutableStateOf<MigrationStatusItem?>(null) }
    var auditLogs by remember { mutableStateOf<List<AuditLogItem>>(emptyList()) }

    var showCoreLogDialog by remember { mutableStateOf(false) }
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
                    SectionTitle(text = stringResource(R.string.settings_cat_appearance))
                    Text(
                        text = stringResource(R.string.settings_theme_title),
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
                                label = { Text(stringResource(mode.titleResId), style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(text = stringResource(R.string.settings_cat_language))
                    Text(
                        text = stringResource(R.string.settings_lang_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppLanguage.values().forEach { lang ->
                            FilterChip(
                                selected = appLanguage == lang,
                                onClick = { onAppLanguageChanged(lang) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        stringResource(lang.titleResId),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.settings_sys_info_title))
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                systemInfo?.let { info ->
                    InfoRow(label = stringResource(R.string.settings_srv_id), value = info.service)
                    InfoRow(label = stringResource(R.string.settings_rclone_ver), value = info.rcloneVersion)
                    InfoRow(label = stringResource(R.string.settings_gateway_ver), value = info.gatewayVersion)
                    InfoRow(label = stringResource(R.string.settings_api_ver), value = info.apiVersion)
                    InfoRow(
                        label = stringResource(R.string.settings_root_status),
                        value = if (info.root) stringResource(R.string.settings_root_authorized) else stringResource(R.string.settings_root_unauthorized)
                    )
                    InfoRow(
                        label = stringResource(R.string.settings_lan_listen),
                        value = if (info.lanEnabled) stringResource(R.string.settings_lan_enabled_tls) else stringResource(R.string.settings_lan_disabled_ipc)
                    )
                    InfoRow(
                        label = stringResource(R.string.settings_mtls),
                        value = if (info.mtlsRequired) stringResource(R.string.settings_mtls_required) else stringResource(R.string.settings_mtls_disabled)
                    )
                } ?: run {
                    Text(stringResource(R.string.settings_connecting_gateway), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.settings_safe_mode_title))
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
                            stringResource(R.string.settings_safe_mode_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSafeMode) stringResource(R.string.settings_safe_mode_active) else stringResource(R.string.settings_safe_mode_normal),
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
                                            onShowMessage(context.getString(R.string.settings_msg_safe_mode_exited))
                                        },
                                        onFailure = { onShowMessage(context.getString(R.string.settings_msg_exit_failed, it.message ?: "")) }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.settings_section_runtime_tuning))
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                MaterialTextField(
                    value = logRetentionDays,
                    onValueChange = { logRetentionDays = it },
                    label = stringResource(R.string.settings_label_log_retention)
                )
                MaterialTextField(
                    value = logMaxBytes,
                    onValueChange = { logMaxBytes = it },
                    label = stringResource(R.string.settings_label_log_max_bytes)
                )
                MaterialTextField(
                    value = cacheMaxBytes,
                    onValueChange = { cacheMaxBytes = it },
                    label = stringResource(R.string.settings_label_cache_max_bytes)
                )
                MaterialTextField(
                    value = maxConcurrentJobs,
                    onValueChange = { maxConcurrentJobs = it },
                    label = stringResource(R.string.settings_label_max_concurrent_jobs)
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
                                onSuccess = { onShowMessage(context.getString(R.string.settings_msg_runtime_updated)) },
                                onFailure = { onShowMessage(context.getString(R.string.settings_msg_save_failed, it.message ?: "")) }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_btn_save_settings))
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(text = stringResource(R.string.settings_section_uninstall_protection))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_keep_on_uninstall_title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_keep_on_uninstall_desc),
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
                                            onShowMessage(if (checked) context.getString(R.string.settings_keep_enabled) else context.getString(R.string.settings_keep_disabled))
                                        }
                                        .onFailure {
                                            onShowMessage(it.message ?: "")
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
            SectionTitle(text = stringResource(R.string.settings_section_database_backups, backups.size))
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
                            stringResource(R.string.settings_db_backup_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.settings_db_backup_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                client.createBackup(bearer).fold(
                                    onSuccess = {
                                        onShowMessage(context.getString(R.string.settings_msg_backup_created))
                                        client.backups(bearer).onSuccess { backups = parseBackups(it) }
                                    },
                                    onFailure = { onShowMessage(context.getString(R.string.settings_msg_backup_failed, it.message ?: "")) }
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_btn_backup_now))
                    }
                }

                if (backups.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_no_backups),
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
                                                StatusBadge(status = stringResource(R.string.settings_badge_bundle))
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
                                            Text(stringResource(R.string.settings_btn_restore), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = { backupToDelete = b },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), modifier = Modifier.size(14.dp))
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
                                Text(if (showAllBackups) stringResource(R.string.settings_collapse_backups) else stringResource(R.string.settings_view_all_backups, backups.size))
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.settings_section_legacy_migration))
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
                            stringResource(R.string.settings_legacy_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        migration?.let { m ->
                            StatusBadge(
                                status = if (m.alreadyMigrated) stringResource(R.string.settings_status_migrated) else if (!m.detectedLegacyPath.isNullOrEmpty()) stringResource(R.string.settings_status_migration_pending) else stringResource(R.string.settings_status_migration_none)
                            )
                        }
                    }

                    migration?.let { m ->
                        InfoRow(
                            label = stringResource(R.string.settings_label_detected_path),
                            value = m.detectedLegacyPath ?: stringResource(R.string.settings_no_detected_path)
                        )
                        InfoRow(label = stringResource(R.string.settings_label_migrated_jobs), value = "${m.migratedJobs}")
                        InfoRow(label = stringResource(R.string.settings_label_migration_errors), value = "${m.errorCount}")

                        OutlinedTextField(
                            value = legacyPathInput,
                            onValueChange = { legacyPathInput = it },
                            label = { Text(stringResource(R.string.settings_legacy_path_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showLegacyPathPicker = true }) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = stringResource(R.string.settings_choose_legacy_dir),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Text(stringResource(R.string.settings_legacy_presets_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text(stringResource(R.string.settings_preset_detected, detected), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val presets = listOf(
                                "/data/adb/modules/rclone" to stringResource(R.string.settings_preset_standard_module),
                                "/data/adb/modules" to stringResource(R.string.settings_preset_magisk_dir),
                                "/data/local/tmp/legacy_test" to stringResource(R.string.settings_preset_test_dir)
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
                                                onShowMessage(context.getString(R.string.settings_msg_migration_success))
                                                loadAllSettings()
                                            },
                                            onFailure = { onShowMessage(context.getString(R.string.settings_msg_migration_failed, it.message ?: "")) }
                                        )
                                        isMigrating = false
                                    }
                                },
                                enabled = !isMigrating && legacyPathInput.text.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isMigrating) stringResource(R.string.settings_btn_migrating) else if (m.alreadyMigrated) stringResource(R.string.settings_btn_remigrate) else stringResource(R.string.settings_btn_start_migration))
                            }

                            if (m.errors.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { showMigrationErrorsDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.settings_btn_view_errors, m.errors.size))
                                }
                            }
                        }
                    } ?: run {
                        Text(stringResource(R.string.settings_migration_querying), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            SectionTitle(text = stringResource(R.string.settings_section_logs_audit))
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 1. 核心运行日志
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.settings_gateway_log_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.settings_gateway_log_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { showCoreLogDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_view_core_log))
                        }
                    }

                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 2. 安全审计日志与清理
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.settings_audit_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.settings_audit_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                            onFailure = { onShowMessage(it.message ?: "") }
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_btn_view_audit))
                            }
                            Button(
                                onClick = { showClearLogsDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.settings_btn_clear_logs))
                            }
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
            title = { Text(stringResource(R.string.settings_safe_mode_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.settings_safe_mode_dialog_desc))
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            client.setSafeMode(true, bearer).fold(
                                onSuccess = {
                                    isSafeMode = true
                                    onShowMessage(context.getString(R.string.settings_msg_save_failed, ""))
                                    showSafeModeWarning = false
                                },
                                onFailure = { onShowMessage(it.message ?: "") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_btn_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafeModeWarning = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showAuditDialog) {
        AlertDialog(
            onDismissRequest = { showAuditDialog = false },
            title = { Text(stringResource(R.string.settings_audit_dialog_title, auditLogs.size), fontWeight = FontWeight.Bold) },
            text = {
                if (auditLogs.isEmpty()) {
                    Text(stringResource(R.string.settings_no_audit_events))
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
                                    stringResource(R.string.settings_audit_time, formatEpochTime(log.timestamp)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                log.clientId?.let { Text(stringResource(R.string.settings_audit_client, it), style = MaterialTheme.typography.bodySmall) }
                                log.latencyMs?.let { Text(stringResource(R.string.settings_audit_latency, it), style = MaterialTheme.typography.bodySmall) }
                                log.pathHash?.let {
                                    Text(
                                        stringResource(R.string.settings_audit_path_hash, it.take(16)),
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
                TextButton(onClick = { showAuditDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAuditDialog = false
                        showClearLogsDialog = true
                    }
                ) {
                    Text(stringResource(R.string.settings_btn_clear_logs_short), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isClearingLogs) showClearLogsDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_clear_logs_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.settings_clear_logs_dialog_desc))
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
                                    onShowMessage(context.getString(R.string.settings_msg_logs_cleared, files, audit))
                                },
                                onFailure = { err ->
                                    isClearingLogs = false
                                    onShowMessage(err.message ?: "")
                                }
                            )
                        }
                    },
                    enabled = !isClearingLogs,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isClearingLogs) stringResource(R.string.settings_btn_clearing_logs) else stringResource(R.string.settings_btn_confirm_clear))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearLogsDialog = false },
                    enabled = !isClearingLogs
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    backupToRestore?.let { b ->
        DangerousConfirmDialog(
            show = true,
            title = stringResource(R.string.settings_restore_dialog_title),
            message = stringResource(R.string.settings_restore_dialog_desc, b.name),
            confirmLabel = stringResource(R.string.settings_btn_confirm_restore),
            tokenBadge = "restore",
            showTokenValue = false,
            onDismiss = { backupToRestore = null },
            onConfirm = {
                val targetName = b.name
                backupToRestore = null
                scope.launch {
                    client.restoreBackup(targetName, bearer).fold(
                        onSuccess = {
                            onShowMessage(context.getString(R.string.settings_msg_restore_success))
                            loadAllSettings()
                        },
                        onFailure = { onShowMessage(context.getString(R.string.settings_msg_restore_failed, it.message ?: "")) }
                    )
                }
            }
        )
    }

    backupToDelete?.let { b ->
        val bundleSuffix = if (b.bundle) stringResource(R.string.settings_bundle_suffix) else ""
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_delete_backup_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.settings_delete_backup_desc, b.name, bundleSuffix)) },
            confirmButton = {
                Button(
                    onClick = {
                        val targetName = b.name
                        backupToDelete = null
                        scope.launch {
                            client.deleteBackup(targetName, bearer).fold(
                                onSuccess = {
                                    onShowMessage(context.getString(R.string.settings_msg_backup_deleted))
                                    client.backups(bearer).onSuccess { backups = parseBackups(it) }
                                },
                                onFailure = { onShowMessage(it.message ?: "") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_btn_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showMigrationErrorsDialog) {
        AlertDialog(
            onDismissRequest = { showMigrationErrorsDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.settings_migration_errors_title, migration?.errors?.size ?: 0), fontWeight = FontWeight.Bold) },
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
                            Text(stringResource(R.string.settings_err_file_line, err.file, err.line), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.settings_err_reason, err.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMigrationErrorsDialog = false }) {
                    Text(stringResource(R.string.settings_btn_got_it))
                }
            }
        )
    }

    if (showLegacyPathPicker) {
        PathPickerDialog(
            title = stringResource(R.string.settings_choose_legacy_dir),
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

    if (showCoreLogDialog) {
        CoreLogDialog(
            show = true,
            client = client,
            bearer = bearer,
            onDismiss = { showCoreLogDialog = false },
            onShowMessage = onShowMessage
        )
    }
}
