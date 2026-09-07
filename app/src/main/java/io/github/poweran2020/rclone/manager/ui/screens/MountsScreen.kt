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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
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
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountsScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
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
                onFailure = { onShowMessage("加载挂载列表失败: ${it.message}") }
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
                SectionTitle(text = "挂载管理 (${mounts.size})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { loadMounts() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("创建挂载")
                    }
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = "正在获取挂载列表…") }
        } else if (mounts.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Storage,
                    title = "暂无挂载 Profile",
                    message = "点击“创建挂载”将云存储挂载到 Android 文件系统 (/mnt/rclone-* 或自定义路径)。"
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
                            Text(mount.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "挂载点: ${mount.mountPoint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        StatusBadge(status = mount.status)
                    }

                    val remoteDisplayName = mount.remoteName
                        ?: remotes.find { it.id == mount.remoteId }?.name
                        ?: mount.remoteId
                    InfoRow(label = "远端源", value = "${remoteDisplayName}:${mount.remotePath}")
                    if (mount.mountPoint.startsWith("/mnt/rclone-")) {
                        InfoRow(label = "Bind 共享路径", value = "/data/media/0/${mount.name}")
                    } else {
                        InfoRow(label = "挂载类型", value = "直接挂载 (${mount.mountPoint})")
                    }
                    InfoRow(label = "缓存配置", value = "${mount.cacheMode} · ${mount.cacheMaxSize} · ${mount.cacheMaxAge}")
                    mount.pid?.let { InfoRow(label = "Worker PID", value = it.toString()) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开机自动恢复 (Enabled)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = mount.enabled,
                            onCheckedChange = { enable ->
                                scope.launch {
                                    val action = if (enable) "enable" else "disable"
                                    client.mountAction(mount.id, action, bearer).fold(
                                        onSuccess = {
                                            onShowMessage("已${if (enable) "启用" else "禁用"}开机恢复")
                                            loadMounts()
                                        },
                                        onFailure = { onShowMessage("操作失败: ${it.message}") }
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
                                onClick = {
                                    scope.launch {
                                        client.mountAction(mount.id, "start", bearer).fold(
                                            onSuccess = { onShowMessage("已发送启动请求"); loadMounts() },
                                            onFailure = { onShowMessage("启动失败: ${it.message}") }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("启动挂载")
                            }
                        } else {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        client.mountAction(mount.id, "stop", bearer).fold(
                                            onSuccess = { onShowMessage("已发送停止请求"); loadMounts() },
                                            onFailure = { onShowMessage("停止失败: ${it.message}") }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("停止挂载")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (isRunning || mount.status.uppercase() == "STARTING") {
                                    onShowMessage("挂载正在运行中，请先停止挂载后再进行编辑修改")
                                } else {
                                    mountToEdit = mount
                                }
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("编辑")
                        }

                        IconButton(
                            onClick = { mountToDelete = mount }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除挂载",
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
            title = "创建挂载 Profile",
            initialMount = null,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, remoteId, remotePath, mountPoint, readOnly, mode, size, age ->
                scope.launch {
                    client.createMount(name, remoteId, mountPoint, bearer, remotePath, null, readOnly, mode, size, age).fold(
                        onSuccess = {
                            onShowMessage("挂载配置创建成功")
                            showCreateDialog = false
                            loadMounts()
                        },
                        onFailure = { onShowMessage("创建挂载失败: ${it.message}") }
                    )
                }
            }
        )
    }

    mountToEdit?.let { editTarget ->
        MountEditDialog(
            title = "编辑挂载 Profile: ${editTarget.name}",
            initialMount = editTarget,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { mountToEdit = null },
            onSubmit = { name, remoteId, remotePath, mountPoint, readOnly, mode, size, age ->
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
                        cacheMaxAge = age
                    ).fold(
                        onSuccess = {
                            onShowMessage("挂载配置已成功更新")
                            mountToEdit = null
                            loadMounts()
                        },
                        onFailure = { onShowMessage("更新挂载失败: ${it.message}") }
                    )
                }
            }
        )
    }

    mountToDelete?.let { delTarget ->
        DangerousConfirmDialog(
            show = true,
            title = "删除挂载: ${delTarget.name}",
            message = "确定要删除此挂载 Profile 吗？" +
                    (if (delTarget.status.uppercase() == "RUNNING") "\n当前挂载正在运行，删除将先强制停止后台 Worker 进程并卸载挂载点。" else "") +
                    "\n删除后配置无法撤销。",
            confirmLabel = "确认删除",
            isLoading = isDeleting,
            onConfirm = {
                scope.launch {
                    isDeleting = true
                    client.deleteMount(delTarget.id, bearer).fold(
                        onSuccess = {
                            isDeleting = false
                            onShowMessage("挂载配置已成功删除")
                            mountToDelete = null
                            loadMounts()
                        },
                        onFailure = {
                            isDeleting = false
                            onShowMessage("删除挂载失败: ${it.message}")
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
    onSubmit: (name: String, remoteId: String, remotePath: String, mountPoint: String, readOnly: Boolean, mode: String, size: String, age: String) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initialMount?.name ?: "")) }
    var selectedRemoteId by remember { mutableStateOf(initialMount?.remoteId ?: (remotes.firstOrNull()?.id ?: "")) }
    var remotePath by remember { mutableStateOf(TextFieldValue(initialMount?.remotePath ?: "/")) }
    var mountPoint by remember { mutableStateOf(TextFieldValue(initialMount?.mountPoint ?: "")) }
    var isCustomMountPoint by remember { mutableStateOf(initialMount != null) }
    var cacheMode by remember { mutableStateOf(initialMount?.cacheMode ?: "full") }
    var cacheMaxSize by remember { mutableStateOf(TextFieldValue(initialMount?.cacheMaxSize ?: "32G")) }
    var cacheMaxAge by remember { mutableStateOf(TextFieldValue(initialMount?.cacheMaxAge ?: "36h")) }
    var readOnly by remember { mutableStateOf(initialMount?.readOnly ?: false) }

    var remoteDropdownExpanded by remember { mutableStateOf(false) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    val cacheModes = listOf("full", "writes", "minimal", "off")

    var showRemotePathPicker by remember { mutableStateOf(false) }
    var showLocalMountPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MaterialTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (!isCustomMountPoint && initialMount == null) {
                            val trimmed = it.text.trim()
                            mountPoint = TextFieldValue(if (trimmed.isNotBlank()) "/mnt/rclone-$trimmed" else "")
                        }
                    },
                    label = "Profile 名称 (英文标识符)"
                )

                // Remote selector
                ExposedDropdownMenuBox(
                    expanded = remoteDropdownExpanded,
                    onExpandedChange = { remoteDropdownExpanded = !remoteDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentRemoteName = remotes.find { it.id == selectedRemoteId }?.name ?: "请选择远端"
                    OutlinedTextField(
                        value = currentRemoteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("关联远端") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
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
                    label = { Text("远端子路径 (默认 /)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showRemotePathPicker = true }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "浏览选择远端目录",
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
                        isCustomMountPoint = true
                    },
                    label = { Text("挂载点路径 (Mount Point)") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showLocalMountPicker = true }) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "浏览选择本地挂载目录",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )

                Text("常用挂载点预设:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val trimmedName = name.text.trim().ifBlank { "name" }
                    val presets = listOf(
                        "/mnt/rclone-$trimmedName" to "默认 (/mnt)",
                        "/sdcard/$trimmedName" to "内部存储",
                        "/storage/emulated/0/$trimmedName" to "标准存储"
                    )
                    presets.forEach { (path, label) ->
                        OutlinedButton(
                            onClick = {
                                mountPoint = TextFieldValue(path)
                                isCustomMountPoint = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                val currentMountPoint = mountPoint.text.trim().ifBlank {
                    if (name.text.isNotBlank()) "/mnt/rclone-${name.text.trim()}" else ""
                }
                if (currentMountPoint.startsWith("/mnt/rclone-")) {
                    Text(
                        "提示: /mnt/rclone-* 会由 Magisk 自动创建 /data/media/0/${name.text.trim().ifBlank { "<name>" }} 的 bind 共享映射",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (currentMountPoint.isNotBlank()) {
                    Text(
                        "直接挂载点: $currentMountPoint",
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
                        label = { Text("VFS 缓存模式") },
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
                    MaterialTextField(value = cacheMaxSize, onValueChange = { cacheMaxSize = it }, label = "缓存上限 (如 32G)", modifier = Modifier.weight(1f))
                    MaterialTextField(value = cacheMaxAge, onValueChange = { cacheMaxAge = it }, label = "最长保留 (如 36h)", modifier = Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("只读挂载 (Read-Only)")
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val n = name.text.trim()
                    if (n.isBlank() || selectedRemoteId.isBlank()) return@Button
                    val mp = mountPoint.text.trim().ifBlank { "/mnt/rclone-$n" }
                    onSubmit(
                        n,
                        selectedRemoteId,
                        remotePath.text.trim().ifBlank { "/" },
                        mp,
                        readOnly,
                        cacheMode,
                        cacheMaxSize.text.trim().ifBlank { "32G" },
                        cacheMaxAge.text.trim().ifBlank { "36h" }
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

    if (showRemotePathPicker) {
        val selectedRemote = remotes.find { it.id == selectedRemoteId }
        val rName = selectedRemote?.name ?: ""
        val initialP = if (rName.isNotBlank()) "$rName:${remotePath.text.trim()}" else remotePath.text.trim()
        PathPickerDialog(
            title = "选择远端子路径 (${rName.ifBlank { "远端" }})",
            initialPath = initialP,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showRemotePathPicker = false },
            onConfirm = { chosen ->
                val sub = if (chosen.contains(":")) chosen.substringAfter(":") else chosen
                val normalized = if (sub.isBlank()) "/" else if (sub.startsWith("/")) sub else "/$sub"
                remotePath = TextFieldValue(normalized)
                showRemotePathPicker = false
            }
        )
    }

    if (showLocalMountPicker) {
        PathPickerDialog(
            title = "选择本地挂载目录",
            initialPath = mountPoint.text.ifBlank { "/storage/emulated/0" },
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showLocalMountPicker = false },
            onConfirm = { chosen ->
                val localP = if (chosen.contains(":")) chosen.substringAfter(":") else chosen
                mountPoint = TextFieldValue(localP)
                isCustomMountPoint = true
                showLocalMountPicker = false
            }
        )
    }
}
