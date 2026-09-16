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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.MountProfileItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.parseMounts
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.PathPickerDialog
import io.github.poweran2020.rclone.manager.ui.component.PathPickerMode
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountsScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var mounts by remember { mutableStateOf<List<MountProfileItem>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var mountToEdit by remember { mutableStateOf<MountProfileItem?>(null) }
    var mountToDelete by remember { mutableStateOf<MountProfileItem?>(null) }
    var isDeleting by remember { mutableStateOf(false) }

    val loadMounts = {
        scope.launch {
            isLoading = true
            client.mounts(bearer).fold(
                onSuccess = { mounts = parseMounts(it) },
                onFailure = { onShowMessage(context.getString(R.string.mount_msg_list_failed, it.message ?: "")) }
            )
            client.remotes(bearer).onSuccess { remotes = parseRemotes(it) }
            isLoading = false
        }
    }

    LaunchedEffect(bearer) {
        loadMounts()
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
                    text = "${stringResource(R.string.mounts_title)} (${mounts.size})",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { loadMounts() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), modifier = Modifier.size(20.dp))
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.mounts_btn_create), maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = stringResource(R.string.status_loading)) }
        } else if (mounts.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.mounts_empty_title),
                    message = stringResource(R.string.mounts_empty_desc)
                )
            }
        } else {
            items(mounts, key = { it.id }) { mount ->
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(mount.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (mount.isolated || mount.targetPackage != null) {
                                    if (!mount.readOnly) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.mount_badge_isolated_writable),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else {
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.mount_tag_app_isolated),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.mount_point_label, mount.mountPoint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        StatusBadge(status = mount.status)
                    }

                    val remoteDisplayName = mount.remoteName
                        ?: remotes.find { it.id == mount.remoteId }?.name
                        ?: mount.remoteId
                    InfoRow(label = stringResource(R.string.mount_label_remote), value = "${remoteDisplayName}:${mount.remotePath}")
                    if (mount.isolated || mount.targetPackage != null) {
                        InfoRow(label = stringResource(R.string.mount_label_target), value = mount.targetPackage ?: stringResource(R.string.mount_val_private_sandbox))
                    } else if (mount.mountPoint.startsWith("/mnt/rclone-")) {
                        InfoRow(label = stringResource(R.string.mount_label_bind_path), value = "/data/media/0/${mount.name}")
                    } else {
                        InfoRow(label = stringResource(R.string.mount_label_type), value = stringResource(R.string.mount_val_direct, mount.mountPoint))
                    }
                    InfoRow(label = stringResource(R.string.mount_label_cache), value = "${mount.cacheMode} · ${mount.cacheMaxSize} · ${mount.cacheMaxAge}")
                    mount.pid?.let { InfoRow(label = "Worker PID", value = it.toString()) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.mount_label_boot_restore), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = mount.enabled,
                            onCheckedChange = { enable ->
                                scope.launch {
                                    val action = if (enable) "enable" else "disable"
                                    client.mountAction(mount.id, action, bearer).fold(
                                        onSuccess = {
                                            val msg = if (enable) context.getString(R.string.mount_msg_boot_enabled) else context.getString(R.string.mount_msg_boot_disabled)
                                            onShowMessage(msg)
                                            loadMounts()
                                        },
                                        onFailure = { onShowMessage("${context.getString(R.string.status_failed)}: ${it.message}") }
                                    )
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isRunning = mount.status.uppercase() == "RUNNING"
                        if (!isRunning) {
                            Button(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                onClick = {
                                    scope.launch {
                                        client.mountAction(mount.id, "start", bearer).fold(
                                            onSuccess = { onShowMessage(context.getString(R.string.mount_msg_start_req)); loadMounts() },
                                            onFailure = { onShowMessage("${context.getString(R.string.status_failed)}: ${it.message}") }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.mount_btn_start), maxLines = 1, softWrap = false)
                            }
                        } else {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                onClick = {
                                    scope.launch {
                                        client.mountAction(mount.id, "stop", bearer).fold(
                                            onSuccess = { onShowMessage(context.getString(R.string.mount_msg_stop_req)); loadMounts() },
                                            onFailure = { onShowMessage("${context.getString(R.string.status_failed)}: ${it.message}") }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.mount_btn_stop), maxLines = 1, softWrap = false)
                            }
                        }

                        OutlinedButton(
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            onClick = {
                                if (isRunning || mount.status.uppercase() == "STARTING") {
                                    onShowMessage(context.getString(R.string.mount_msg_running_cant_edit))
                                } else {
                                    mountToEdit = mount
                                }
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_edit), maxLines = 1, softWrap = false)
                        }

                        IconButton(
                            onClick = { mountToDelete = mount }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        MountEditDialog(
            title = stringResource(R.string.mount_edit_title_create),
            initialMount = null,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, remoteId, remotePath, mountPoint, readOnly, mode, size, age, targetPackage, isolated ->
                scope.launch {
                    client.createMount(name, remoteId, mountPoint, bearer, remotePath, null, readOnly, mode, size, age, targetPackage, isolated).fold(
                        onSuccess = {
                            onShowMessage(context.getString(R.string.mount_msg_created))
                            showCreateDialog = false
                            loadMounts()
                        },
                        onFailure = { onShowMessage(context.getString(R.string.mount_msg_create_failed) + ": ${it.message}") }
                    )
                }
            }
        )
    }

    mountToEdit?.let { editTarget ->
        MountEditDialog(
            title = "${stringResource(R.string.mount_edit_title_edit)}: ${editTarget.name}",
            initialMount = editTarget,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { mountToEdit = null },
            onSubmit = { name, remoteId, remotePath, mountPoint, readOnly, mode, size, age, targetPackage, isolated ->
                scope.launch {
                    client.updateMount(
                        id = editTarget.id,
                        name = name,
                        remoteId = remoteId,
                        mountPoint = mountPoint,
                        token = bearer,
                        remotePath = remotePath,
                        cacheDir = editTarget.cacheDir,
                        readOnly = readOnly,
                        cacheMode = mode,
                        cacheMaxSize = size,
                        cacheMaxAge = age,
                        targetPackage = targetPackage,
                        isolated = isolated
                    ).fold(
                        onSuccess = {
                            onShowMessage(context.getString(R.string.mount_msg_updated))
                            mountToEdit = null
                            loadMounts()
                        },
                        onFailure = { onShowMessage(context.getString(R.string.mount_msg_update_failed) + ": ${it.message}") }
                    )
                }
            }
        )
    }

    mountToDelete?.let { delTarget ->
        DangerousConfirmDialog(
            show = true,
            title = "${stringResource(R.string.mount_delete_title)}: ${delTarget.name}",
            message = stringResource(R.string.mount_delete_message),
            confirmLabel = stringResource(R.string.action_confirm),
            isLoading = isDeleting,
            onConfirm = {
                scope.launch {
                    isDeleting = true
                    client.deleteMount(delTarget.id, bearer).fold(
                        onSuccess = {
                            isDeleting = false
                            onShowMessage(context.getString(R.string.mount_msg_deleted))
                            mountToDelete = null
                            loadMounts()
                        },
                        onFailure = {
                            isDeleting = false
                            onShowMessage(context.getString(R.string.mount_msg_delete_failed) + ": ${it.message}")
                        }
                    )
                }
            },
            onDismiss = {
                if (!isDeleting) mountToDelete = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountEditDialog(
    title: String,
    initialMount: MountProfileItem?,
    remotes: List<RemoteItem>,
    client: GatewayClient,
    bearer: String,
    onDismiss: () -> Unit,
    onSubmit: (name: String, remoteId: String, remotePath: String, mountPoint: String, readOnly: Boolean, mode: String, size: String, age: String, targetPackage: String?, isolated: Boolean) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(TextFieldValue(initialMount?.name ?: "")) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var selectedRemoteId by remember { mutableStateOf(initialMount?.remoteId ?: (remotes.firstOrNull()?.id ?: "")) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remotePath by remember { mutableStateOf(TextFieldValue(initialMount?.remotePath ?: "/")) }
    var mountPoint by remember { mutableStateOf(TextFieldValue(initialMount?.mountPoint ?: "")) }
    var mountPointError by remember { mutableStateOf<String?>(null) }
    var isCustomMountPoint by remember { mutableStateOf(initialMount != null) }
    var isIsolated by remember { mutableStateOf(initialMount?.isolated ?: (initialMount?.targetPackage != null)) }
    var targetPackage by remember { mutableStateOf(TextFieldValue(initialMount?.targetPackage ?: "")) }
    var packageError by remember { mutableStateOf<String?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    var cacheMode by remember { mutableStateOf(initialMount?.cacheMode ?: "full") }
    var cacheMaxSize by remember { mutableStateOf(TextFieldValue(initialMount?.cacheMaxSize ?: "32G")) }
    var cacheMaxAge by remember { mutableStateOf(TextFieldValue(initialMount?.cacheMaxAge ?: "36h")) }
    var readOnly by remember { mutableStateOf(initialMount?.readOnly ?: (initialMount == null || isIsolated)) }
    var showReadOnlyWarningDialog by remember { mutableStateOf(false) }
    var formErrorMsg by remember { mutableStateOf<String?>(null) }

    var remoteDropdownExpanded by remember { mutableStateOf(false) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    val cacheModes = listOf("full", "writes", "minimal", "off")

    var showRemotePathPicker by remember { mutableStateOf(false) }
    var showLocalMountPicker by remember { mutableStateOf(false) }

    LaunchedEffect(remotes) {
        if (selectedRemoteId.isBlank() && remotes.isNotEmpty()) {
            selectedRemoteId = remotes.first().id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (formErrorMsg != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = formErrorMsg!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { formErrorMsg = null },
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

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                        formErrorMsg = null
                        if (!isCustomMountPoint && initialMount == null) {
                            val trimmed = it.text.trim()
                            if (isIsolated) {
                                val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                                mountPoint = TextFieldValue(if (trimmed.isNotBlank()) "/storage/emulated/0/Android/data/$pkg/files/rclone/$trimmed" else "")
                            } else {
                                mountPoint = TextFieldValue(if (trimmed.isNotBlank()) "/mnt/rclone-$trimmed" else "")
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.mount_field_name) + " *") },
                    isError = nameError != null,
                    supportingText = {
                        if (nameError != null) {
                            Text(nameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.mount_name_hint))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Visibility & Isolation Mode Selector
                Text(stringResource(R.string.mount_mode_label), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isIsolated,
                        onClick = {
                            isIsolated = false
                            if (mountPoint.text.startsWith("/data/data/") || mountPoint.text.startsWith("/data/user/0/") || mountPoint.text.contains("/Android/data/")) {
                                val trimmed = name.text.trim().ifBlank { "mount" }
                                mountPoint = TextFieldValue("/mnt/rclone-$trimmed")
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.mount_mode_global),
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isIsolated,
                        onClick = {
                            isIsolated = true
                            readOnly = true // 专属挂载默认强制只读，防止卸载连带删除
                            val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                            val trimmed = name.text.trim().ifBlank { "mount" }
                            if (mountPoint.text.isBlank() || mountPoint.text.startsWith("/mnt/rclone-") || mountPoint.text.startsWith("/sdcard/") || mountPoint.text.startsWith("/storage/")) {
                                mountPoint = TextFieldValue("/storage/emulated/0/Android/data/$pkg/files/rclone/$trimmed")
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.mount_mode_isolated),
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isIsolated) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.mount_isolated_card_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.mount_isolated_card_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = targetPackage,
                                onValueChange = {
                                    targetPackage = it
                                    packageError = null
                                    formErrorMsg = null
                                    val pkg = it.text.trim().ifBlank { "com.example.app" }
                                    val trimmed = name.text.trim().ifBlank { "mount" }
                                    mountPoint = TextFieldValue("/storage/emulated/0/Android/data/$pkg/files/rclone/$trimmed")
                                },
                                label = { Text(stringResource(R.string.mount_target_package_label) + " *") },
                                isError = packageError != null,
                                supportingText = {
                                    if (packageError != null) {
                                        Text(packageError!!, color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { showAppPicker = true }) {
                                        Icon(Icons.Default.Apps, contentDescription = stringResource(R.string.mount_select_app_tooltip), tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                // Remote selector
                ExposedDropdownMenuBox(
                    expanded = remoteDropdownExpanded,
                    onExpandedChange = { remoteDropdownExpanded = !remoteDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentRemoteName = remotes.find { it.id == selectedRemoteId }?.let { "${it.name} (${it.type})" }
                        ?: if (remotes.isEmpty()) stringResource(R.string.mount_err_remotes_none) else stringResource(R.string.mount_err_remote_empty)
                    OutlinedTextField(
                        value = currentRemoteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.mount_associated_remote) + " *") },
                        isError = remoteError != null,
                        supportingText = {
                            if (remoteError != null) {
                                Text(remoteError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = remoteDropdownExpanded,
                        onDismissRequest = { remoteDropdownExpanded = false }
                    ) {
                        remotes.forEach { r ->
                            DropdownMenuItem(
                                text = { Text("${r.name} (${r.type})") },
                                onClick = {
                                    selectedRemoteId = r.id
                                    remoteError = null
                                    formErrorMsg = null
                                    remoteDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Remote subpath with Picker
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text(stringResource(R.string.mount_remote_subpath_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showRemotePathPicker = true }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.mount_cd_pick_remote),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )

                // Mount point with Picker
                OutlinedTextField(
                    value = mountPoint,
                    onValueChange = {
                        mountPoint = it
                        mountPointError = null
                        formErrorMsg = null
                        isCustomMountPoint = true
                    },
                    label = { Text(stringResource(R.string.mount_point_path_label)) },
                    isError = mountPointError != null,
                    supportingText = {
                        if (mountPointError != null) {
                            Text(mountPointError!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showLocalMountPicker = true }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.mount_cd_pick_local),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = stringResource(R.string.mount_presets_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val trimmedName = name.text.trim().ifBlank { "name" }
                    val presets = if (isIsolated) {
                        val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                        listOf(
                            "/storage/emulated/0/Android/data/$pkg/files/rclone/$trimmedName" to stringResource(R.string.mount_preset_external_app),
                            "/data/data/$pkg/files/rclone/$trimmedName" to stringResource(R.string.mount_preset_internal_rclone),
                            "/data/data/$pkg/files/$trimmedName" to stringResource(R.string.mount_preset_internal_root)
                        )
                    } else {
                        listOf(
                            "/mnt/rclone-$trimmedName" to stringResource(R.string.mount_preset_default_mnt),
                            "/sdcard/$trimmedName" to stringResource(R.string.mount_preset_sdcard),
                            "/storage/emulated/0/$trimmedName" to stringResource(R.string.mount_preset_storage)
                        )
                    }
                    presets.forEach { (path, label) ->
                        val isSelected = mountPoint.text.trim() == path
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                mountPoint = TextFieldValue(path)
                                isCustomMountPoint = true
                                mountPointError = null
                                formErrorMsg = null
                            },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }

                val currentMountPoint = mountPoint.text.trim().ifBlank {
                    if (isIsolated) {
                        val pkg = targetPackage.text.trim().ifBlank { "<package>" }
                        "/storage/emulated/0/Android/data/$pkg/files/rclone/${name.text.trim().ifBlank { "mount" }}"
                    } else {
                        if (name.text.isNotBlank()) "/mnt/rclone-${name.text.trim()}" else ""
                    }
                }
                if (isIsolated) {
                    Text(
                        if (currentMountPoint.contains("/Android/data/")) {
                            stringResource(R.string.mount_hint_external_isolated)
                        } else {
                            stringResource(R.string.mount_hint_internal_isolated)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (currentMountPoint.startsWith("/mnt/rclone-")) {
                    Text(
                        stringResource(R.string.mount_hint_magisk_bind, name.text.trim().ifBlank { "<name>" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (currentMountPoint.isNotBlank()) {
                    Text(
                        stringResource(R.string.mount_hint_direct, currentMountPoint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                // Cache mode
                ExposedDropdownMenuBox(
                    expanded = modeDropdownExpanded,
                    onExpandedChange = { modeDropdownExpanded = !modeDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = cacheMode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.mount_cache_mode_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = modeDropdownExpanded,
                        onDismissRequest = { modeDropdownExpanded = false }
                    ) {
                        cacheModes.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    cacheMode = m
                                    modeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaterialTextField(value = cacheMaxSize, onValueChange = { cacheMaxSize = it }, label = stringResource(R.string.mount_cache_max_size_label), modifier = Modifier.weight(1f))
                    MaterialTextField(value = cacheMaxAge, onValueChange = { cacheMaxAge = it }, label = stringResource(R.string.mount_cache_max_age_label), modifier = Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mount_read_only))
                        if (isIsolated) {
                            Text(
                                if (readOnly) stringResource(R.string.mount_ro_protect_desc) else stringResource(R.string.mount_rw_warning_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (readOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Switch(
                        checked = readOnly,
                        onCheckedChange = { targetState ->
                            if (!targetState && isIsolated) {
                                showReadOnlyWarningDialog = true
                            } else {
                                readOnly = targetState
                            }
                        }
                    )
                }
            }
        }
    },
        confirmButton = {
            Button(
                onClick = {
                    nameError = null
                    remoteError = null
                    packageError = null
                    mountPointError = null
                    formErrorMsg = null

                    val n = name.text.trim()
                    if (n.isBlank()) {
                        nameError = context.getString(R.string.mount_err_name_empty)
                        formErrorMsg = context.getString(R.string.mount_err_name_empty)
                        return@Button
                    }
                    if (!n.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        nameError = context.getString(R.string.mount_err_name_invalid)
                        formErrorMsg = context.getString(R.string.mount_err_name_invalid)
                        return@Button
                    }

                    if (selectedRemoteId.isBlank()) {
                        remoteError = if (remotes.isEmpty()) {
                            context.getString(R.string.mount_err_remotes_none)
                        } else {
                            context.getString(R.string.mount_err_remote_empty)
                        }
                        formErrorMsg = remoteError
                        return@Button
                    }

                    if (isIsolated) {
                        val pkg = targetPackage.text.trim()
                        if (pkg.isBlank()) {
                            packageError = context.getString(R.string.mount_err_package_empty)
                            formErrorMsg = context.getString(R.string.mount_err_package_empty)
                            return@Button
                        }
                        if (!pkg.contains(".") || pkg.split('.').any { it.isEmpty() || !it.first().isLetter() }) {
                            packageError = context.getString(R.string.mount_err_package_invalid)
                            formErrorMsg = context.getString(R.string.mount_err_package_invalid)
                            return@Button
                        }
                    }

                    val mp = mountPoint.text.trim().ifBlank {
                        if (isIsolated) {
                            val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                            "/storage/emulated/0/Android/data/$pkg/files/rclone/$n"
                        } else {
                            "/mnt/rclone-$n"
                        }
                    }
                    if (mp.isBlank()) {
                        mountPointError = context.getString(R.string.mount_err_point_empty)
                        formErrorMsg = context.getString(R.string.mount_err_point_empty)
                        return@Button
                    }

                    val fileExtensions = listOf(".txt", ".pdf", ".zip", ".apk", ".mp4", ".mkv", ".mp3", ".jpg", ".png", ".tar", ".gz", ".json", ".xml", ".iso")
                    val lower = mp.lowercase()
                    if (fileExtensions.any { lower.endsWith(it) }) {
                        val err = context.getString(R.string.mount_err_point_is_file) + " ($mp)"
                        mountPointError = err
                        formErrorMsg = err
                        return@Button
                    }

                    val tp = if (isIsolated) targetPackage.text.trim().ifBlank { null } else null
                    onSubmit(
                        n,
                        selectedRemoteId,
                        remotePath.text.trim().ifBlank { "/" },
                        mp,
                        readOnly,
                        cacheMode,
                        cacheMaxSize.text.trim().ifBlank { "32G" },
                        cacheMaxAge.text.trim().ifBlank { "36h" },
                        tp,
                        isIsolated
                    )
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )

    if (showReadOnlyWarningDialog) {
        AlertDialog(
            onDismissRequest = { showReadOnlyWarningDialog = false },
            title = {
                Text(
                    stringResource(R.string.mount_warning_disable_readonly_title),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.mount_warning_disable_readonly_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        readOnly = false
                        showReadOnlyWarningDialog = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.mount_warning_confirm_writable))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        readOnly = true
                        showReadOnlyWarningDialog = false
                    }
                ) {
                    Text(stringResource(R.string.mount_warning_keep_readonly))
                }
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onSelect = { pkg ->
                targetPackage = TextFieldValue(pkg)
                packageError = null
                formErrorMsg = null
                val trimmed = name.text.trim().ifBlank { "mount" }
                mountPoint = TextFieldValue("/storage/emulated/0/Android/data/$pkg/files/rclone/$trimmed")
                isCustomMountPoint = true
                showAppPicker = false
            }
        )
    }

    if (showRemotePathPicker) {
        val selectedRemote = remotes.find { it.id == selectedRemoteId }
        val rName = selectedRemote?.name ?: ""
        val initialP = if (rName.isNotBlank()) "$rName:${remotePath.text.trim()}" else remotePath.text.trim()
        PathPickerDialog(
            title = stringResource(R.string.mount_pick_remote_subpath_title, rName.ifBlank { stringResource(R.string.path_picker_tab_remote) }),
            initialPath = initialP,
            remotes = remotes,
            client = client,
            bearer = bearer,
            directoryOnly = true,
            pickerMode = PathPickerMode.REMOTE_ONLY,
            onDismiss = { showRemotePathPicker = false },
            onConfirm = { chosen ->
                val sub = if (chosen.contains(":")) {
                    val r = chosen.substringBefore(":")
                    remotes.find { it.name == r }?.let {
                        selectedRemoteId = it.id
                        remoteError = null
                        formErrorMsg = null
                    }
                    chosen.substringAfter(":")
                } else chosen
                val normalized = if (sub.isBlank()) "/" else if (sub.startsWith("/")) sub else "/$sub"
                remotePath = TextFieldValue(normalized)
                showRemotePathPicker = false
            }
        )
    }

    if (showLocalMountPicker) {
        PathPickerDialog(
            title = stringResource(R.string.mount_pick_local_path_title),
            initialPath = mountPoint.text.ifBlank { "/storage/emulated/0" },
            remotes = remotes,
            client = client,
            bearer = bearer,
            directoryOnly = true,
            pickerMode = PathPickerMode.LOCAL_ONLY,
            onDismiss = { showLocalMountPicker = false },
            onConfirm = { chosen ->
                val localP = if (chosen.contains(":")) chosen.substringAfter(":") else chosen
                mountPoint = TextFieldValue(localP)
                mountPointError = null
                formErrorMsg = null
                isCustomMountPoint = true
                showLocalMountPicker = false
            }
        )
    }
}

data class InstalledAppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable? = null
)

fun drawableToBitmap(drawable: Drawable, width: Int = 96, height: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { appInfo ->
                    InstalledAppItem(
                        name = appInfo.loadLabel(pm).toString(),
                        packageName = appInfo.packageName,
                        icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                    )
                }
                .sortedWith(compareBy({ it.packageName.startsWith("android") || it.packageName.startsWith("com.android") }, { it.name.lowercase() }))
            withContext(Dispatchers.Main) {
                apps = list
                loading = false
            }
        }
    }

    val filteredApps = remember(query, apps) {
        if (query.isBlank()) apps else {
            val q = query.trim().lowercase()
            apps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mount_select_app_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.mount_search_app_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.mount_app_not_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(app.packageName) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.icon != null) {
                                    val bmp = remember(app.packageName) {
                                        runCatching { drawableToBitmap(app.icon) }.getOrNull()
                                    }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else {
                                        Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                } else {
                                    Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        app.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
