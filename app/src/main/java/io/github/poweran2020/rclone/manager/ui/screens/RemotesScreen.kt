package io.github.poweran2020.rclone.manager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.MountProfileItem
import io.github.poweran2020.rclone.manager.data.model.RemoteExportData
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.parseMounts
import io.github.poweran2020.rclone.manager.data.model.parseRemoteExport
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.QrCodeUtils
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import kotlinx.coroutines.launch
import org.json.JSONObject

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import io.github.poweran2020.rclone.manager.data.model.ProviderItem
import io.github.poweran2020.rclone.manager.data.model.ProviderOption
import io.github.poweran2020.rclone.manager.data.model.parseProviders
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotesScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onNavigateToFileBrowser: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var remotes by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var providers by remember { mutableStateOf<List<ProviderItem>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var editingRemote by remember { mutableStateOf<RemoteItem?>(null) }
    var exportData by remember { mutableStateOf<RemoteExportData?>(null) }
    var deleteCandidate by remember { mutableStateOf<Pair<RemoteItem, String>?>(null) } // RemoteItem to confirmationToken
    var mountConflictWarning by remember { mutableStateOf<Pair<RemoteItem, List<MountProfileItem>>?>(null) }
    var isDeletingRemote by remember { mutableStateOf(false) }

    val loadRemotes = {
        scope.launch {
            isLoading = true
            client.remotes(bearer).fold(
                onSuccess = { remotes = parseRemotes(it) },
                onFailure = { onShowMessage("获取远端失败: ${it.message}") }
            )
            isLoading = false
        }
    }

    LaunchedEffect(bearer) {
        loadRemotes()
        scope.launch {
            client.remoteProviders(bearer).onSuccess {
                providers = parseProviders(it)
            }
        }
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
                SectionTitle(text = stringResource(R.string.remotes_count_header, remotes.size))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { loadRemotes() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.action_refresh))
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.remotes_btn_import))
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.remotes_btn_add))
                    }
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = stringResource(R.string.remotes_loading)) }
        } else if (remotes.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Cloud,
                    title = stringResource(R.string.remotes_empty_title),
                    message = stringResource(R.string.remotes_empty_desc)
                )
            }
        } else {
            items(remotes, key = { it.id }) { remote ->
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(remote.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val endpointText = remote.endpoint.takeIf { it.isNotBlank() && it != "null" }
                            Text(
                                text = stringResource(R.string.remote_type_prefix, remote.type) + if (endpointText != null) " · $endpointText" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusBadge(status = if (remote.enabled) "ENABLED" else "DISABLED")
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    onShowMessage("正在测试连接 ${remote.name}…")
                                    client.testRemote(remote.id, bearer).fold(
                                        onSuccess = { res ->
                                            val json = runCatching { JSONObject(res) }.getOrNull()
                                            if (json?.optBoolean("ok") == true) {
                                                onShowMessage("测试成功: 远端服务连接正常")
                                            } else {
                                                val errMsg = json?.optString("error")?.ifBlank { null } ?: "无法连接到该远端服务"
                                                onShowMessage("测试失败: $errMsg")
                                            }
                                        },
                                        onFailure = { onShowMessage("测试请求失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(R.string.remotes_btn_test_short))
                        }

                        OutlinedButton(onClick = { onNavigateToFileBrowser(remote.id) }) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(R.string.remotes_btn_browse_short))
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val action = if (remote.enabled) "disable" else "enable"
                                    client.remoteAction(remote.id, action, bearer).fold(
                                        onSuccess = {
                                            onShowMessage("远端已${if (remote.enabled) "禁用" else "启用"}")
                                            loadRemotes()
                                        },
                                        onFailure = { onShowMessage("操作失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Text(stringResource(if (remote.enabled) R.string.remotes_btn_disable else R.string.remotes_btn_enable))
                        }

                        IconButton(onClick = { editingRemote = remote }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    client.exportRemote(remote.id, bearer).fold(
                                        onSuccess = { exportData = parseRemoteExport(it) },
                                        onFailure = { onShowMessage("导出失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "导出分享")
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    // 前置检查：是否存在关联的挂载配置
                                    val mountsRes = client.mounts(bearer)
                                    val referencedMounts = mountsRes.getOrNull()?.let { parseMounts(it) }?.filter {
                                        it.remoteId == remote.id || it.remoteName == remote.name
                                    } ?: emptyList()

                                    if (referencedMounts.isNotEmpty()) {
                                        mountConflictWarning = remote to referencedMounts
                                        return@launch
                                    }

                                    client.remoteDelete(remote.id, bearer).fold(
                                        onSuccess = { preview ->
                                            val token = JSONObject(preview).optString("confirmationToken")
                                            if (token.isNotBlank()) {
                                                deleteCandidate = remote to token
                                            } else {
                                                onShowMessage("已删除")
                                                loadRemotes()
                                            }
                                        },
                                        onFailure = { onShowMessage("删除失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RemoteFormDialog(
            title = stringResource(R.string.remotes_dialog_add_title),
            initialName = "",
            initialType = "webdav",
            initialEndpoint = "",
            providers = providers,
            isEditing = false,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, type, endpoint, secret ->
                scope.launch {
                    client.createRemote(name, type, endpoint.ifBlank { null }, secret, bearer).fold(
                        onSuccess = {
                            onShowMessage("远端添加成功")
                            showCreateDialog = false
                            loadRemotes()
                        },
                        onFailure = { onShowMessage("添加失败: ${it.message}") }
                    )
                }
            }
        )
    }

    editingRemote?.let { remote ->
        RemoteFormDialog(
            title = "${stringResource(R.string.remotes_dialog_edit_title)}: ${remote.name}",
            initialName = remote.name,
            initialType = remote.type,
            initialEndpoint = remote.endpoint,
            initialOptions = remote.options,
            configuredSecrets = remote.configuredSecrets,
            providers = providers,
            isEditing = true,
            onDismiss = { editingRemote = null },
            onSubmit = { name, type, endpoint, secret ->
                scope.launch {
                    client.updateRemote(remote.id, name, type, endpoint.ifBlank { null }, bearer, secret).fold(
                        onSuccess = {
                            onShowMessage("远端更新成功")
                            editingRemote = null
                            loadRemotes()
                        },
                        onFailure = { onShowMessage("更新失败: ${it.message}") }
                    )
                }
            }
        )
    }

    if (showImportDialog) {
        RemoteImportDialog(
            onDismiss = { showImportDialog = false },
            onShowMessage = onShowMessage,
            onSubmit = { configText ->
                scope.launch {
                    client.importRemoteConfig(configText, bearer).fold(
                        onSuccess = { res ->
                            val count = runCatching { JSONObject(res).optJSONArray("imported")?.length() ?: 0 }.getOrDefault(0)
                            onShowMessage("成功导入 $count 个远端配置")
                            showImportDialog = false
                            loadRemotes()
                        },
                        onFailure = { onShowMessage("导入失败: ${it.message}") }
                    )
                }
            }
        )
    }

    deleteCandidate?.let { (remote, token) ->
        DangerousConfirmDialog(
            show = true,
            title = "${stringResource(R.string.remote_delete_title)}: ${remote.name}",
            message = stringResource(R.string.remote_delete_confirm_msg),
            tokenBadge = token,
            confirmLabel = stringResource(R.string.action_confirm),
            isLoading = isDeletingRemote,
            onConfirm = {
                scope.launch {
                    isDeletingRemote = true
                    client.remoteDelete(remote.id, bearer, token).fold(
                        onSuccess = {
                            isDeletingRemote = false
                            onShowMessage("远端已成功删除")
                            deleteCandidate = null
                            loadRemotes()
                        },
                        onFailure = {
                            isDeletingRemote = false
                            onShowMessage("删除失败: ${it.message}")
                        }
                    )
                }
            },
            onDismiss = {
                if (!isDeletingRemote) {
                    deleteCandidate = null
                }
            }
        )
    }

    exportData?.let { data ->
        RemoteExportDialog(
            data = data,
            onDismiss = { exportData = null },
            onShowMessage = onShowMessage
        )
    }

    mountConflictWarning?.let { (remote, referencedMounts) ->
        AlertDialog(
            onDismissRequest = { mountConflictWarning = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("无法删除远端: ${remote.name}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "检测到该远端当前被以下挂载配置引用。为保证系统文件访问稳定，禁止删除该远端：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    referencedMounts.forEach { m ->
                        val isRunning = m.status == "RUNNING" || m.status == "STARTING"
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("挂载名称: ${m.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text("本地挂载点: ${m.mountPoint}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "运行状态: ${if (isRunning) "⚠️ 挂载运行中" else "已停止"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Text(
                        "提示：请先前往【更多 -> 挂载管理】停止并删除相关挂载配置，然后再尝试删除此远端。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { mountConflictWarning = null }) {
                    Text("我知道了")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFormDialog(
    title: String,
    initialName: String,
    initialType: String,
    initialEndpoint: String,
    initialOptions: Map<String, String> = emptyMap(),
    configuredSecrets: List<String> = emptyList(),
    providers: List<ProviderItem> = emptyList(),
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (name: String, type: String, endpoint: String, secret: JSONObject?) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName)) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameFocusRequester = remember { FocusRequester() }

    var type by remember { mutableStateOf(initialType.ifBlank { "webdav" }) }
    var typeExpanded by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    val popularTypes = listOf("webdav", "s3", "drive", "onedrive", "smb", "sftp", "local", "dropbox")
    val selectedProvider = providers.find { it.name.equals(type, ignoreCase = true) }

    val optionErrors = remember { mutableStateMapOf<String, String>() }
    val optionFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    val optionValues = remember(type, initialOptions) {
        mutableStateMapOf<String, String>().apply {
            if (initialEndpoint.isNotBlank()) {
                put("endpoint", initialEndpoint)
                put("url", initialEndpoint)
            }
            selectedProvider?.options?.forEach { opt ->
                if (!opt.advanced && opt.type != "CommaSepList" && opt.name != "headers") {
                    opt.defaultVal?.takeIf { it.isNotBlank() && it != "[]" && it != "{}" }?.let { def ->
                        put(opt.name, def)
                    }
                }
            }
            initialOptions.forEach { (k, v) ->
                put(k, v)
            }
        }
    }
    val passwordVisibility = remember { mutableStateMapOf<String, Boolean>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isEditing) {
                    Text(
                        text = "提示：留空的密码或 Secret 选项将保留原有的加密值，不会被覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (nameError != null) nameError = null
                    },
                    label = { Text("远端名称 (英文标识符，例如 mydav) *") },
                    isError = nameError != null,
                    supportingText = {
                        if (nameError != null) {
                            Text(nameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("用于挂载和任务引用的唯一英文标识符")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                )

                Text(
                    text = "常用存储类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    popularTypes.forEach { pType ->
                        FilterChip(
                            selected = type.equals(pType, ignoreCase = true),
                            onClick = {
                                type = pType
                                optionErrors.clear()
                            },
                            label = {
                                Text(
                                    text = pType,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {
                            type = it
                            typeExpanded = true
                            optionErrors.clear()
                        },
                        label = { Text("存储类型 (可输入搜索)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    val filtered = if (type.isBlank()) providers else providers.filter {
                        it.name.contains(type, ignoreCase = true) || it.description.contains(type, ignoreCase = true)
                    }
                    if (filtered.isNotEmpty() || providers.isEmpty()) {
                        ExposedDropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false },
                            modifier = Modifier.heightIn(max = 260.dp)
                        ) {
                            val list = if (filtered.isNotEmpty()) filtered else popularTypes.map { ProviderItem(it, "", emptyList()) }
                            list.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(p.name, fontWeight = FontWeight.SemiBold)
                                            if (p.description.isNotBlank()) {
                                                Text(
                                                    p.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        type = p.name
                                        typeExpanded = false
                                        optionErrors.clear()
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                if (selectedProvider != null && selectedProvider.options.isNotEmpty()) {
                    val basicOptions = selectedProvider.options.filter { !it.advanced && it.name != "name" && it.name != "type" }
                    val advancedOptions = selectedProvider.options.filter { it.advanced && it.name != "name" && it.name != "type" }

                    Text(
                        text = "基础配置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    basicOptions.forEach { opt ->
                        RenderOptionField(
                            opt = opt,
                            currentVal = optionValues[opt.name] ?: "",
                            errorMessage = optionErrors[opt.name],
                            focusRequester = optionFocusRequesters.getOrPut(opt.name) { FocusRequester() },
                            isPasswordVisible = passwordVisibility[opt.name] == true,
                            onTogglePassword = { passwordVisibility[opt.name] = !(passwordVisibility[opt.name] ?: false) },
                            isEditing = isEditing,
                            configuredSecrets = configuredSecrets,
                            onValueChange = {
                                optionValues[opt.name] = it
                                optionErrors.remove(opt.name)
                            }
                        )
                    }

                    if (advancedOptions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { showAdvanced = !showAdvanced },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showAdvanced) "收起高级选项 (${advancedOptions.size} 项)" else "展开高级选项 (${advancedOptions.size} 项)")
                        }

                        if (showAdvanced) {
                            advancedOptions.forEach { opt ->
                                RenderOptionField(
                                    opt = opt,
                                    currentVal = optionValues[opt.name] ?: "",
                                    errorMessage = optionErrors[opt.name],
                                    focusRequester = optionFocusRequesters.getOrPut(opt.name) { FocusRequester() },
                                    isPasswordVisible = passwordVisibility[opt.name] == true,
                                    onTogglePassword = { passwordVisibility[opt.name] = !(passwordVisibility[opt.name] ?: false) },
                                    isEditing = isEditing,
                                    configuredSecrets = configuredSecrets,
                                    onValueChange = {
                                        optionValues[opt.name] = it
                                        optionErrors.remove(opt.name)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "通用配置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = optionValues["url"] ?: optionValues["endpoint"] ?: "",
                        onValueChange = {
                            optionValues["url"] = it
                            optionValues["endpoint"] = it
                            optionErrors.remove("url")
                        },
                        label = { Text("服务器地址 / URL (例如 https://dav.example.com) *") },
                        isError = optionErrors["url"] != null,
                        supportingText = {
                            if (optionErrors["url"] != null) {
                                Text(optionErrors["url"]!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("WebDAV 根路径 URL")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(optionFocusRequesters.getOrPut("url") { FocusRequester() })
                    )
                    OutlinedTextField(
                        value = optionValues["user"] ?: optionValues["access_key"] ?: "",
                        onValueChange = {
                            optionValues["user"] = it
                            optionValues["access_key"] = it
                        },
                        label = { Text("用户名 / Access Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val isPassConfigured = isEditing && (configuredSecrets.contains("pass") || configuredSecrets.contains("secret") || configuredSecrets.contains("password"))
                    OutlinedTextField(
                        value = optionValues["pass"] ?: optionValues["secret"] ?: "",
                        onValueChange = {
                            optionValues["pass"] = it
                            optionValues["secret"] = it
                        },
                        label = { Text("密码 / Secret Key (掩码保护)") },
                        placeholder = if (isPassConfigured) {
                            { Text("已配置密码 (留空保持不变，输入可修改)") }
                        } else null,
                        supportingText = if (isPassConfigured) {
                            { Text("已配置密码凭据。若不修改请留空；输入新密码将覆盖旧密码。", color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = optionValues["vendor"] ?: "other",
                        onValueChange = { optionValues["vendor"] = it },
                        label = { Text("提供商 (WebDAV 默认填写 other)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    nameError = null
                    optionErrors.clear()

                    if (name.text.isBlank()) {
                        nameError = "远端名称不能为空"
                        nameFocusRequester.requestFocus()
                        return@Button
                    }
                    if (!name.text.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        nameError = "远端名称仅允许字母、数字、下划线及连字符"
                        nameFocusRequester.requestFocus()
                        return@Button
                    }

                    if (selectedProvider != null && selectedProvider.options.isNotEmpty()) {
                        val basicOptions = selectedProvider.options.filter { !it.advanced && it.name != "name" && it.name != "type" }
                        val emptyRequired = basicOptions.filter { opt ->
                            opt.required && optionValues[opt.name].isNullOrBlank() && !(isEditing && configuredSecrets.contains(opt.name))
                        }
                        if (emptyRequired.isNotEmpty()) {
                            emptyRequired.forEach { opt ->
                                optionErrors[opt.name] = "${opt.name} 为必填项"
                            }
                            val first = emptyRequired.first()
                            optionFocusRequesters[first.name]?.requestFocus()
                            return@Button
                        }
                    } else {
                        val urlVal = optionValues["url"] ?: optionValues["endpoint"] ?: ""
                        if (type.equals("webdav", ignoreCase = true) && urlVal.isBlank()) {
                            optionErrors["url"] = "服务器地址 / URL 为必填项"
                            optionFocusRequesters["url"]?.requestFocus()
                            return@Button
                        }
                    }

                    val secretObj = JSONObject()
                    optionValues.forEach { (k, v) ->
                        val trimmed = v.trim()
                        if (trimmed.isNotBlank() && trimmed != "[]" && trimmed != "{}") {
                            secretObj.put(k, trimmed)
                        }
                    }
                    val endpointVal = optionValues["url"]?.ifBlank { null }
                        ?: optionValues["endpoint"]?.ifBlank { null }
                        ?: ""
                    val secret = if (secretObj.length() > 0) secretObj else null
                    onSubmit(
                        name.text.trim(),
                        type.trim(),
                        endpointVal,
                        secret
                    )
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderOptionField(
    opt: ProviderOption,
    currentVal: String,
    errorMessage: String? = null,
    focusRequester: FocusRequester? = null,
    isPasswordVisible: Boolean,
    onTogglePassword: () -> Unit,
    isEditing: Boolean = false,
    configuredSecrets: List<String> = emptyList(),
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val fieldModifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)

        if (opt.examples.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentVal,
                    onValueChange = onValueChange,
                    label = { Text("${opt.name}${if (opt.required) " *" else ""}") },
                    isError = errorMessage != null,
                    supportingText = {
                        if (errorMessage != null) {
                            Text(errorMessage, color = MaterialTheme.colorScheme.error)
                        } else {
                            val helpText = opt.help.take(80) + if (opt.help.length > 80) "…" else ""
                            if (helpText.isNotBlank()) Text(helpText)
                        }
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = fieldModifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 240.dp)
                ) {
                    opt.examples.forEach { ex ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(ex.value, fontWeight = FontWeight.SemiBold)
                                    if (ex.help.isNotBlank()) {
                                        Text(
                                            ex.help,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onValueChange(ex.value)
                                expanded = false
                            }
                        )
                    }
                }
            }
        } else if (opt.isPassword) {
            val isConfiguredSecret = isEditing && configuredSecrets.contains(opt.name)
            OutlinedTextField(
                value = currentVal,
                onValueChange = onValueChange,
                label = { Text("${opt.name}${if (opt.required && !isConfiguredSecret) " *" else ""}") },
                placeholder = if (isConfiguredSecret) {
                    { Text("已配置密码 (留空保持不变，输入可修改)") }
                } else null,
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    } else if (isConfiguredSecret) {
                        Text("已配置密码凭据。若不修改请留空；输入新密码将覆盖旧密码。", color = MaterialTheme.colorScheme.primary)
                    } else {
                        val helpText = opt.help.take(80) + if (opt.help.length > 80) "…" else ""
                        if (helpText.isNotBlank()) Text(helpText)
                    }
                },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(
                            if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isPasswordVisible) "隐藏密码" else "显示明文"
                        )
                    }
                },
                modifier = fieldModifier
            )
        } else if (opt.type == "bool") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(opt.name, fontWeight = FontWeight.Medium)
                    if (opt.help.isNotBlank()) {
                        Text(
                            opt.help.take(80) + if (opt.help.length > 80) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = currentVal == "true",
                    onCheckedChange = { onValueChange(if (it) "true" else "false") }
                )
            }
        } else {
            OutlinedTextField(
                value = currentVal,
                onValueChange = onValueChange,
                label = { Text("${opt.name}${if (opt.required) " *" else ""}") },
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    } else {
                        val helpText = opt.help.take(80) + if (opt.help.length > 80) "…" else ""
                        if (helpText.isNotBlank()) Text(helpText)
                    }
                },
                singleLine = opt.type != "string_array",
                modifier = fieldModifier
            )
        }
    }
}

@Composable
fun RemoteImportDialog(
    onDismiss: () -> Unit,
    onSubmit: (configText: String) -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var configText by remember {
        mutableStateOf(
            """[my-webdav]
type = webdav
url = https://dav.example.com
vendor = other
user = myusername
pass = mypassword
"""
        )
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = QrCodeUtils.decodeQrFromUri(context, uri)
            if (!decoded.isNullOrBlank()) {
                configText = decoded
                onShowMessage("已成功解析二维码配置！")
            } else {
                onShowMessage("未能从图片中解析出二维码")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 rclone.conf 配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "在此粘贴原生 rclone.conf INI 格式配置，或通过相册图片识别配置二维码。系统将自动解析区块并加密敏感凭据入库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("识别二维码")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).text?.toString()
                                if (!text.isNullOrBlank()) {
                                    configText = text
                                    onShowMessage("已从剪贴板粘贴")
                                } else {
                                    onShowMessage("剪贴板内容为空")
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("粘贴剪贴板")
                    }
                }

                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("[remote-name]\ntype = ...") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (configText.isNotBlank()) {
                        onSubmit(configText.trim())
                    }
                }
            ) {
                Text("导入并保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteExportDialog(
    data: RemoteExportData,
    onDismiss: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var isFullMode by remember { mutableStateOf(true) } // true: 完整模式(含加密密文); false: 脱敏模式
    var selectedFormat by remember { mutableStateOf(0) } // 0: rclone.conf (INI), 1: JSON, 2: 二维码

    val currentContent = remember(isFullMode, selectedFormat, data) {
        when (selectedFormat) {
            0 -> if (isFullMode) data.ini else data.redactedIni
            1 -> if (isFullMode) data.jsonConfig else data.redactedJsonConfig
            else -> if (isFullMode) data.ini else data.redactedIni
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(currentContent.toByteArray())
                }
                onShowMessage("已成功保存至文件")
            } catch (e: Exception) {
                onShowMessage("保存失败: ${e.message}")
            }
        }
    }

    val qrBitmap = remember(currentContent, selectedFormat) {
        if (selectedFormat == 2) {
            QrCodeUtils.generateQrBitmap(currentContent, 512)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("导出与分享: ${data.name}", fontWeight = FontWeight.Bold)
                Text(
                    text = "支持 rclone.conf 标准片段、JSON 配置及二维码，可便捷跨机互传或分享备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 模式切换 Chip (完整 vs 脱敏)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isFullMode,
                        onClick = { isFullMode = true },
                        label = { Text("完整备份 (含密文)") }
                    )
                    FilterChip(
                        selected = !isFullMode,
                        onClick = { isFullMode = false },
                        label = { Text("安全脱敏 (公开)") }
                    )
                }

                // 格式 Tab
                val formats = listOf("rclone.conf", "JSON", "二维码")
                ScrollableTabRow(
                    selectedTabIndex = selectedFormat,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    formats.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedFormat == index,
                            onClick = { selectedFormat = index },
                            text = { Text(title) }
                        )
                    }
                }

                // 内容展示区
                if (selectedFormat == 2) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "配置二维码",
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(4.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "使用另一设备扫描此二维码即可一键导入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "配置内容过长，无法生成标准二维码，请使用复制或分享文本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = currentContent,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 复制按钮
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("rclone-config", currentContent))
                        onShowMessage("已复制到剪贴板")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制")
                }

                // 系统原生分享按钮
                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentContent)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "分享远端配置: ${data.name}"))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("分享")
                }

                // 保存文件按钮
                Button(
                    onClick = {
                        val ext = if (selectedFormat == 1) "json" else "conf"
                        saveFileLauncher.launch("${data.name}.$ext")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

