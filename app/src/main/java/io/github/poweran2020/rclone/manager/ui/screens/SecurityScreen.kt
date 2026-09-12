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
                onFailure = { onShowMessage(context.getString(R.string.sec_msg_clients_failed, it.message ?: "")) }
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
                        stringResource(R.string.security_local_paired_desc) +
                        (if (currentClient != null) "\n" + stringResource(R.string.security_client_identity, currentClient.name, currentClient.id.take(8)) else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showRePairOptions = !showRePairOptions }) {
                            Icon(if (showRePairOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (showRePairOptions) stringResource(R.string.security_re_pair_collapse) else stringResource(R.string.security_re_pair_expand))
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
                            Text(stringResource(R.string.security_btn_re_pair))
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
                    stringResource(R.string.security_ext_clients_desc),
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
                                        onShowMessage("Code: $code ($expiresIn s)")
                                    },
                                    onFailure = { onShowMessage("${context.getString(R.string.status_failed)}: ${it.message}") }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.security_get_pairing_code), maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { showPairingCompleteDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.security_btn_manual_pair), maxLines = 1, softWrap = false)
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
                                        Text(stringResource(R.string.security_current_pairing_code), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = code,
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                            fontWeight = FontWeight.Bold,
                                            color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.security_pairing_countdown, pairingRemainingSeconds),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isUrgent) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Pairing Code", code))
                                        onShowMessage(context.getString(R.string.security_copied_code, code))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            client.pairingCancel(code)
                                            pairingCodeInfo = null
                                            pairingExpiresAt = null
                                            pairingRemainingSeconds = 0
                                            onShowMessage(context.getString(R.string.security_revoked_code))
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.security_revoke_code),
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
                                    text = stringResource(R.string.security_pairing_expired),
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
                                    Text(stringResource(R.string.security_btn_clear_code), color = MaterialTheme.colorScheme.error)
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
                        Text(stringResource(R.string.security_lan_section_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    StatusBadge(status = if (lanConfig.enabled) "LISTENING" else "DISABLED")
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    if (lanConfig.enabled)
                        stringResource(R.string.security_lan_desc_enabled)
                    else
                        stringResource(R.string.security_lan_desc_disabled),
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
                        Text(if (lanConfig.enabled) stringResource(R.string.security_lan_switch_on) else stringResource(R.string.security_lan_switch_off), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (lanConfig.enabled) stringResource(R.string.security_lan_running_port, lanConfig.port) else stringResource(R.string.security_lan_auto_tls_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    onFailure = { onShowMessage(it.message ?: "") }
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
                        Text(stringResource(R.string.security_lan_addresses_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isRefreshingIps = true
                                    deviceIps = client.getDeviceIpAddresses()
                                    isRefreshingIps = false
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            if (isRefreshingIps) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.security_lan_refresh_ips), modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.security_lan_refresh_ips), style = MaterialTheme.typography.labelSmall)
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
                                            onShowMessage(context.getString(R.string.security_lan_copied_addr, fullUrl))
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.security_lan_no_ips_warn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                            label = { Text(stringResource(R.string.security_lan_listen_port)) },
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
                                        onFailure = { onShowMessage(it.message ?: "") }
                                    )
                                    isLanUpdating = false
                                }
                            }
                        ) {
                            Text(stringResource(R.string.security_lan_save_port))
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
                    message = stringResource(R.string.security_clients_empty_desc)
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
                                            stringResource(R.string.security_client_current_device),
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
                    InfoRow(label = stringResource(R.string.security_client_created_at), value = formatEpochTime(c.createdAt))
                    c.lastUsedAt?.let { InfoRow(label = stringResource(R.string.security_client_last_used), value = formatEpochTime(it)) }

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
                                        onFailure = { onShowMessage(it.message ?: "") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.security_btn_scope_grants), style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = { selectedClientForAcl = c },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.security_btn_remote_acl), style = MaterialTheme.typography.labelSmall)
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
                                        onFailure = { onShowMessage(it.message ?: "") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.security_btn_rotate_token), style = MaterialTheme.typography.labelSmall)
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
                                    return@OutlinedButton
                                } else {
                                    scope.launch {
                                        val action = if (c.enabled) client.disableClient(c.id, bearer) else client.enableClient(c.id, bearer)
                                        action.fold(
                                            onSuccess = {
                                                loadClients()
                                            },
                                            onFailure = { onShowMessage(it.message ?: "") }
                                        )
                                    }
                                }
                            },
                            enabled = !c.isCurrent,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (c.isCurrent) stringResource(R.string.security_client_connected) else if (c.enabled) stringResource(R.string.security_btn_disable) else stringResource(R.string.security_btn_enable), style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                if (c.isCurrent) {
                                    return@OutlinedButton
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
                            Text(stringResource(R.string.security_btn_delete), style = MaterialTheme.typography.labelSmall)
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
            title = { Text(stringResource(R.string.security_dialog_pair_manual_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MaterialTextField(value = inputCode, onValueChange = { inputCode = it }, label = stringResource(R.string.security_dialog_code_label))
                    MaterialTextField(value = inputClientName, onValueChange = { inputClientName = it }, label = stringResource(R.string.security_dialog_client_name_label))
                    MaterialTextField(value = inputPublicKey, onValueChange = { inputPublicKey = it }, label = stringResource(R.string.security_dialog_public_key_label))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.security_dialog_grant_admin), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.security_dialog_grant_admin_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        showPairingCompleteDialog = false
                                        loadClients()
                                    },
                                    onFailure = { onShowMessage(it.message ?: "") }
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.security_btn_submit_pairing))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairingCompleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showAutoPairAdminDialog) {
        AlertDialog(
            onDismissRequest = { showAutoPairAdminDialog = false },
            title = { Text(stringResource(R.string.security_auth_confirm_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.security_auth_confirm_desc))
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
                                    loadClients()
                                },
                                onFailure = { onShowMessage(it.message ?: "") }
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.security_btn_grant_admin))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showAutoPairAdminDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    OutlinedButton(
                        onClick = {
                            showAutoPairAdminDialog = false
                            scope.launch {
                                client.autoPair(grantAdmin = false).fold(
                                    onSuccess = { token ->
                                        tokenStore.write(token)
                                        onTokenUpdated(token)
                                        loadClients()
                                    },
                                    onFailure = { onShowMessage(it.message ?: "") }
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.security_btn_standard_only))
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
            title = { Text(stringResource(R.string.security_delete_client_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.security_delete_client_desc, target.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.id
                        clientToDelete = null
                        scope.launch {
                            client.deleteClient(id, bearer).fold(
                                onSuccess = {
                                    loadClients()
                                },
                                onFailure = { onShowMessage(it.message ?: "") }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.security_btn_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { clientToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
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
                    Text(stringResource(R.string.security_scope_grants_title, currentClient.name), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showAddSection = !showAddSection }) {
                        Icon(
                            if (showAddSection) Icons.Default.Block else Icons.Default.Add,
                            contentDescription = stringResource(R.string.security_scope_toggle_panel),
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
                                Text(stringResource(R.string.security_scope_assign_new), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.security_scope_select_preset), style = MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    commonScopes.forEach { sc ->
                                        FilterChip(
                                            selected = selectedScope == sc,
                                            onClick = { selectedScope = sc },
                                            label = { Text(sc, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                MaterialTextField(value = resourceInput, onValueChange = { resourceInput = it }, label = stringResource(R.string.security_scope_target_resource))
                                Button(
                                    onClick = {
                                        val res = resourceInput.text.trim().ifBlank { "*" }
                                        scope.launch {
                                            client.grant(currentClient.id, selectedScope, res, bearer).fold(
                                                onSuccess = {
                                                    showAddSection = false
                                                    client.grants(currentClient.id, bearer).onSuccess {
                                                        clientGrantsList = parseGrants(it)
                                                    }
                                                },
                                                onFailure = { onShowMessage(it.message ?: "") }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.security_scope_confirm_add))
                                }
                            }
                        }
                    }

                    // Grants List
                    if (clientGrantsList.isEmpty()) {
                        Text(stringResource(R.string.security_scope_empty), style = MaterialTheme.typography.bodyMedium)
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
                                            Text(stringResource(R.string.security_scope_resource_prefix, g.resource), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    client.revokeGrant(currentClient.id, g.id, bearer).fold(
                                                        onSuccess = {
                                                            client.grants(currentClient.id, bearer).onSuccess {
                                                                clientGrantsList = parseGrants(it)
                                                            }
                                                        },
                                                        onFailure = { onShowMessage(it.message ?: "") }
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
            },
            confirmButton = {
                TextButton(onClick = { selectedClientForGrants = null }) { Text(stringResource(R.string.security_btn_finish)) }
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

        var permissionsError by remember { mutableStateOf<String?>(null) }

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
                onFailure = { onShowMessage(it.message ?: "") }
            )
            isRemotesLoading = false
        }

        AlertDialog(
            onDismissRequest = { selectedClientForAcl = null },
            title = { Text(stringResource(R.string.security_acl_title, targetClient.name), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isRemotesLoading) {
                        Text(stringResource(R.string.security_acl_loading_remotes))
                    } else if (remotesList.isEmpty()) {
                        Text(stringResource(R.string.security_acl_no_remotes), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.security_acl_select_remote), style = MaterialTheme.typography.labelSmall)
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

                    MaterialTextField(
                        value = permissions,
                        onValueChange = {
                            permissions = it
                            permissionsError = null
                        },
                        label = stringResource(R.string.security_acl_permissions_label),
                        isError = permissionsError != null,
                        supportingText = {
                            if (permissionsError != null) {
                                Text(permissionsError!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                    MaterialTextField(value = prefix, onValueChange = { prefix = it }, label = stringResource(R.string.security_acl_prefix_label))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val perms = permissions.text.trim()
                        val pfx = prefix.text.trim().ifBlank { "/" }
                        if (selectedRemoteId.isBlank()) {
                            return@Button
                        }
                        if (perms.isBlank()) {
                            return@Button
                        }
                        scope.launch {
                            client.remoteAcl(targetClient.id, selectedRemoteId, perms, pfx, bearer).fold(
                                onSuccess = {
                                    selectedClientForAcl = null
                                },
                                onFailure = { onShowMessage(it.message ?: "") }
                            )
                        }
                    }
                ) { Text(stringResource(R.string.security_btn_save_acl)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedClientForAcl = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // Rotated Token Dialog
    rotatedTokenDialog?.let { (clientName, newToken) ->
        AlertDialog(
            onDismissRequest = { rotatedTokenDialog = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.security_token_rotated_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.security_token_rotated_desc, clientName))
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
                    Text(stringResource(R.string.security_token_rotated_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Rclone Gateway Token", newToken))
                        onShowMessage(context.getString(R.string.token_saved_copied))
                        rotatedTokenDialog = null
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.security_btn_copy_and_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { rotatedTokenDialog = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}
