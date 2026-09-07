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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingRemote by remember { mutableStateOf<RemoteItem?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<Pair<RemoteItem, String>?>(null) } // RemoteItem to confirmationToken
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
                SectionTitle(text = "远端列表 (${remotes.size})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { loadRemotes() },
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
                        Text("添加远端")
                    }
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = "正在加载远端列表…") }
        } else if (remotes.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Cloud,
                    title = "暂无配置的远端",
                    message = "点击右上角“添加远端”，配置 WebDAV、S3 或其他云存储。"
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
                                text = "类型: ${remote.type}" + if (endpointText != null) " · $endpointText" else "",
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
                            Text("测试")
                        }

                        OutlinedButton(onClick = { onNavigateToFileBrowser(remote.id) }) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(2.dp))
                            Text("浏览")
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
                            Text(if (remote.enabled) "禁用" else "启用")
                        }

                        IconButton(onClick = { editingRemote = remote }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑")
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    client.exportRemote(remote.id, bearer).fold(
                                        onSuccess = { exportJson = it },
                                        onFailure = { onShowMessage("导出失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "导出")
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
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
            title = "添加远端",
            initialName = "",
            initialType = "webdav",
            initialEndpoint = "",
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
            title = "编辑远端: ${remote.name}",
            initialName = remote.name,
            initialType = remote.type,
            initialEndpoint = remote.endpoint,
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

    deleteCandidate?.let { (remote, token) ->
        DangerousConfirmDialog(
            show = true,
            title = "确认删除远端: ${remote.name}",
            message = "此操作将从数据库和运行时移除该远端配置。若有挂载关联请先停止挂载。",
            tokenBadge = token,
            confirmLabel = "确认删除",
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

    exportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJson = null },
            title = { Text("远端配置导出 (已脱敏)", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = json,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { exportJson = null }) { Text("关闭") }
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
    onDismiss: () -> Unit,
    onSubmit: (name: String, type: String, endpoint: String, secret: JSONObject?) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName)) }
    var type by remember { mutableStateOf(initialType) }
    var endpoint by remember { mutableStateOf(TextFieldValue(initialEndpoint)) }
    var accessKey by remember { mutableStateOf(TextFieldValue("")) }
    var secretKey by remember { mutableStateOf(TextFieldValue("")) }
    var account by remember { mutableStateOf(TextFieldValue("")) }
    var typeExpanded by remember { mutableStateOf(false) }

    val supportedTypes = listOf("webdav", "s3", "local", "drive", "onedrive", "sftp", "smb", "ftp", "dropbox")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MaterialTextField(value = name, onValueChange = { name = it }, label = "远端名称 (英文标识符)")

                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = !typeExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("存储类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false }
                    ) {
                        supportedTypes.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    type = item
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                MaterialTextField(value = endpoint, onValueChange = { endpoint = it }, label = "端点 URL / 地址 (可选)")
                MaterialTextField(value = accessKey, onValueChange = { accessKey = it }, label = "Access Key / 用户名 (可选)")
                MaterialTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = "Secret / 密码 (可选，掩码保护)",
                    visualTransformation = PasswordVisualTransformation()
                )
                MaterialTextField(value = account, onValueChange = { account = it }, label = "Account / 账户 (可选)")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.text.isBlank()) return@Button
                    val secretObj = JSONObject().apply {
                        if (accessKey.text.isNotBlank()) put("access_key", accessKey.text.trim())
                        if (secretKey.text.isNotBlank()) put("secret", secretKey.text.trim())
                        if (account.text.isNotBlank()) put("account", account.text.trim())
                    }.takeIf { it.length() > 0 }
                    onSubmit(name.text.trim(), type.trim(), endpoint.text.trim(), secretObj)
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
