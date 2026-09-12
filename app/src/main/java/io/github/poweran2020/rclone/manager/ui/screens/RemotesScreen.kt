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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
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
    val context = LocalContext.current
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

    // 1. Hoisted photo picker launcher for QR import to prevent Dialog window crash
    var pendingImportQrCode by remember { mutableStateOf<String?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = runCatching { QrCodeUtils.decodeQrFromUri(context, uri) }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                pendingImportQrCode = decoded
                onShowMessage(context.getString(R.string.remotes_msg_qr_parsed))
            } else {
                onShowMessage(context.getString(R.string.remotes_msg_qr_parse_failed))
            }
        }
    }

    // 2. Hoisted document creation launcher for export to prevent Dialog window crash
    var exportContentToSave by remember { mutableStateOf<String?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null && exportContentToSave != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(exportContentToSave!!.toByteArray())
                }
                onShowMessage(context.getString(R.string.remotes_msg_import_success))
            } catch (e: Exception) {
                onShowMessage(context.getString(R.string.remotes_msg_import_failed) + ": ${e.message}")
            }
        }
    }

    val loadRemotes = {
        scope.launch {
            isLoading = true
            client.remotes(bearer).fold(
                onSuccess = { remotes = parseRemotes(it) },
                onFailure = { onShowMessage(context.getString(R.string.remotes_msg_list_failed, it.message ?: "")) }
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
                SectionTitle(
                    text = stringResource(R.string.remotes_count_header, remotes.size),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { loadRemotes() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.remotes_btn_import), maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.remotes_btn_add), maxLines = 1, softWrap = false)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusBadge(status = if (remote.enabled) "ENABLED" else "DISABLED")
                            Switch(
                                checked = remote.enabled,
                                onCheckedChange = {
                                    scope.launch {
                                        val action = if (remote.enabled) "disable" else "enable"
                                        client.remoteAction(remote.id, action, bearer).fold(
                                            onSuccess = {
                                                val actionStr = if (remote.enabled) context.getString(R.string.remotes_status_disabled_action) else context.getString(R.string.remotes_status_enabled_action)
                                                onShowMessage(context.getString(R.string.remotes_msg_status_changed, actionStr))
                                                loadRemotes()
                                            },
                                            onFailure = { onShowMessage(context.getString(R.string.remotes_msg_op_failed, it.message ?: "")) }
                                        )
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        onShowMessage(context.getString(R.string.remotes_msg_testing, remote.name))
                                        client.testRemote(remote.id, bearer).fold(
                                            onSuccess = { res ->
                                                val json = runCatching { JSONObject(res) }.getOrNull()
                                                if (json?.optBoolean("ok") == true) {
                                                    onShowMessage(context.getString(R.string.remotes_msg_test_success))
                                                } else {
                                                    val errMsg = json?.optString("error")?.ifBlank { null } ?: context.getString(R.string.remotes_msg_test_failed_fallback)
                                                    onShowMessage(context.getString(R.string.remotes_msg_test_failed, errMsg))
                                                }
                                            },
                                            onFailure = { onShowMessage(context.getString(R.string.remotes_msg_test_failed, it.message ?: "")) }
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.remotes_btn_test_short))
                            }

                            OutlinedButton(
                                onClick = { onNavigateToFileBrowser(remote.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.remotes_btn_browse_short))
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { editingRemote = remote }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                            }

                            IconButton(
                                onClick = {
                                    scope.launch {
                                        client.exportRemote(remote.id, bearer).fold(
                                            onSuccess = { exportData = parseRemoteExport(it) },
                                            onFailure = { onShowMessage(context.getString(R.string.remotes_msg_export_failed, it.message ?: "")) }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.remotes_cd_share))
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
                                                    onShowMessage(context.getString(R.string.remotes_msg_deleted))
                                                    loadRemotes()
                                                }
                                            },
                                            onFailure = { onShowMessage(context.getString(R.string.remotes_msg_delete_failed, it.message ?: "")) }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
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
            client = client,
            bearer = bearer,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, type, endpoint, secret, onSuccess, onError ->
                scope.launch {
                    client.createRemote(name, type, endpoint.ifBlank { null }, secret, bearer).fold(
                        onSuccess = {
                            onShowMessage(context.getString(R.string.remotes_msg_created))
                            showCreateDialog = false
                            onSuccess()
                            loadRemotes()
                        },
                        onFailure = { onError(context.getString(R.string.remotes_msg_create_failed, it.message ?: "")) }
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
            client = client,
            bearer = bearer,
            onDismiss = { editingRemote = null },
            onSubmit = { name, type, endpoint, secret, onSuccess, onError ->
                scope.launch {
                    client.updateRemote(remote.id, name, type, endpoint.ifBlank { null }, bearer, secret).fold(
                        onSuccess = {
                            onShowMessage(context.getString(R.string.remotes_msg_updated))
                            editingRemote = null
                            onSuccess()
                            loadRemotes()
                        },
                        onFailure = { onError(context.getString(R.string.remotes_msg_update_failed, it.message ?: "")) }
                    )
                }
            }
        )
    }

    if (showImportDialog) {
        RemoteImportDialog(
            pendingQrCode = pendingImportQrCode,
            onClearPendingQrCode = { pendingImportQrCode = null },
            onPickQrImage = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { showImportDialog = false },
            onShowMessage = onShowMessage,
            onSubmit = { configText, onSuccess, onError ->
                scope.launch {
                    client.importRemoteConfig(configText, bearer).fold(
                        onSuccess = { res ->
                            val count = runCatching { JSONObject(res).optJSONArray("imported")?.length() ?: 0 }.getOrDefault(0)
                            onShowMessage(context.getString(R.string.remotes_msg_import_count_success, count))
                            showImportDialog = false
                            onSuccess()
                            loadRemotes()
                        },
                        onFailure = { onError(context.getString(R.string.remotes_msg_import_failed) + ": ${it.message}") }
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
                            onShowMessage(context.getString(R.string.remotes_msg_deleted))
                            deleteCandidate = null
                            loadRemotes()
                        },
                        onFailure = {
                            isDeletingRemote = false
                            onShowMessage(context.getString(R.string.remotes_msg_delete_failed, it.message ?: ""))
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
            onSaveToFile = { filename, content ->
                exportContentToSave = content
                saveFileLauncher.launch(filename)
            },
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
                Text(stringResource(R.string.remotes_cannot_delete_title, remote.name), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.remotes_cannot_delete_desc),
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
                                Text(stringResource(R.string.remotes_cannot_delete_mount_name, m.name), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.remotes_cannot_delete_mount_point, m.mountPoint), style = MaterialTheme.typography.bodySmall)
                                val stStr = if (isRunning) stringResource(R.string.remotes_mount_status_running) else stringResource(R.string.remotes_mount_status_stopped)
                                Text(
                                    stringResource(R.string.remotes_mount_status_prefix, stStr),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.remotes_cannot_delete_tip),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { mountConflictWarning = null }) {
                    Text(stringResource(R.string.remotes_cannot_delete_btn))
                }
            }
        )
    }
}

enum class RemoteTestState {
    UNTESTED,
    TESTING,
    SUCCESS,
    FAILED
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
    client: GatewayClient,
    bearer: String,
    onDismiss: () -> Unit,
    onSubmit: (name: String, type: String, endpoint: String, secret: JSONObject?, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    var testState by remember { mutableStateOf(RemoteTestState.UNTESTED) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var dialogSuccess by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun invalidateTestState() {
        if (testState != RemoteTestState.UNTESTED) {
            testState = RemoteTestState.UNTESTED
            dialogSuccess = null
        }
    }

    fun buildSecretObject(): JSONObject? {
        val secretObj = JSONObject()
        optionValues.forEach { (k, v) ->
            val trimmed = v.trim()
            if (trimmed.isNotBlank() && trimmed != "[]" && trimmed != "{}") {
                secretObj.put(k, trimmed)
            }
        }
        return if (secretObj.length() > 0) secretObj else null
    }

    fun getEndpointValue(): String {
        return optionValues["url"]?.ifBlank { null }
            ?: optionValues["endpoint"]?.ifBlank { null }
            ?: ""
    }

    fun validateInputs(): Boolean {
        nameError = null
        optionErrors.clear()
        dialogError = null

        if (name.text.isBlank()) {
            nameError = context.getString(R.string.remotes_err_name_empty)
            nameFocusRequester.requestFocus()
            return false
        }
        if (!name.text.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            nameError = context.getString(R.string.remotes_err_name_invalid)
            nameFocusRequester.requestFocus()
            return false
        }

        if (selectedProvider != null && selectedProvider.options.isNotEmpty()) {
            val basicOptions = selectedProvider.options.filter { !it.advanced && it.name != "name" && it.name != "type" }
            val emptyRequired = basicOptions.filter { opt ->
                opt.required && optionValues[opt.name].isNullOrBlank() && !(isEditing && configuredSecrets.contains(opt.name))
            }
            if (emptyRequired.isNotEmpty()) {
                emptyRequired.forEach { opt ->
                    optionErrors[opt.name] = context.getString(R.string.remotes_err_field_required, opt.name)
                }
                val first = emptyRequired.first()
                optionFocusRequesters[first.name]?.requestFocus()
                return false
            }
        } else {
            val urlVal = optionValues["url"] ?: optionValues["endpoint"] ?: ""
            if (type.equals("webdav", ignoreCase = true) && urlVal.isBlank()) {
                optionErrors["url"] = context.getString(R.string.remotes_err_url_required)
                optionFocusRequesters["url"]?.requestFocus()
                return false
            }
        }
        return true
    }

    fun runTest(onTestResult: (Boolean) -> Unit = {}) {
        if (!validateInputs()) {
            onTestResult(false)
            return
        }

        testState = RemoteTestState.TESTING
        dialogError = null
        dialogSuccess = null

        val endpointVal = getEndpointValue()
        val secret = buildSecretObject()

        scope.launch {
            client.testRemoteConfig(name.text.trim(), type.trim(), endpointVal.ifBlank { null }, secret, bearer).fold(
                onSuccess = { res ->
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    val ok = json?.optBoolean("ok", false) ?: false
                    if (ok) {
                        testState = RemoteTestState.SUCCESS
                        dialogSuccess = context.getString(R.string.remotes_test_success_desc)
                        dialogError = null
                        onTestResult(true)
                    } else {
                        testState = RemoteTestState.FAILED
                        val errMsg = json?.optString("error")?.ifBlank { null } ?: context.getString(R.string.remotes_test_failed_fallback)
                        dialogError = context.getString(R.string.remotes_test_failed_format, errMsg)
                        dialogSuccess = null
                        onTestResult(false)
                    }
                },
                onFailure = { err ->
                    testState = RemoteTestState.FAILED
                    dialogError = context.getString(R.string.remotes_test_error_format, err.message ?: "")
                    dialogSuccess = null
                    onTestResult(false)
                }
            )
        }
    }

    fun handleSave() {
        if (!validateInputs()) return

        if (!isEditing) {
            when (testState) {
                RemoteTestState.SUCCESS -> {
                    // 已通过测试，继续保存
                }
                RemoteTestState.FAILED -> {
                    dialogError = context.getString(R.string.remotes_err_test_must_pass)
                    return
                }
                RemoteTestState.TESTING -> {
                    return
                }
                RemoteTestState.UNTESTED -> {
                    runTest { passed ->
                        if (passed) {
                            val endpointVal = getEndpointValue()
                            val secret = buildSecretObject()
                            isSubmitting = true
                            onSubmit(
                                name.text.trim(),
                                type.trim(),
                                endpointVal,
                                secret,
                                { isSubmitting = false },
                                { err -> dialogError = err; isSubmitting = false }
                            )
                        }
                    }
                    return
                }
            }
        }

        val endpointVal = getEndpointValue()
        val secret = buildSecretObject()
        isSubmitting = true
        onSubmit(
            name.text.trim(),
            type.trim(),
            endpointVal,
            secret,
            { isSubmitting = false },
            { err -> dialogError = err; isSubmitting = false }
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (testState != RemoteTestState.TESTING && !isSubmitting) {
                onDismiss()
            }
        },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dialogError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = dialogError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { dialogError = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (dialogSuccess != null) {
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = dialogSuccess!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1B5E20),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isEditing) {
                        Text(
                            text = stringResource(R.string.remotes_form_secret_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (nameError != null) nameError = null
                        invalidateTestState()
                    },
                    label = { Text(stringResource(R.string.remotes_form_name_label)) },
                    isError = nameError != null,
                    supportingText = {
                        if (nameError != null) {
                            Text(nameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.remotes_form_name_hint))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                )

                Text(
                    text = stringResource(R.string.remotes_form_common_types),
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
                                invalidateTestState()
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
                            invalidateTestState()
                        },
                        label = { Text(stringResource(R.string.remotes_form_type_search_label)) },
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
                                        invalidateTestState()
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
                        text = stringResource(R.string.remotes_sec_basic_config),
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
                                invalidateTestState()
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
                            Text(if (showAdvanced) stringResource(R.string.remotes_btn_collapse_advanced, advancedOptions.size) else stringResource(R.string.remotes_btn_expand_advanced, advancedOptions.size))
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
                                        invalidateTestState()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.remotes_sec_general_config),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = optionValues["url"] ?: optionValues["endpoint"] ?: "",
                        onValueChange = {
                            optionValues["url"] = it
                            optionValues["endpoint"] = it
                            optionErrors.remove("url")
                            invalidateTestState()
                        },
                        label = { Text(stringResource(R.string.remotes_field_url)) },
                        isError = optionErrors["url"] != null,
                        supportingText = {
                            if (optionErrors["url"] != null) {
                                Text(optionErrors["url"]!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(stringResource(R.string.remotes_field_url_hint))
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
                            invalidateTestState()
                        },
                        label = { Text(stringResource(R.string.remotes_field_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val isPassConfigured = isEditing && (configuredSecrets.contains("pass") || configuredSecrets.contains("secret") || configuredSecrets.contains("password"))
                    OutlinedTextField(
                        value = optionValues["pass"] ?: optionValues["secret"] ?: "",
                        onValueChange = {
                            optionValues["pass"] = it
                            optionValues["secret"] = it
                            invalidateTestState()
                        },
                        label = { Text(stringResource(R.string.remotes_field_pass)) },
                        placeholder = if (isPassConfigured) {
                            { Text(stringResource(R.string.remotes_field_pass_placeholder)) }
                        } else null,
                        supportingText = if (isPassConfigured) {
                            { Text(stringResource(R.string.remotes_field_pass_configured_hint), color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = optionValues["vendor"] ?: "other",
                        onValueChange = {
                            optionValues["vendor"] = it
                            invalidateTestState()
                        },
                        label = { Text(stringResource(R.string.remotes_field_vendor)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { runTest() },
                    enabled = testState != RemoteTestState.TESTING && !isSubmitting
                ) {
                    if (testState == RemoteTestState.TESTING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.remotes_btn_testing))
                    } else {
                        Text(if (testState == RemoteTestState.SUCCESS) stringResource(R.string.remotes_btn_retest) else stringResource(R.string.remotes_btn_test))
                    }
                }
                Button(
                    onClick = { handleSave() },
                    enabled = testState != RemoteTestState.TESTING && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (!isEditing && testState != RemoteTestState.SUCCESS) stringResource(R.string.remotes_btn_test_and_save) else stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = testState != RemoteTestState.TESTING && !isSubmitting
            ) {
                Text(stringResource(R.string.action_cancel))
            }
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
                    { Text(stringResource(R.string.remotes_field_pass_placeholder)) }
                } else null,
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    } else if (isConfiguredSecret) {
                        Text(stringResource(R.string.remotes_field_pass_configured_hint), color = MaterialTheme.colorScheme.primary)
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
                            contentDescription = if (isPasswordVisible) stringResource(R.string.remotes_hide_password) else stringResource(R.string.remotes_show_password)
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
    pendingQrCode: String? = null,
    onClearPendingQrCode: () -> Unit = {},
    onPickQrImage: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (configText: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
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
    var dialogError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(pendingQrCode) {
        if (!pendingQrCode.isNullOrBlank()) {
            configText = pendingQrCode
            dialogError = null
            onClearPendingQrCode()
            onShowMessage(context.getString(R.string.remotes_msg_qr_parsed))
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(stringResource(R.string.remotes_import_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dialogError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = dialogError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { dialogError = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.remotes_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickQrImage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        enabled = !isSubmitting
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.remotes_import_btn_scan_qr))
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).text?.toString()
                                if (!text.isNullOrBlank()) {
                                    configText = text
                                    dialogError = null
                                    onShowMessage(context.getString(R.string.remotes_import_clipboard_pasted))
                                } else {
                                    dialogError = context.getString(R.string.remotes_import_clipboard_empty)
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        enabled = !isSubmitting
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.remotes_import_btn_paste))
                    }
                }

                OutlinedTextField(
                    value = configText,
                    onValueChange = {
                        configText = it
                        if (dialogError != null) dialogError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("[remote-name]\ntype = ...") },
                    enabled = !isSubmitting
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (configText.isBlank()) {
                        dialogError = context.getString(R.string.remotes_import_err_empty)
                        return@Button
                    }
                    isSubmitting = true
                    onSubmit(
                        configText.trim(),
                        { isSubmitting = false },
                        { err ->
                            dialogError = err
                            isSubmitting = false
                        }
                    )
                },
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.remotes_import_btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteExportDialog(
    data: RemoteExportData,
    onSaveToFile: (fileName: String, content: String) -> Unit,
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
                Text(stringResource(R.string.remotes_export_title, data.name), fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(R.string.remotes_export_desc),
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
                        label = { Text(stringResource(R.string.remotes_export_mode_full)) }
                    )
                    FilterChip(
                        selected = !isFullMode,
                        onClick = { isFullMode = false },
                        label = { Text(stringResource(R.string.remotes_export_mode_masked)) }
                    )
                }

                // 格式 Tab
                val formats = listOf("rclone.conf", "JSON", stringResource(R.string.remotes_export_format_qr))
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
                                contentDescription = stringResource(R.string.remotes_export_qr_alt),
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(4.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.remotes_export_qr_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.remotes_export_qr_too_long),
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
                        onShowMessage(context.getString(R.string.remotes_export_copied))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_copy))
                }

                // 系统原生分享按钮
                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, currentContent)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.remotes_export_share_title, data.name)))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.remotes_export_btn_share))
                }

                // 保存文件按钮
                Button(
                    onClick = {
                        val ext = if (selectedFormat == 1) "json" else "conf"
                        onSaveToFile("${data.name}.$ext", currentContent)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

