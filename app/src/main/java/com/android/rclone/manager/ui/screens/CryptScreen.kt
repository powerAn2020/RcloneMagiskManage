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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
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
import com.android.rclone.manager.GatewayClient
import com.android.rclone.manager.data.model.CryptProfileItem
import com.android.rclone.manager.data.model.RemoteItem
import com.android.rclone.manager.data.model.parseCrypts
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
fun CryptScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var crypts by remember { mutableStateOf<List<CryptProfileItem>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val loadCrypts = {
        scope.launch {
            isLoading = true
            client.crypts(bearer).fold(
                onSuccess = { crypts = parseCrypts(it) },
                onFailure = { onShowMessage("获取 Crypt 列表失败: ${it.message}") }
            )
            client.remotes(bearer).onSuccess { remotes = parseRemotes(it) }
            isLoading = false
        }
    }

    LaunchedEffect(bearer) {
        loadCrypts()
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
                SectionTitle(text = "rclone Crypt 加密档案 (${crypts.size})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { loadCrypts() },
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
                        Text("新建加密")
                    }
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("端到端透明加密", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "• 基于 rclone crypt 模块，提供文件名与文件内容的客户端加密。\n" +
                    "• 密码使用 rclone 标准 AES-CTR obscure 算法混淆保护，由 Gateway 加密存储在 Secret Store 中。\n" +
                    "• API 绝不回显密码明文，仅返回 passwordConfigured 标志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isLoading) {
            item { LoadingView(message = "正在加载 Crypt 档案…") }
        } else if (crypts.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Security,
                    title = "暂无 Crypt 加密档案",
                    message = "点击右上角“新建加密”为任意已有远端配置加密层。"
                )
            }
        } else {
            items(crypts, key = { it.id }) { crypt ->
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(crypt.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        StatusBadge(status = if (crypt.passwordConfigured) "ENABLED" else "DISABLED")
                    }
                    Spacer(Modifier.height(4.dp))
                    val remoteDisplayName = crypt.remoteName
                        ?: remotes.find { it.id == crypt.remoteId }?.name
                        ?: crypt.remoteId
                    InfoRow(label = "底层远端", value = remoteDisplayName)
                    InfoRow(label = "底层路径", value = crypt.remotePath)
                    InfoRow(label = "密码配置", value = if (crypt.passwordConfigured) "已加密存储" else "未设置")

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                onShowMessage("正在测试 ${crypt.name} 加密物化…")
                                client.cryptTest(crypt.id, bearer).fold(
                                    onSuccess = { onShowMessage("加密测试通过: $it") },
                                    onFailure = { onShowMessage("加密测试失败: ${it.message}") }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试加密物化与配置")
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCryptDialog(
            remotes = remotes,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, remoteId, remotePath, password ->
                scope.launch {
                    client.createCrypt(name, remoteId, remotePath.ifBlank { null }, password.ifBlank { null }, bearer).fold(
                        onSuccess = {
                            onShowMessage("加密档案创建成功")
                            showCreateDialog = false
                            loadCrypts()
                        },
                        onFailure = { onShowMessage("创建失败: ${it.message}") }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCryptDialog(
    remotes: List<RemoteItem>,
    onDismiss: () -> Unit,
    onSubmit: (name: String, remoteId: String, remotePath: String, password: String) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var selectedRemoteId by remember { mutableStateOf(remotes.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(TextFieldValue("/")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var remoteDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Crypt 加密档案", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MaterialTextField(value = name, onValueChange = { name = it }, label = "Crypt Profile 名称")

                ExposedDropdownMenuBox(
                    expanded = remoteDropdownExpanded,
                    onExpandedChange = { remoteDropdownExpanded = !remoteDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentRemoteName = remotes.find { it.id == selectedRemoteId }?.name ?: "请选择底层远端"
                    OutlinedTextField(
                        value = currentRemoteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("底层远端") },
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

                MaterialTextField(value = remotePath, onValueChange = { remotePath = it }, label = "远端基础路径 (默认 /)")
                MaterialTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "加密密码 (可选，掩码保护)",
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val n = name.text.trim()
                    if (n.isBlank() || selectedRemoteId.isBlank()) return@Button
                    onSubmit(n, selectedRemoteId, remotePath.text.trim(), password.text.trim())
                }
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
