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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.android.rclone.manager.GatewayClient
import com.android.rclone.manager.data.model.MountProfileItem
import com.android.rclone.manager.data.model.RemoteItem
import com.android.rclone.manager.data.model.parseMounts
import com.android.rclone.manager.data.model.parseRemotes
import com.android.rclone.manager.ui.component.ContentCard
import com.android.rclone.manager.ui.component.EmptyView
import com.android.rclone.manager.ui.component.InfoRow
import com.android.rclone.manager.ui.component.LoadingView
import com.android.rclone.manager.ui.component.MaterialTextField
import com.android.rclone.manager.ui.component.SectionTitle
import com.android.rclone.manager.ui.component.StatusBadge
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
                    message = "点击“创建挂载”将云存储挂载到 Android 文件系统 (/mnt/rclone-*)。"
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
                        Column {
                            Text(mount.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "挂载点: ${mount.mountPoint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        StatusBadge(status = mount.status)
                    }

                    Spacer(Modifier.height(4.dp))
                    InfoRow(label = "远端源", value = "${mount.remoteId}:${mount.remotePath}")
                    InfoRow(label = "Bind 共享路径", value = "/data/media/0/${mount.name}")
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (mount.status.uppercase() != "RUNNING") {
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
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateMountDialog(
            remotes = remotes,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMountDialog(
    remotes: List<RemoteItem>,
    onDismiss: () -> Unit,
    onSubmit: (name: String, remoteId: String, remotePath: String, mountPoint: String, readOnly: Boolean, mode: String, size: String, age: String) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var selectedRemoteId by remember { mutableStateOf(remotes.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(TextFieldValue("/")) }
    var cacheMode by remember { mutableStateOf("full") }
    var cacheMaxSize by remember { mutableStateOf(TextFieldValue("32G")) }
    var cacheMaxAge by remember { mutableStateOf(TextFieldValue("36h")) }
    var readOnly by remember { mutableStateOf(false) }

    var remoteDropdownExpanded by remember { mutableStateOf(false) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    val cacheModes = listOf("full", "writes", "minimal", "off")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建挂载 Profile", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MaterialTextField(value = name, onValueChange = { name = it }, label = "Profile 名称 (英文标识符)")

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

                MaterialTextField(value = remotePath, onValueChange = { remotePath = it }, label = "远端子路径 (默认 /)")

                val mountPointDisplay = if (name.text.isNotBlank()) "/mnt/rclone-${name.text.trim()}" else "/mnt/rclone-<name>"
                Text("挂载点: $mountPointDisplay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("对应共享目录: /data/media/0/${name.text.trim()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                    val mp = "/mnt/rclone-$n"
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
}
