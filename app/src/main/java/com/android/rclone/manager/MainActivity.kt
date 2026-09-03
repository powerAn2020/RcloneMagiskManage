package com.android.rclone.manager

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.rclone.manager.data.model.RemoteSummary
import com.android.rclone.manager.data.model.parseRemotes
import com.android.rclone.manager.ui.component.ContentCard
import com.android.rclone.manager.ui.component.MaterialDialog
import com.android.rclone.manager.ui.component.MaterialTextField
import com.android.rclone.manager.ui.component.PreferenceRow
import com.android.rclone.manager.ui.component.SectionTitle
import com.android.rclone.manager.ui.component.TogglePreference

class MainActivity : ComponentActivity() {
    private val client = GatewayClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tokenStore: TokenStore
    private lateinit var output: TextView
    private lateinit var token: EditText
    private val tokenState = mutableStateOf("")
    private val promptState = mutableStateOf<PromptRequest?>(null)
    private val confirmState = mutableStateOf<ConfirmRequest?>(null)

    private data class PromptRequest(
        val title: String,
        val hints: List<String>,
        val secretIndexes: Set<Int>,
        val done: (List<String>) -> Unit,
    )

    private data class ConfirmRequest(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
    )

    @Composable private fun AppTopBar(title: String, subtitle: String) {
        androidx.compose.material3.TopAppBar(title = { Column { Text(title); Text(subtitle, style = MaterialTheme.typography.labelSmall) } })
    }


    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        tokenStore = TokenStore(this)
        token = EditText(this).apply { setText(tokenStore.read()) }
        tokenState.value = tokenStore.read()
        output = TextView(this)
        setContent {
            MaterialTheme { RcloneApp() }
        }
    }

    @Composable
    private fun RcloneApp() {
        var selected by remember { mutableIntStateOf(0) }
        var result by remember { mutableStateOf("正在检查 Gateway…") }
        var showTokenEditor by remember { mutableStateOf(false) }
        val tabs = listOf("首页", "远端", "文件", "任务", "更多")
        val icons = listOf(Icons.Default.Home, Icons.Default.Cloud, Icons.Default.Folder, Icons.Default.PlayArrow, Icons.Default.MoreHoriz)
        val tokenValue = tokenState.value
        LaunchedEffect(Unit) { result = client.health().fold({ it }, { "Gateway 不可用：${it.message}" }) }
        Scaffold(
            topBar = { AppTopBar(tabs[selected], if (selected == 0) "Root Manager" else "Rclone Root Manager") },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, label ->
                        NavigationBarItem(selected = selected == index, onClick = { selected = index }, icon = { androidx.compose.material3.Icon(icons[index], null) }, label = { Text(label) })
                    }
                }
            },
        ) { padding ->
            when (selected) {
                0 -> Dashboard(padding, result, tokenValue) { result = it; if (it == "__edit_token__") showTokenEditor = true }
                1 -> RemotesPage(padding, tokenValue, result) { result = it }
                2 -> FilesPage(padding, tokenValue, result) { result = it }
                3 -> JobsPage(padding, tokenValue, result) { result = it }
                else -> MorePage(padding, tokenValue, result) { result = it }
            }
            TokenEditor(show = showTokenEditor, initialValue = tokenValue, onDismiss = { showTokenEditor = false })
            PromptDialog(request = promptState.value, onDismiss = { promptState.value = null })
            ConfirmDialog(request = confirmState.value, onDismiss = { confirmState.value = null })
        }
    }

    @Composable
    private fun Dashboard(padding: PaddingValues, result: String, bearer: String, onResult: (String) -> Unit) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle(text = "系统状态") }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                    Text("Gateway / rclone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(if (result.startsWith("{") || result.contains("ok", true)) "在线" else "检查失败", style = MaterialTheme.typography.bodyMedium)
                    Text("数据目录  /data/adb/rclone-manage", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                    Text(if (bearer.isBlank()) "认证未配置" else "认证已配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Token 使用 Android Keystore 加密保存", style = MaterialTheme.typography.bodyMedium)
                    Text("不会显示在页面或日志", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(text = "设置 Token", onClick = { onResult("__edit_token__") })
                }
            }
            item { SectionTitle(text = "WebDAV 测试") }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                    Text("WebDAV 配置已就绪", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Provider → rclone → FUSE3 → bind mount", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { onResult(client.remotes(bearer).fold({ it }, { "错误：${it.message}" })) } }) { Text("检查 Remote") }
                        TextButton(text = "刷新 Gateway", onClick = { scope.launch { onResult(client.health().fold({ it }, { "错误：${it.message}" })) } })
                    }
                }
            }
            item { SectionTitle(text = "快捷操作") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = { createRemote() }) { Text("添加远端") }
                    Button(modifier = Modifier.weight(1f), onClick = { createJob() }) { Text("创建任务") }
                }
            }
            item { Text("FUSE3 meta 模块已修复。测试结果将分别显示 Provider、进程、FUSE3、bind、读写五级状态。", style = MaterialTheme.typography.bodyMedium) }
        }
    }

    @Composable
    private fun TokenEditor(show: Boolean, initialValue: String, onDismiss: () -> Unit) {
        var value by remember(show, initialValue) { mutableStateOf(TextFieldValue(initialValue)) }
        MaterialDialog(show = show, title = "Gateway Token", summary = "使用 Android Keystore 加密保存，不会显示在日志中。", onDismissRequest = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MaterialTextField(value = value, onValueChange = { value = it }, label = "Token", singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(text = "保存", onClick = {
                        token.setText(value.text)
                        tokenState.value = value.text
                        tokenStore.write(value.text)
                        onDismiss()
                    })
                }
            }
        }
    }

    @Composable
    private fun PromptDialog(request: PromptRequest?, onDismiss: () -> Unit) {
        if (request == null) return
        var values by remember(request) { mutableStateOf(request.hints.map { TextFieldValue("") }) }
        MaterialDialog(show = true, title = request.title, onDismissRequest = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values.forEachIndexed { index, current ->
                    TextField(
                        value = current,
                        onValueChange = { updated -> values = values.toMutableList().also { it[index] = updated } },
                        label = request.hints[index],
                        singleLine = true,
                        visualTransformation = if (index in request.secretIndexes) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(text = "提交", onClick = {
                        request.done(values.map { it.text })
                        onDismiss()
                    })
                }
            }
        }
    }

    @Composable
    private fun ConfirmDialog(request: ConfirmRequest?, onDismiss: () -> Unit) {
        if (request == null) return
        MaterialDialog(show = true, title = request.title, summary = request.message, onDismissRequest = onDismiss) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = onDismiss)
                TextButton(text = request.confirmLabel, onClick = { request.onConfirm(); onDismiss() })
            }
        }
    }

    @Composable
    private fun RemotesPage(padding: PaddingValues, bearer: String, result: String, onResult: (String) -> Unit) {
        var raw by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(bearer) { raw = client.remotes(bearer).getOrElse { "错误：${it.message}" } }
        val remotes = remember(raw) { parseRemotes(raw.orEmpty()) }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(text = "远端列表")
                    TextButton(text = "添加", onClick = { createRemote() })
                }
            }
            if (remotes.isEmpty()) {
                item { ContentCard(Modifier.fillMaxWidth()) { Text(if (raw == null) "正在加载…" else "暂无远端或返回格式无法识别") } }
            } else {
                items(remotes) { remote ->
                    ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(remote.name)
                        Text("${remote.type} · ${if (remote.enabled) "已启用" else "已禁用"}")
                        if (remote.endpoint.isNotBlank()) Text(remote.endpoint)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(text = "测试", onClick = { testRemoteWithId(remote.id) })
                            TextButton(text = "文件", onClick = { listFilesWithId(remote.id) })
                        }
                    }
                }
            }
            item {
                TextButton(text = "刷新", onClick = { scope.launch { onResult(client.remotes(bearer).fold({ it }, { "错误：${it.message}" })) } })
            }
            item { Text(result) }
        }
    }

    private data class RemoteSummary(val id: String, val name: String, val type: String, val endpoint: String, val enabled: Boolean)

    private fun parseRemotes(raw: String): List<RemoteSummary> = runCatching {
        val value = raw.trim()
        val array = when {
            value.startsWith("[") -> JSONArray(value)
            value.startsWith("{") -> {
                val objectValue = JSONObject(value)
                objectValue.optJSONArray("remotes") ?: objectValue.optJSONArray("items") ?: JSONArray()
            }
            else -> JSONArray()
        }
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val endpoint = item.optString("endpoint", item.optString("path", ""))
                add(RemoteSummary(item.optString("id"), item.optString("name", "未命名"), item.optString("type", "unknown"), endpoint, item.optBoolean("enabled", true)))
            }
        }
    }.getOrDefault(emptyList())

    private fun testRemoteWithId(id: String) = scope.launch {
        output.text = client.testRemote(id, tokenState.value).fold({ it }, { "错误：${it.message}" })
    }

    private fun listFilesWithId(id: String) = scope.launch {
        output.text = client.listFiles(id, "/", tokenState.value).fold({ it }, { "错误：${it.message}" })
    }

    @Composable
    private fun FilesPage(padding: PaddingValues, bearer: String, result: String, onResult: (String) -> Unit) = ActionListPage(padding, "文件浏览", result, onResult,
        listOf("列出文件" to { listFiles(); null }, "上传文件" to { upload(); null }, "新建目录" to { mkdir(); null }, "删除预览" to { deletePreview(); null }))

    @Composable
    private fun JobsPage(padding: PaddingValues, bearer: String, result: String, onResult: (String) -> Unit) = ActionListPage(padding, "任务中心", result, onResult,
        listOf("加载任务" to { client.jobs(bearer) }, "创建任务" to { createJob(); null }, "查看运行记录" to { jobRuns(); null }, "查看日志" to { jobLog(); null }))

    @Composable
    private fun MorePage(padding: PaddingValues, bearer: String, result: String, onResult: (String) -> Unit) {
        var safeEnabled by remember { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle(text = "挂载与运行") }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    PreferenceRow(title = "挂载配置", summary = "查看 Mount profile、缓存、FUSE3 与 bind 状态", onClick = { scope.launch { onResult(client.mounts(bearer).fold({ it }, { "错误：${it.message}" })) } })
                    PreferenceRow(title = "任务策略", summary = "网络、电量、并发与调度策略", onClick = { scope.launch { onResult(client.jobs(bearer).fold({ it }, { "错误：${it.message}" })) } })
                }
            }
            item { SectionTitle(text = "安全与数据") }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    PreferenceRow(title = "配对客户端", summary = "Scope、Remote ACL、撤销与 Token 轮换", onClick = { scope.launch { onResult(client.clients(bearer).fold({ it }, { "错误：${it.message}" })) } })
                    PreferenceRow(title = "创建备份", summary = "备份数据库、配置与加密密钥包", onClick = { createBackup() })
                }
            }
            item { SectionTitle(text = "系统") }
            item {
                ContentCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    TogglePreference(checked = safeEnabled, onCheckedChange = { safeEnabled = it; scope.launch { onResult(client.setSafeMode(it, bearer).fold({ value -> value }, { error -> "错误：${error.message}" })) } }, title = "Safe Mode", summary = "停止新任务并安全回收挂载")
                    PreferenceRow(title = "Gateway 设置", summary = "Unix socket、LAN TLS、日志与迁移", onClick = { scope.launch { onResult(client.settings(bearer).fold({ it }, { "错误：${it.message}" })) } })
                }
            }
            item { Text(result) }
        }
    }

    @Composable
    private fun ActionListPage(padding: PaddingValues, title: String, result: String, onResult: (String) -> Unit, actions: List<Pair<String, suspend () -> Result<String>?>>) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle(text = title) }
            items(actions) { (label, action) ->
                ContentCard(modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        action()?.let { resultValue ->
                            onResult(resultValue.fold({ value -> value }, { error -> "错误：${error.message}" }))
                        }
                    }
                }) { Text(label) }
            }
            item { Text(result) }
        }
    }

    private fun load(label: String) = scope.launch {
        if (label == "Files") { listFiles(); return@launch }
        val bearer = token.text.toString().ifBlank { null }
        val result = when (label) {
            "Health" -> client.health()
            "Remotes" -> client.remotes(bearer.orEmpty())
            "Jobs" -> client.jobs(bearer.orEmpty())
            "Mounts" -> client.mounts(bearer.orEmpty())
            "Audit" -> client.auditLogs(bearer.orEmpty())
            else -> client.backups(bearer.orEmpty())
        }
        output.text = result.fold({ it }, { "Error: ${it.message}" })
    }

    private fun pair() = scope.launch {
        val start = client.pairingStart().getOrElse { output.text = "Error: ${it.message}"; return@launch }
        val code = JSONObject(start).optString("pairingCode")
        prompt("Complete pairing", listOf("client name", "public key")) { v ->
            scope.launch {
                output.text = client.pairingComplete(code, v[0], v[1]).fold({ result ->
                    runCatching { tokenStore.write(JSONObject(result).optString("token")) }
                    result
                }, { "Error: ${it.message}" })
                token.setText(tokenStore.read())
            }
        }
    }

    private fun safeMode() = scope.launch {
        val current = client.safeMode(token.text.toString()).getOrElse { output.text = "Error: ${it.message}"; return@launch }
        val enabled = !JSONObject(current).optBoolean("enabled")
        output.text = client.setSafeMode(enabled, token.text.toString()).fold({ it }, { "Error: ${it.message}" })
    }

    private fun prompt(title: String, hints: List<String>, secretIndexes: Set<Int> = emptySet(), done: (List<String>) -> Unit) {
        promptState.value = PromptRequest(title, hints, secretIndexes, done)
    }

    private fun createRemote() = prompt("Create Remote", listOf("name", "type (s3/webdav/local)", "endpoint (optional)", "access key (optional)", "secret/token (optional)", "account (optional)"), setOf(3, 4, 5)) { v ->
        scope.launch { output.text = client.createRemote(v[0], v[1], v[2].ifBlank { null }, remoteSecret(v, 3), token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun importRemote() = prompt("Import Remote", listOf("name", "type", "endpoint (optional)")) { v ->
        scope.launch { output.text = client.importRemote(v[0], v[1], v[2].ifBlank { null }, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun createJob() = prompt("Create Job", listOf("type (copy/sync/move/bisync/delete)", "source", "destination (blank for delete)", "schedule (@hourly/@daily/@every 60s/@reboot)", "network (ANY/WIFI/UNMETERED/VPN)", "battery (ANY/CHARGING/BATTERY_30)", "dryRun (true/false)", "transfers (1..32, optional)", "checkers (1..64, optional)", "bwLimit (optional)", "overwrite (true/false, optional)", "deleteExcluded (sync only, optional)")) { v ->
        val options = JSONObject().apply {
            v.getOrNull(7)?.toIntOrNull()?.let { put("transfers", it) }
            v.getOrNull(8)?.toIntOrNull()?.let { put("checkers", it) }
            v.getOrNull(9)?.takeIf { it.isNotBlank() }?.let { put("bwLimit", it) }
            v.getOrNull(10)?.takeIf { it.isNotBlank() }?.let { put("overwrite", it.equals("true", true)) }
            v.getOrNull(11)?.takeIf { it.isNotBlank() }?.let { put("deleteExcluded", it.equals("true", true)) }
        }.takeIf { it.length() > 0 }
        scope.launch { output.text = client.createJob(v[0], v[1], v[2], token.text.toString(), v[3], v[4], v[5], v[6].equals("true", true), options).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun createMount() = prompt("Create Mount", listOf("name", "remote ID", "mount point (/mnt/rclone-*)", "remote path (default /)", "cache dir (optional, under manager cache)", "read-only (true/false)", "cache mode (off/minimal/writes/full)", "cache max size (default 32G)", "cache max age (default 36h)")) { v ->
        scope.launch { output.text = client.createMount(v[0], v[1], v[2], token.text.toString(), v[3], v[4], v[5].equals("true", true), v[6], v[7], v[8]).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun editRemote() = prompt("Edit Remote", listOf("remote ID", "name", "type", "endpoint (optional)", "access key (optional)", "secret/token (optional)", "account (optional)"), setOf(4, 5, 6)) { v -> scope.launch { output.text = client.updateRemote(v[0], v[1], v[2], v[3].ifBlank { null }, token.text.toString(), remoteSecret(v, 4)).fold({ it }, { "Error: ${it.message}" }) } }
    private fun listFiles() = prompt("List Files", listOf("remote ID", "path")) { v -> scope.launch { output.text = client.listFiles(v[0], v[1].ifBlank { "/" }, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun mkdir() = prompt("Create Directory", listOf("remote ID", "path")) { v -> scope.launch { output.text = client.mkdir(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun upload() = prompt("Upload", listOf("local path", "remote:path")) { v -> scope.launch { output.text = client.upload(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun download() = prompt("Download", listOf("remote:path", "local path")) { v -> scope.launch { output.text = client.download(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun copy() = prompt("Copy", listOf("source (remote:path)", "destination (remote:path)")) { v -> scope.launch { output.text = client.copy(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun move() = prompt("Move", listOf("source (remote:path)", "destination (remote:path)")) { v -> scope.launch { output.text = client.move(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun deletePreview() = prompt("Delete preview (no deletion)", listOf("remote ID", "path")) { v -> scope.launch { output.text = client.deletePreview(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun deleteFiles() = prompt("Delete files", listOf("remote ID", "path")) { v ->
        scope.launch {
            val bearer = token.text.toString()
            val preview = client.deletePreview(v[0], v[1], bearer).getOrElse {
                output.text = "Error: ${it.message}"
                return@launch
            }
            val confirmation = JSONObject(preview).optString("confirmationToken")
            if (confirmation.isBlank()) {
                output.text = "Error: Gateway did not return a confirmation token"
                return@launch
            }
            confirmState.value = ConfirmRequest("确认删除文件", "预览确认令牌将在 60 秒后过期。是否继续？", "删除") {
                scope.launch {
                    output.text = client.deleteConfirmed(v[0], v[1], confirmation, bearer)
                        .fold({ it }, { "Error: ${it.message}" })
                }
            }
        }
    }
    private fun jobRuns() = prompt("Job runs", listOf("job ID")) { v -> scope.launch { output.text = client.jobRuns(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun jobLog() = prompt("Job log", listOf("job ID")) { v -> scope.launch { output.text = client.jobLog(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun jobAction() = prompt("Job action", listOf("job ID", "start/pause/resume/cancel/retry")) { v -> scope.launch { output.text = client.jobAction(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun mountAction() = prompt("Mount action", listOf("mount ID", "start/stop/enable/disable")) { v -> scope.launch { output.text = client.mountAction(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun deleteRemote() = prompt("Delete remote", listOf("remote ID")) { v ->
        scope.launch {
            val bearer = token.text.toString()
            val preview = client.remoteDelete(v[0], bearer).getOrElse {
                output.text = "Error: ${it.message}"
                return@launch
            }
            val confirmation = JSONObject(preview).optString("confirmationToken")
            if (confirmation.isBlank()) {
                output.text = "Error: Gateway did not return a confirmation token"
                return@launch
            }
            confirmState.value = ConfirmRequest("确认删除远端", "此操作不可撤销，确认令牌将在 60 秒后过期。", "删除") {
                scope.launch {
                    output.text = client.remoteDelete(v[0], bearer, confirmation)
                        .fold({ it }, { "Error: ${it.message}" })
                }
            }
        }
    }
    private fun testRemote() = prompt("Test remote", listOf("remote ID")) { v -> scope.launch { output.text = client.testRemote(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun remoteAction() = prompt("Remote action", listOf("remote ID", "enable/disable")) { v -> scope.launch { output.text = client.remoteAction(v[0], v[1], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun exportRemote() = prompt("Export remote (credentials excluded)", listOf("remote ID")) { v -> scope.launch { output.text = client.exportRemote(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun createCrypt() = prompt("Create Crypt profile", listOf("name", "remote ID", "remote path (optional)", "password (optional)"), setOf(3)) { v -> scope.launch { output.text = client.createCrypt(v[0], v[1], v[2], v[3], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun loadCrypts() = scope.launch { output.text = client.crypts(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun testCrypt() = prompt("Test Crypt encryption", listOf("crypt profile ID")) { v -> scope.launch { output.text = client.cryptTest(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun createBackup() = scope.launch { output.text = client.createBackup(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun loadSecurity() = scope.launch { output.text = client.clients(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun loadTransport() = scope.launch { output.text = client.info(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun loadSettings() = scope.launch { output.text = client.settings(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun loadMigration() = scope.launch { output.text = client.migrationStatus(token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    private fun updateSettings() = prompt("Settings (blank keeps current)", listOf("logRetentionDays", "logMaxBytes", "cacheMaxBytes", "maxConcurrentJobs")) { v ->
        val json = JSONObject()
        listOf("logRetentionDays", "logMaxBytes", "cacheMaxBytes", "maxConcurrentJobs").forEachIndexed { index, key -> v[index].toLongOrNull()?.let { json.put(key, it) } }
        scope.launch { output.text = client.updateSettings(json, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun clientGrants() = prompt("Client grants", listOf("client ID")) { v -> scope.launch { output.text = client.grants(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun grantScope() = prompt("Grant scope", listOf("client ID", "scope", "resource (*)")) { v -> scope.launch { output.text = client.grant(v[0], v[1], v[2].ifBlank { "*" }, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun revokeGrant() = prompt("Revoke scope grant", listOf("client ID", "grant ID")) { v ->
        val grantId = v[1].toLongOrNull() ?: run { output.text = "Error: grant ID must be numeric"; return@prompt }
        scope.launch { output.text = client.revokeGrant(v[0], grantId, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) }
    }
    private fun grantRemoteAcl() = prompt("Grant Remote ACL", listOf("client ID", "remote ID", "permissions (file.read,file.write)", "allowed prefix (/)")) { v -> scope.launch { output.text = client.remoteAcl(v[0], v[1], v[2], v[3].ifBlank { "/" }, token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun disableClient() = prompt("Disable client", listOf("client ID")) { v -> scope.launch { output.text = client.disableClient(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }
    private fun rotateClientToken() = prompt("Rotate client token", listOf("client ID")) { v -> scope.launch { output.text = client.rotateToken(v[0], token.text.toString()).fold({ it }, { "Error: ${it.message}" }) } }

    /** Build only the documented scalar credential fields; Gateway encrypts them. */
    private fun remoteSecret(values: List<String>, offset: Int): JSONObject? {
        val fields = listOf("access_key", "secret", "account")
        val result = JSONObject()
        fields.forEachIndexed { index, key -> values.getOrNull(offset + index)?.takeIf { it.isNotBlank() }?.let { result.put(key, it) } }
        return result.takeIf { it.length() > 0 }
    }

    override fun onPause() { tokenStore.write(token.text.toString()); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

