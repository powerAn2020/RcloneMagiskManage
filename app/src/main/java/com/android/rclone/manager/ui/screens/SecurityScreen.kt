package com.android.rclone.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.android.rclone.manager.GatewayClient
import com.android.rclone.manager.TokenStore
import com.android.rclone.manager.data.model.ClientGrantItem
import com.android.rclone.manager.data.model.ClientItem
import com.android.rclone.manager.data.model.formatEpochTime
import com.android.rclone.manager.data.model.parseClients
import com.android.rclone.manager.data.model.parseGrants
import com.android.rclone.manager.ui.component.ContentCard
import com.android.rclone.manager.ui.component.EmptyView
import com.android.rclone.manager.ui.component.InfoRow
import com.android.rclone.manager.ui.component.LoadingView
import com.android.rclone.manager.ui.component.MaterialTextField
import com.android.rclone.manager.ui.component.SectionTitle
import com.android.rclone.manager.ui.component.StatusBadge
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    tokenStore: TokenStore,
    onTokenUpdated: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var clientsList by remember { mutableStateOf<List<ClientItem>>(emptyList()) }
    var pairingCodeInfo by remember { mutableStateOf<String?>(null) }
    var showPairingCompleteDialog by remember { mutableStateOf(false) }

    var selectedClientForGrants by remember { mutableStateOf<ClientItem?>(null) }
    var clientGrantsList by remember { mutableStateOf<List<ClientGrantItem>>(emptyList()) }
    var showAddGrantDialog by remember { mutableStateOf(false) }
    var selectedClientForAcl by remember { mutableStateOf<ClientItem?>(null) }

    val loadClients = {
        scope.launch {
            isLoading = true
            client.clients(bearer).fold(
                onSuccess = { clientsList = parseClients(it) },
                onFailure = { onShowMessage("获取客户端失败: ${it.message}") }
            )
            isLoading = false
        }
    }

    LaunchedEffect(bearer) {
        loadClients()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(text = "客户端安全配对 (Pairing)")
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("受控配对与 Bearer 令牌分配", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "• 客户端接入采用最小权限模型与一次性配对码。\n" +
                    "• 配对码有效期为 300 秒，完成配对后 Gateway 发放独立 Bearer 令牌。\n" +
                    "• 令牌仅以哈希值落盘，支持按客户端禁用与即时令牌轮换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            client.autoPair().fold(
                                onSuccess = { token ->
                                    tokenStore.write(token)
                                    onTokenUpdated(token)
                                    onShowMessage("配对成功！Token 已自动加密保存并生效")
                                    loadClients()
                                },
                                onFailure = { onShowMessage("自动配对失败: ${it.message}") }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("本机一键自动配对 (推荐)")
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                client.pairingStart().fold(
                                    onSuccess = { res ->
                                        val code = JSONObject(res).optString("pairingCode")
                                        pairingCodeInfo = code
                                        onShowMessage("已生成新配对码: $code (300 秒有效)")
                                    },
                                    onFailure = { onShowMessage("启动配对失败: ${it.message}") }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("获取新配对码")
                    }
                    OutlinedButton(
                        onClick = { showPairingCompleteDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("手动完成配对")
                    }
                }
                pairingCodeInfo?.let { code ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "当前一次性配对码: $code (300 秒内有效)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(text = "已配对控制端 (${clientsList.size})")
                OutlinedButton(
                    onClick = { loadClients() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = "正在加载客户端列表…") }
        } else if (clientsList.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Security,
                    title = "暂无已配对客户端",
                    message = "本地默认 root 进程直接通过 Unix socket 连接。"
                )
            }
        } else {
            items(clientsList, key = { it.id }) { c ->
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("ID: ${c.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(status = if (c.enabled) "ENABLED" else "DISABLED")
                    }
                    Spacer(Modifier.height(4.dp))
                    InfoRow(label = "注册时间", value = formatEpochTime(c.createdAt))
                    c.lastUsedAt?.let { InfoRow(label = "最近活跃", value = formatEpochTime(it)) }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedClientForGrants = c
                                scope.launch {
                                    client.grants(c.id, bearer).onSuccess {
                                        clientGrantsList = parseGrants(it)
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Scope 授权")
                        }

                        OutlinedButton(
                            onClick = { selectedClientForAcl = c },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Remote ACL")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.rotateToken(c.id, bearer).fold(
                                        onSuccess = {
                                            val newToken = JSONObject(it).optString("token")
                                            onShowMessage("新令牌: $newToken (仅显示一次)")
                                        },
                                        onFailure = { onShowMessage("轮换失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("轮换 Token")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.disableClient(c.id, bearer).fold(
                                        onSuccess = { onShowMessage("已禁用该客户端"); loadClients() },
                                        onFailure = { onShowMessage("操作失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("禁用")
                        }
                    }
                }
            }
        }
    }

    // Complete pairing dialog
    if (showPairingCompleteDialog) {
        var codeInput by remember { mutableStateOf(TextFieldValue(pairingCodeInfo ?: "")) }
        var nameInput by remember { mutableStateOf(TextFieldValue("Android Controller")) }
        var pubKeyInput by remember { mutableStateOf(TextFieldValue("local-controller-pubkey")) }

        LaunchedEffect(pairingCodeInfo) {
            pairingCodeInfo?.let { code ->
                if (code.isNotBlank()) {
                    codeInput = TextFieldValue(code)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showPairingCompleteDialog = false },
            title = { Text("完成配对交换", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("配对参数", style = MaterialTheme.typography.labelMedium)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    client.pairingStart().fold(
                                        onSuccess = { res ->
                                            val code = JSONObject(res).optString("pairingCode")
                                            pairingCodeInfo = code
                                            codeInput = TextFieldValue(code)
                                            onShowMessage("已生成最新配对码: $code")
                                        },
                                        onFailure = { onShowMessage("生成配对码失败: ${it.message}") }
                                    )
                                }
                            }
                        ) {
                            Text("刷新生成配对码")
                        }
                    }
                    MaterialTextField(value = codeInput, onValueChange = { codeInput = it }, label = "6 位配对码")
                    MaterialTextField(value = nameInput, onValueChange = { nameInput = it }, label = "客户端名称")
                    MaterialTextField(value = pubKeyInput, onValueChange = { pubKeyInput = it }, label = "公钥字符串 (HMAC 身份标识)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val c = codeInput.text.trim()
                        val n = nameInput.text.trim()
                        val k = pubKeyInput.text.trim()
                        if (c.isNotBlank() && n.isNotBlank() && k.isNotBlank()) {
                            scope.launch {
                                client.pairingComplete(c, n, k).fold(
                                    onSuccess = { res ->
                                        val token = JSONObject(res).optString("token")
                                        if (token.isNotBlank()) {
                                            tokenStore.write(token)
                                            onTokenUpdated(token)
                                            onShowMessage("配对成功！新 Token 已加密存入 Keystore")
                                        } else {
                                            onShowMessage("配对成功: $res")
                                        }
                                        showPairingCompleteDialog = false
                                        loadClients()
                                    },
                                    onFailure = { err ->
                                        val msg = err.message ?: ""
                                        val friendly = when {
                                            msg.contains("AUTH_INVALID") || msg.contains("expired") ->
                                                "配对码已失效或不存在，请点击上方「刷新生成配对码」"
                                            msg.contains("Connection refused") -> "Gateway 离线，请先拉起服务"
                                            else -> "配对失败: $msg"
                                        }
                                        onShowMessage(friendly)
                                    }
                                )
                            }
                        } else {
                            onShowMessage("请填写完整配对参数")
                        }
                    }
                ) { Text("提交配对") }
            },
            dismissButton = {
                TextButton(onClick = { showPairingCompleteDialog = false }) { Text("取消") }
            }
        )
    }

    // Grants Dialog
    selectedClientForGrants?.let { currentClient ->
        AlertDialog(
            onDismissRequest = { selectedClientForGrants = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Scope 授权: ${currentClient.name}", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showAddGrantDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加授权")
                    }
                }
            },
            text = {
                if (clientGrantsList.isEmpty()) {
                    Text("该客户端目前没有任何 Scope 授权。")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(clientGrantsList) { g ->
                            ContentCard(insideMargin = PaddingValues(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(g.scope, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text("资源: ${g.resource}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                client.revokeGrant(currentClient.id, g.id, bearer).fold(
                                                    onSuccess = {
                                                        onShowMessage("已撤销授权")
                                                        client.grants(currentClient.id, bearer).onSuccess {
                                                            clientGrantsList = parseGrants(it)
                                                        }
                                                    },
                                                    onFailure = { onShowMessage("撤销失败: ${it.message}") }
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "撤销", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedClientForGrants = null }) { Text("关闭") }
            }
        )
    }

    // Add Grant Dialog
    if (showAddGrantDialog && selectedClientForGrants != null) {
        val targetClient = selectedClientForGrants!!
        var selectedScope by remember { mutableStateOf("file.read") }
        var resource by remember { mutableStateOf(TextFieldValue("*")) }
        var scopeDropdownExpanded by remember { mutableStateOf(false) }
        val scopes = listOf("system.read", "remote.read", "remote.write", "remote.delete", "file.read", "file.write", "file.delete", "job.execute", "job.control", "mount.write", "security.write", "*")

        AlertDialog(
            onDismissRequest = { showAddGrantDialog = false },
            title = { Text("分配 Scope 权限", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = scopeDropdownExpanded,
                        onExpandedChange = { scopeDropdownExpanded = !scopeDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedScope,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Scope 标识") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scopeDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = scopeDropdownExpanded,
                            onDismissRequest = { scopeDropdownExpanded = false }
                        ) {
                            scopes.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = {
                                        selectedScope = s
                                        scopeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    MaterialTextField(value = resource, onValueChange = { resource = it }, label = "作用资源 (默认 *)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            client.grant(targetClient.id, selectedScope, resource.text.trim().ifBlank { "*" }, bearer).fold(
                                onSuccess = {
                                    onShowMessage("授权成功")
                                    showAddGrantDialog = false
                                    client.grants(targetClient.id, bearer).onSuccess {
                                        clientGrantsList = parseGrants(it)
                                    }
                                },
                                onFailure = { onShowMessage("授权失败: ${it.message}") }
                            )
                        }
                    }
                ) { Text("授予") }
            },
            dismissButton = {
                TextButton(onClick = { showAddGrantDialog = false }) { Text("取消") }
            }
        )
    }

    // Remote ACL Dialog
    selectedClientForAcl?.let { targetClient ->
        var remoteId by remember { mutableStateOf(TextFieldValue("")) }
        var permissions by remember { mutableStateOf(TextFieldValue("file.read,file.write")) }
        var prefix by remember { mutableStateOf(TextFieldValue("/")) }
        AlertDialog(
            onDismissRequest = { selectedClientForAcl = null },
            title = { Text("配置 Remote ACL: ${targetClient.name}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MaterialTextField(value = remoteId, onValueChange = { remoteId = it }, label = "远端 ID (Remote ID)")
                    MaterialTextField(value = permissions, onValueChange = { permissions = it }, label = "权限列表 (逗号分隔: file.read,file.write,file.delete)")
                    MaterialTextField(value = prefix, onValueChange = { prefix = it }, label = "允许路径前缀 (如 /photos)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rId = remoteId.text.trim()
                        val perms = permissions.text.trim()
                        val pfx = prefix.text.trim().ifBlank { "/" }
                        if (rId.isNotBlank() && perms.isNotBlank()) {
                            scope.launch {
                                client.remoteAcl(targetClient.id, rId, perms, pfx, bearer).fold(
                                    onSuccess = {
                                        onShowMessage("Remote ACL 配置成功")
                                        selectedClientForAcl = null
                                    },
                                    onFailure = { onShowMessage("ACL 配置失败: ${it.message}") }
                                )
                            }
                        }
                    }
                ) { Text("保存 ACL") }
            },
            dismissButton = {
                TextButton(onClick = { selectedClientForAcl = null }) { Text("取消") }
            }
        )
    }
}
