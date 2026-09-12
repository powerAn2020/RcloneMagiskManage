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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.R
import io.github.poweran2020.rclone.manager.data.model.CryptProfileItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.parseCrypts
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
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
                SectionTitle(
                    text = stringResource(R.string.crypt_list_title, crypts.size),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { loadCrypts() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.crypt_btn_new), maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        item {
            ContentCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.crypt_info_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    stringResource(R.string.crypt_info_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isLoading) {
            item { LoadingView(message = stringResource(R.string.crypt_loading)) }
        } else if (crypts.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.crypt_empty_title),
                    message = stringResource(R.string.crypt_empty_desc)
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
                    InfoRow(label = stringResource(R.string.crypt_underlying_remote), value = remoteDisplayName)
                    InfoRow(label = stringResource(R.string.crypt_underlying_path), value = crypt.remotePath)
                    InfoRow(label = stringResource(R.string.crypt_password_status), value = if (crypt.passwordConfigured) stringResource(R.string.crypt_pass_encrypted) else stringResource(R.string.crypt_pass_not_set))

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
                        Text(stringResource(R.string.crypt_btn_test))
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCryptDialog(
            remotes = remotes,
            onDismiss = { showCreateDialog = false },
            onSubmit = { name, remoteId, remotePath, password, onError ->
                scope.launch {
                    client.createCrypt(name, remoteId, remotePath.ifBlank { null }, password.ifBlank { null }, bearer).fold(
                        onSuccess = {
                            onShowMessage("加密档案创建成功")
                            showCreateDialog = false
                            loadCrypts()
                        },
                        onFailure = {
                            val msg = it.message ?: "创建失败"
                            onShowMessage("创建失败: $msg")
                            onError(msg)
                        }
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
    onSubmit: (name: String, remoteId: String, remotePath: String, password: String, onError: (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var selectedRemoteId by remember { mutableStateOf(remotes.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(TextFieldValue("/")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var remoteDropdownExpanded by remember { mutableStateOf(false) }

    var nameError by remember { mutableStateOf<String?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(stringResource(R.string.crypt_dialog_create_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                MaterialTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                        dialogError = null
                    },
                    label = stringResource(R.string.crypt_profile_name_label) + " *",
                    isError = nameError != null,
                    supportingText = {
                        if (nameError != null) {
                            Text(nameError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("用于加密映射的英文标识符 (必填)")
                        }
                    }
                )

                ExposedDropdownMenuBox(
                    expanded = remoteDropdownExpanded,
                    onExpandedChange = {
                        if (!isSubmitting) remoteDropdownExpanded = !remoteDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentRemoteName = remotes.find { it.id == selectedRemoteId }?.name
                        ?: if (remotes.isEmpty()) "无可用远端，请先添加远端" else stringResource(R.string.crypt_select_remote_placeholder)
                    OutlinedTextField(
                        value = currentRemoteName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.crypt_underlying_remote) + " *") },
                        isError = remoteError != null,
                        supportingText = {
                            if (remoteError != null) {
                                Text(remoteError!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("被加密的目标底层云存储")
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitting
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
                                    remoteError = null
                                    dialogError = null
                                }
                            )
                        }
                    }
                }

                MaterialTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = stringResource(R.string.crypt_base_path_label)
                )

                MaterialTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordError = null
                        dialogError = null
                    },
                    label = stringResource(R.string.crypt_password_label) + " *",
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordError != null,
                    supportingText = {
                        if (passwordError != null) {
                            Text(passwordError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("端到端 AES 加密主密码 (必填)")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    nameError = null
                    remoteError = null
                    passwordError = null
                    dialogError = null

                    val n = name.text.trim()
                    val p = password.text.trim()
                    var hasError = false

                    if (n.isBlank()) {
                        nameError = "加密档案名称不能为空"
                        hasError = true
                    } else if (!n.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                        nameError = "档案名称仅允许字母、数字、下划线及连字符"
                        hasError = true
                    }

                    if (remotes.isEmpty()) {
                        remoteError = "尚未配置底层存储远端，请先前往「远端」页面添加"
                        hasError = true
                    } else if (selectedRemoteId.isBlank()) {
                        remoteError = "请选择一个底层存储远端"
                        hasError = true
                    } else {
                        val parentRemote = remotes.find { it.id == selectedRemoteId }
                        if (parentRemote != null && parentRemote.name == n) {
                            nameError = "加密档案名称不能与底层远端名称 (${parentRemote.name}) 相同"
                            hasError = true
                        }
                    }

                    if (p.isBlank()) {
                        passwordError = "加密密码不能为空，用于派生端到端加密密钥"
                        hasError = true
                    }

                    if (hasError) {
                        dialogError = "表单存在未填写或格式错误的必填项，请检查标红提示"
                        return@Button
                    }

                    isSubmitting = true
                    onSubmit(
                        n,
                        selectedRemoteId,
                        remotePath.text.trim().ifBlank { "/" },
                        p,
                        { errMsg ->
                            isSubmitting = false
                            dialogError = errMsg
                        }
                    )
                },
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
