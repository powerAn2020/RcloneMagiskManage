package io.github.poweran2020.rclone.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.TokenStore
import io.github.poweran2020.rclone.manager.data.model.ClientGrantItem
import io.github.poweran2020.rclone.manager.data.model.ClientItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.formatEpochTime
import io.github.poweran2020.rclone.manager.data.model.parseClients
import io.github.poweran2020.rclone.manager.data.model.parseGrants
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var pairingExpiresAt by remember { mutableStateOf<Long?>(null) }
    var pairingRemainingSeconds by remember { mutableStateOf(0) }
    var showPairingCompleteDialog by remember { mutableStateOf(false) }
    var showRePairOptions by remember { mutableStateOf(false) }

    // Keep an updated reference to active pairing code for lifecycle disposal
    val currentPairingCodeRef = rememberUpdatedState(pairingCodeInfo)
    DisposableEffect(Unit) {
        onDispose {
            val activeCode = currentPairingCodeRef.value
            if (!activeCode.isNullOrEmpty()) {
                // Immediately destroy/cancel pairing code on gateway backend when leaving the screen
                CoroutineScope(Dispatchers.IO).launch {
                    client.pairingCancel(activeCode)
                }
            }
        }
    }

    // Dynamic countdown timer for pairing code
    LaunchedEffect(pairingExpiresAt) {
        val target = pairingExpiresAt ?: return@LaunchedEffect
        while (true) {
            val diff = ((target - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()
            pairingRemainingSeconds = diff
            if (diff <= 0) {
                pairingCodeInfo?.let { expiredCode ->
                    client.pairingCancel(expiredCode)
                }
                break
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    // LAN configuration states
    var lanConfig by remember { mutableStateOf(GatewayClient.LanConfig(enabled = false)) }
    var deviceIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var isRefreshingIps by remember { mutableStateOf(false) }
    var lanPortInput by remember { mutableStateOf("8443") }
    var isLanUpdating by remember { mutableStateOf(false) }
    var showAutoPairAdminDialog by remember { mutableStateOf(false) }

    val loadLanConfig = {
        scope.launch {
            lanConfig = client.getLanConfig()
            lanPortInput = lanConfig.port.toString()
            deviceIps = client.getDeviceIpAddresses()
        }
    }

    // Dialog states
    var selectedClientForGrants by remember { mutableStateOf<ClientItem?>(null) }
    var clientGrantsList by remember { mutableStateOf<List<ClientGrantItem>>(emptyList()) }
    var selectedClientForAcl by remember { mutableStateOf<ClientItem?>(null) }
    var clientToDelete by remember { mutableStateOf<ClientItem?>(null) }
    var rotatedTokenDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

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
        loadLanConfig()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle(text = stringResource(R.string.security_pairing_section))
        }

        val isPaired = bearer.isNotBlank()
        val currentClient = clientsList.find { it.isCurrent }

        if (isPaired) {
            item {
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.security_paired_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                stringResource(R.string.security_paired_online),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• 本机已持有由 Gateway 签发并由 Android Keystore 硬件安全加密的 Bearer 令牌。\n" +
                        "• 通信通过专属 Unix Domain Socket 直连，日常使用无需再次配对。\n" +
                        (if (currentClient != null) "• 对应控制端身份: ${currentClient.name} (${currentClient.id.take(8)})" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showRePairOptions = !showRePairOptions }) {
                            Icon(if (showRePairOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (showRePairOptions) "收起重新配对选项" else "重新配对本机 (仅在令牌失效时需要)")
                        }
                    }
                    if (showRePairOptions) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { showAutoPairAdminDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("重新一键生成并绑定新令牌")
                        }
                    }
                }
            }
        } else {
            item {
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.security_unpaired_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.security_unpaired_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showAutoPairAdminDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.security_btn_auto_pair_rec))
                    }
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.security_external_clients), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "• 供局域网电脑、Web 控制台或第三方命令行客户端配对授权接入。\n" +
                    "• 配对码有效期为 60 秒，完成配对后 Gateway 发放独立 Bearer 令牌。\n" +
                    "• 令牌仅以哈希值落盘，支持按客户端单独禁用与即时令牌轮换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pairingCodeInfo?.let { oldCode ->
                                    client.pairingCancel(oldCode)
                                }
                                client.pairingStart().fold(
                                    onSuccess = { res ->
                                        val obj = JSONObject(res)
                                        val code = obj.optString("pairingCode")
                                        val expiresIn = obj.optLong("expiresIn", 60L)
                                        pairingCodeInfo = code
                                        pairingExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
                                        pairingRemainingSeconds = expiresIn.toInt()
                                        onShowMessage("已生成新配对码: $code ($expiresIn 秒有效)")
                                    },
                                    onFailure = { onShowMessage("启动配对失败: ${it.message}") }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.security_get_pairing_code))
                    }
                    OutlinedButton(
                        onClick = { showPairingCompleteDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("手动完成配对")
                    }
                }
                pairingCodeInfo?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    if (pairingRemainingSeconds > 0) {
                        val isUrgent = pairingRemainingSeconds <= 60
                        Surface(
                            color = if (isUrgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("当前一次性配对码: ", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = code,
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "⏱️ 有效倒计时: ${pairingRemainingSeconds} 秒",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isUrgent) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Pairing Code", code))
                                        onShowMessage("已复制配对码: $code")
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "复制配对码")
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            client.pairingCancel(code)
                                            pairingCodeInfo = null
                                            pairingExpiresAt = null
                                            pairingRemainingSeconds = 0
                                            onShowMessage("已立即作废配对码")
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "作废配对码",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    text = "⚠️ 配对码已失效过期，请重新获取",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        pairingCodeInfo?.let { client.pairingCancel(it) }
                                        pairingCodeInfo = null
                                        pairingExpiresAt = null
                                        pairingRemainingSeconds = 0
                                    }
                                }) {
                                    Text("清除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("局域网 (LAN) 监听服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    StatusBadge(status = if (lanConfig.enabled) "LISTENING" else "DISABLED")
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    if (lanConfig.enabled)
                        "• Gateway 正在局域网开放安全 TLS 加密监听端口。\n• 外部电脑、平板或脚本可通过局域网 IP 与端口向网关发送受控请求。"
                    else
                        "• 局域网监听默认关闭。开启后，网关将使用 TLS 加密开放局域网 REST API，允许同一局域网内的设备配对并接入管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (lanConfig.enabled) "局域网监听服务已开启" else "开启局域网监听服务", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (lanConfig.enabled) "运行端口: ${lanConfig.port} (TLS 加密)" else "开启时会自动配置自签 TLS 证书并重启网关", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = lanConfig.enabled,
                        enabled = !isLanUpdating,
                        onCheckedChange = { enable ->
                            scope.launch {
                                isLanUpdating = true
                                val port = lanPortInput.toIntOrNull() ?: 8443
                                client.updateLanConfig(enable, port).fold(
                                    onSuccess = { msg ->
                                        onShowMessage(msg)
                                        loadLanConfig()
                                    },
                                    onFailure = { onShowMessage("更新 LAN 配置失败: ${it.message}") }
                                )
                                isLanUpdating = false
                            }
                        }
                    )
                }

                if (lanConfig.enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("本机可用局域网访问地址:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isRefreshingIps = true
                                    deviceIps = client.getDeviceIpAddresses()
                                    isRefreshingIps = false
                                    onShowMessage(if (deviceIps.isEmpty()) "未检测到有效局域网 IP" else "已刷新检测到 ${deviceIps.size} 个有效 IP")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            if (isRefreshingIps) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新 IP", modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("刷新 IP", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (deviceIps.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            deviceIps.forEach { ip ->
                                val fullUrl = "https://$ip:${lanConfig.port}"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(fullUrl, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                                    IconButton(
                                        onClick = {
                                            clipboard.setPrimaryClip(ClipData.newPlainText("LAN API", fullUrl))
                                            onShowMessage("已复制地址: $fullUrl")
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Text("未检测到有效局域网 IP（请确认设备已连接 Wi-Fi 或热点）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = lanPortInput,
                            onValueChange = { lanPortInput = it },
                            label = { Text("监听端口") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            enabled = !isLanUpdating && lanPortInput.toIntOrNull() != null && lanPortInput.toInt() != lanConfig.port,
                            onClick = {
                                scope.launch {
                                    isLanUpdating = true
                                    val port = lanPortInput.toIntOrNull() ?: 8443
                                    client.updateLanConfig(true, port).fold(
                                        onSuccess = { msg ->
                                            onShowMessage(msg)
                                            loadLanConfig()
                                        },
                                        onFailure = { onShowMessage("更新端口失败: ${it.message}") }
                                    )
                                    isLanUpdating = false
                                }
                            }
                        ) {
                            Text("保存端口")
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    text = stringResource(R.string.security_clients_section, clientsList.size),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { loadClients() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = stringResource(R.string.status_loading)) }
        } else if (clientsList.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.security_clients_empty),
                    message = "本地默认 root 进程直接通过 Unix socket 连接。"
                )
            }
        } else {
            items(clientsList, key = { it.id }) { c ->
                ContentCard(
                    modifier = if (c.isCurrent) {
                        Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (c.isCurrent) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "当前本机设备",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Text("ID: ${c.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(status = if (c.enabled) "ACTIVE" else "REVOKED")
                    }
                    Spacer(Modifier.height(4.dp))
                    InfoRow(label = "注册时间", value = formatEpochTime(c.createdAt))
                    c.lastUsedAt?.let { InfoRow(label = "最近活跃", value = formatEpochTime(it)) }

                    Spacer(Modifier.height(8.dp))
                    // 操作按钮组第一行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedClientForGrants = c
                                scope.launch {
                                    client.grants(c.id, bearer).fold(
                                        onSuccess = { clientGrantsList = parseGrants(it) },
                                        onFailure = { onShowMessage("加载 Scope 失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scope 授权", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = { selectedClientForAcl = c },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Remote ACL", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.rotateToken(c.id, bearer).fold(
                                        onSuccess = {
                                            val newToken = JSONObject(it).optString("token")
                                            if (c.isCurrent) {
                                                tokenStore.write(newToken)
                                                onTokenUpdated(newToken)
                                            }
                                            rotatedTokenDialog = Pair(c.name, newToken)
                                        },
                                        onFailure = { onShowMessage("轮换失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("轮换 Token", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // 操作按钮组第二行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (c.isCurrent) {
                                    onShowMessage("无法禁用当前正在使用的本机控制端")
                                } else {
                                    scope.launch {
                                        val action = if (c.enabled) client.disableClient(c.id, bearer) else client.enableClient(c.id, bearer)
                                        action.fold(
                                            onSuccess = {
                                                onShowMessage(if (c.enabled) "已禁用该控制端" else "已重新启用该控制端")
                                                loadClients()
                                            },
                                            onFailure = { onShowMessage("操作失败: ${it.message}") }
                                        )
                                    }
                                }
                            },
                            enabled = !c.isCurrent,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (c.isCurrent) "本机已连接" else if (c.enabled) "禁用" else "启用", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                if (c.isCurrent) {
                                    onShowMessage("无法删除当前正在使用的本机控制端")
                                } else {
                                    clientToDelete = c
                                }
                            },
                            enabled = !c.isCurrent,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    // Manual Pair Complete Dialog
    if (showPairingCompleteDialog) {
        var inputCode by remember { mutableStateOf(TextFieldValue("")) }
        var inputClientName by remember { mutableStateOf(TextFieldValue("Android Controller")) }
        var inputPublicKey by remember { mutableStateOf(TextFieldValue("android-local-key")) }
        var inputGrantAdmin by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPairingCompleteDialog = false },
            title = { Text("手动完成配对与令牌发放", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MaterialTextField(value = inputCode, onValueChange = { inputCode = it }, label = "8位一次性配对码")
                    MaterialTextField(value = inputClientName, onValueChange = { inputClientName = it }, label = "客户端标识名称")
                    MaterialTextField(value = inputPublicKey, onValueChange = { inputPublicKey = it }, label = "公钥 / 设备签名指纹")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("授予管理员特权", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("允许导出明文凭据与清空审计日志", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = inputGrantAdmin, onCheckedChange = { inputGrantAdmin = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = inputCode.text.trim()
                        val name = inputClientName.text.trim().ifBlank { "Android Controller" }
                        val pubKey = inputPublicKey.text.trim().ifBlank { "device" }
                        if (code.length == 8 || code.length == 6) {
                            scope.launch {
                                client.pairingComplete(code, name, pubKey, inputGrantAdmin).fold(
                                    onSuccess = { res ->
                                        val json = JSONObject(res)
                                        val token = json.optString("token")
                                        tokenStore.write(token)
                                        onTokenUpdated(token)
                                        if (code == pairingCodeInfo) {
                                            pairingCodeInfo = null
                                            pairingExpiresAt = null
                                            pairingRemainingSeconds = 0
                                        }
                                        onShowMessage(if (inputGrantAdmin) "配对成功！已获取管理员特权令牌" else "配对成功！已获取常规权限令牌")
                                        showPairingCompleteDialog = false
                                        loadClients()
                                    },
                                    onFailure = { onShowMessage("配对失败: ${it.message}") }
                                )
                            }
                        } else {
                            onShowMessage("请输入有效的一次性配对码（8位）")
                        }
                    }
                ) {
                    Text("提交配对")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairingCompleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAutoPairAdminDialog) {
        AlertDialog(
            onDismissRequest = { showAutoPairAdminDialog = false },
            title = { Text("配对授权确认", fontWeight = FontWeight.Bold) },
            text = {
                Text("是否授予本地伴侣端管理员权限？\n\n• 常规权限：允许文件、远端、挂载管理与任务调度（推荐日常使用）。\n• 管理员特权：额外允许导出云存储明文密钥和清空审计日志。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAutoPairAdminDialog = false
                        scope.launch {
                            client.autoPair(grantAdmin = true).fold(
                                onSuccess = { token ->
                                    tokenStore.write(token)
                                    onTokenUpdated(token)
                                    onShowMessage("配对成功！已获取管理员特权令牌并加密生效")
                                    loadClients()
                                },
                                onFailure = { onShowMessage("自动配对失败: ${it.message}") }
                            )
                        }
                    }
                ) {
                    Text("授予管理员特权")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showAutoPairAdminDialog = false }) {
                        Text("取消")
                    }
                    OutlinedButton(
                        onClick = {
                            showAutoPairAdminDialog = false
                            scope.launch {
                                client.autoPair(grantAdmin = false).fold(
                                    onSuccess = { token ->
                                        tokenStore.write(token)
                                        onTokenUpdated(token)
                                        onShowMessage("配对成功！已获取常规权限令牌并加密生效")
                                        loadClients()
                                    },
                                    onFailure = { onShowMessage("自动配对失败: ${it.message}") }
                                )
                            }
                        }
                    ) {
                        Text("仅常规权限")
                    }
                }
            }
        )
    }

    // Delete Client Confirmation Dialog
    clientToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { clientToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除控制端", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要彻底删除控制端「${target.name}」吗？\n\n删除后该控制端的 Token 凭证及所有 Scope、ACL 权限记录将被彻底清除，操作不可逆。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.id
                        clientToDelete = null
                        scope.launch {
                            client.deleteClient(id, bearer).fold(
                                onSuccess = {
                                    onShowMessage("已成功删除控制端「${target.name}」")
                                    loadClients()
                                },
                                onFailure = { onShowMessage("删除失败: ${it.message}") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { clientToDelete = null }) { Text("取消") }
            }
        )
    }

    // Scope Grants Dialog (Single Unified Dialog, No Nesting Issues)
    selectedClientForGrants?.let { currentClient ->
        var showAddSection by remember { mutableStateOf(false) }
        var selectedScope by remember { mutableStateOf("file.write") }
        var resourceInput by remember { mutableStateOf(TextFieldValue("*")) }
        val commonScopes = listOf("*", "remote.read", "remote.write", "remote.delete", "file.read", "file.write", "file.delete", "security.write", "job.execute", "mount.write")

        AlertDialog(
            onDismissRequest = { selectedClientForGrants = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Scope 授权: ${currentClient.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showAddSection = !showAddSection }) {
                        Icon(
                            if (showAddSection) Icons.Default.Block else Icons.Default.Add,
                            contentDescription = "切换添加面板",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Inline Add Section
                    if (showAddSection) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("➕ 分配新权限", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text("选择预设 Scope：", style = MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    commonScopes.forEach { sc ->
                                        FilterChip(
                                            selected = selectedScope == sc,
                                            onClick = { selectedScope = sc },
                                            label = { Text(sc, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                MaterialTextField(value = resourceInput, onValueChange = { resourceInput = it }, label = "作用资源 (默认 *)")
                                Button(
                                    onClick = {
                                        val res = resourceInput.text.trim().ifBlank { "*" }
                                        scope.launch {
                                            client.grant(currentClient.id, selectedScope, res, bearer).fold(
                                                onSuccess = {
                                                    onShowMessage("授权成功")
                                                    showAddSection = false
                                                    client.grants(currentClient.id, bearer).onSuccess {
                                                        clientGrantsList = parseGrants(it)
                                                    }
                                                },
                                                onFailure = { onShowMessage("授权失败: ${it.message}") }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("确认添加权限")
                                }
                            }
                        }
                    }

                    // Grants List
                    if (clientGrantsList.isEmpty()) {
                        Text("该客户端目前没有任何独立 Scope 授权。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(240.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(clientGrantsList) { g ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(g.scope, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                            Text("资源: ${g.resource}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedClientForGrants = null }) { Text("完成") }
            }
        )
    }

    // Remote ACL Dialog
    selectedClientForAcl?.let { targetClient ->
        var remotesList by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
        var selectedRemoteId by remember { mutableStateOf("") }
        var isRemotesLoading by remember { mutableStateOf(true) }
        var permissions by remember { mutableStateOf(TextFieldValue("file.read,file.write")) }
        var prefix by remember { mutableStateOf(TextFieldValue("/")) }

        LaunchedEffect(targetClient) {
            isRemotesLoading = true
            client.remotes(bearer).fold(
                onSuccess = {
                    val parsed = parseRemotes(it)
                    remotesList = parsed
                    if (parsed.isNotEmpty()) {
                        selectedRemoteId = parsed[0].id
                    }
                },
                onFailure = { onShowMessage("加载远端列表失败: ${it.message}") }
            )
            isRemotesLoading = false
        }

        AlertDialog(
            onDismissRequest = { selectedClientForAcl = null },
            title = { Text("配置 Remote ACL: ${targetClient.name}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isRemotesLoading) {
                        Text("正在加载可用远端…")
                    } else if (remotesList.isEmpty()) {
                        Text("尚未创建任何远端，请先前往「远端」页面添加存储配置。", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("选择目标远端：", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            remotesList.forEach { r ->
                                FilterChip(
                                    selected = selectedRemoteId == r.id,
                                    onClick = { selectedRemoteId = r.id },
                                    label = { Text("${r.name} (${r.type})") }
                                )
                            }
                        }
                    }

                    MaterialTextField(value = permissions, onValueChange = { permissions = it }, label = "权限列表 (逗号分隔: file.read,file.write,file.delete,*)")
                    MaterialTextField(value = prefix, onValueChange = { prefix = it }, label = "允许路径前缀 (如 / 或 /photos)")
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedRemoteId.isNotBlank(),
                    onClick = {
                        val perms = permissions.text.trim()
                        val pfx = prefix.text.trim().ifBlank { "/" }
                        if (selectedRemoteId.isNotBlank() && perms.isNotBlank()) {
                            scope.launch {
                                client.remoteAcl(targetClient.id, selectedRemoteId, perms, pfx, bearer).fold(
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

    // Rotated Token Dialog
    rotatedTokenDialog?.let { (clientName, newToken) ->
        AlertDialog(
            onDismissRequest = { rotatedTokenDialog = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Token 轮换成功", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("控制端「$clientName」的新 Bearer 令牌已生成：")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = newToken,
                            modifier = Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("⚠️ 令牌明文仅显示一次，请妥善保存。如果是本机控制端已自动存入 Keystore。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Rclone Gateway Token", newToken))
                        onShowMessage("已复制令牌到剪贴板")
                        rotatedTokenDialog = null
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制并关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = { rotatedTokenDialog = null }) { Text("关闭") }
            }
        )
    }
}
