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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(mount.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (mount.isolated || mount.targetPackage != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "应用隔离",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
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
                    if (mount.isolated || mount.targetPackage != null) {
                        InfoRow(label = "隔离目标", value = mount.targetPackage ?: "专属沙盒私有目录 (Non-Broadcast)")
                    } else if (mount.mountPoint.startsWith("/mnt/rclone-")) {
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
            onSubmit = { name, remoteId, remotePath, mountPoint, readOnly, mode, size, age, targetPackage, isolated ->
                scope.launch {
                    client.createMount(name, remoteId, mountPoint, bearer, remotePath, null, readOnly, mode, size, age, targetPackage, isolated).fold(
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
    onSubmit: (name: String, remoteId: String, remotePath: String, mountPoint: String, readOnly: Boolean, mode: String, size: String, age: String, targetPackage: String?, isolated: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initialMount?.name ?: "")) }
    var selectedRemoteId by remember { mutableStateOf(initialMount?.remoteId ?: (remotes.firstOrNull()?.id ?: "")) }
    var remotePath by remember { mutableStateOf(TextFieldValue(initialMount?.remotePath ?: "/")) }
    var mountPoint by remember { mutableStateOf(TextFieldValue(initialMount?.mountPoint ?: "")) }
    var isCustomMountPoint by remember { mutableStateOf(initialMount != null) }
    var isIsolated by remember { mutableStateOf(initialMount?.isolated ?: (initialMount?.targetPackage != null)) }
    var targetPackage by remember { mutableStateOf(TextFieldValue(initialMount?.targetPackage ?: "")) }
    var showAppPicker by remember { mutableStateOf(false) }
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
                            if (isIsolated) {
                                val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                                mountPoint = TextFieldValue(if (trimmed.isNotBlank()) "/data/data/$pkg/files/rclone/$trimmed" else "")
                            } else {
                                mountPoint = TextFieldValue(if (trimmed.isNotBlank()) "/mnt/rclone-$trimmed" else "")
                            }
                        }
                    },
                    label = "Profile 名称 (英文标识符)"
                )

                // Visibility & Isolation Mode Selector
                Text("挂载模式", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isIsolated,
                        onClick = {
                            isIsolated = false
                            if (mountPoint.text.startsWith("/data/data/") || mountPoint.text.startsWith("/data/user/0/")) {
                                val trimmed = name.text.trim().ifBlank { "mount" }
                                mountPoint = TextFieldValue("/mnt/rclone-$trimmed")
                            }
                        },
                        label = { Text("全局通用挂载") },
                        leadingIcon = if (!isIsolated) { { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isIsolated,
                        onClick = {
                            isIsolated = true
                            val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                            val trimmed = name.text.trim().ifBlank { "mount" }
                            if (mountPoint.text.isBlank() || mountPoint.text.startsWith("/mnt/rclone-") || mountPoint.text.startsWith("/sdcard/") || mountPoint.text.startsWith("/storage/")) {
                                mountPoint = TextFieldValue("/data/data/$pkg/files/rclone/$trimmed")
                            }
                        },
                        label = { Text("应用专属隔离") },
                        leadingIcon = if (isIsolated) { { Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isIsolated) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("应用沙盒隔离配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "直接挂载至目标 App 专属私有目录，仅该 App 进程享有读写权限，不向全局公共存储广播。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = targetPackage,
                                onValueChange = {
                                    targetPackage = it
                                    val pkg = it.text.trim().ifBlank { "com.example.app" }
                                    val trimmed = name.text.trim().ifBlank { "mount" }
                                    mountPoint = TextFieldValue("/data/data/$pkg/files/rclone/$trimmed")
                                },
                                label = { Text("目标应用包名 (Package Name)") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { showAppPicker = true }) {
                                        Icon(Icons.Default.Apps, contentDescription = "选择已安装应用", tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
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
                    val presets = if (isIsolated) {
                        val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                        listOf(
                            "/data/data/$pkg/files/rclone/$trimmedName" to "files/rclone",
                            "/data/data/$pkg/files/$trimmedName" to "files (根)",
                            "/data/data/$pkg/cache/rclone/$trimmedName" to "cache/rclone"
                        )
                    } else {
                        listOf(
                            "/mnt/rclone-$trimmedName" to "默认 (/mnt)",
                            "/sdcard/$trimmedName" to "内部存储",
                            "/storage/emulated/0/$trimmedName" to "标准存储"
                        )
                    }
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
                    if (isIsolated) {
                        val pkg = targetPackage.text.trim().ifBlank { "<package>" }
                        "/data/data/$pkg/files/rclone/${name.text.trim().ifBlank { "mount" }}"
                    } else {
                        if (name.text.isNotBlank()) "/mnt/rclone-${name.text.trim()}" else ""
                    }
                }
                if (isIsolated) {
                    Text(
                        "提示: 专属隔离挂载仅对目标应用沙盒可见，不会向系统公共存储 (/data/media/0/*) 广播。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (currentMountPoint.startsWith("/mnt/rclone-")) {
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
                    val mp = mountPoint.text.trim().ifBlank {
                        if (isIsolated) {
                            val pkg = targetPackage.text.trim().ifBlank { "com.example.app" }
                            "/data/data/$pkg/files/rclone/$n"
                        } else {
                            "/mnt/rclone-$n"
                        }
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onSelect = { pkg ->
                targetPackage = TextFieldValue(pkg)
                val trimmed = name.text.trim().ifBlank { "mount" }
                mountPoint = TextFieldValue("/data/data/$pkg/files/rclone/$trimmed")
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
        title = { Text("选择目标应用 (App)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用名称或包名") },
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
                            "未找到相关应用",
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
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
