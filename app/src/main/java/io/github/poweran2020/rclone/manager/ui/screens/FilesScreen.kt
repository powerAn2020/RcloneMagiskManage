package io.github.poweran2020.rclone.manager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.FileItem
import io.github.poweran2020.rclone.manager.data.model.LocalFileItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.formatBytes
import io.github.poweran2020.rclone.manager.data.model.JobRunItem
import io.github.poweran2020.rclone.manager.data.model.parseFileList
import io.github.poweran2020.rclone.manager.data.model.parseJobRuns
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import io.github.poweran2020.rclone.manager.ui.component.LoadingProgressDialog
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    initialRemoteId: String? = null,
    onShowMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var remotes by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var selectedRemote by remember { mutableStateOf<RemoteItem?>(null) }
    var currentPath by remember { mutableStateOf("/") }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isSafeMode by remember { mutableStateOf(false) }

    var showMkdirDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var downloadTargetFile by remember { mutableStateOf<FileItem?>(null) }
    var moveTargetFile by remember { mutableStateOf<Pair<FileItem, Boolean>?>(null) } // FileItem to isCopy (true=copy, false=move)
    var deleteCandidate by remember { mutableStateOf<Triple<FileItem, String, String>?>(null) } // FileItem, Token, summary
    var isCalculatingPreview by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var activeTransfer by remember { mutableStateOf<ActiveTransfer?>(null) }

    val loadDirectory = { remoteId: String, path: String ->
        scope.launch {
            isLoading = true
            client.listFiles(remoteId, path, bearer).fold(
                onSuccess = { raw ->
                    val resp = parseFileList(raw)
                    currentPath = resp.path.ifBlank { "/" }
                    files = resp.items
                },
                onFailure = {
                    onShowMessage("加载文件失败: ${it.message}")
                    files = emptyList()
                }
            )
            isLoading = false
        }
    }

    LaunchedEffect(bearer, initialRemoteId) {
        client.safeMode(bearer).onSuccess {
            isSafeMode = JSONObject(it).optBoolean("enabled", false)
        }
        client.remotes(bearer).onSuccess {
            val list = parseRemotes(it)
            remotes = list
            val target = if (!initialRemoteId.isNullOrBlank()) list.find { r -> r.id == initialRemoteId } else list.firstOrNull()
            selectedRemote = target
            target?.let { r -> loadDirectory(r.id, "/") }
        }
    }

    // Poll transfer progress
    LaunchedEffect(activeTransfer?.jobId) {
        val transfer = activeTransfer ?: return@LaunchedEffect
        if (transfer.isFinished) return@LaunchedEffect

        while (true) {
            kotlinx.coroutines.delay(800)
            val current = activeTransfer ?: break
            if (current.isFinished) break

            var runState: String? = null
            var runTransferred: Long? = null
            var runTotal: Long? = null
            var runError: String? = null

            client.jobRuns(current.jobId, bearer).onSuccess { raw ->
                val runs = parseJobRuns(raw)
                val latest = runs.firstOrNull()
                if (latest != null) {
                    runState = latest.state
                    runTransferred = latest.transferredBytes
                    runTotal = latest.totalBytes
                    runError = latest.errorMessage
                }
            }

            var jobStatus: String? = null
            client.job(current.jobId, bearer).onSuccess { raw ->
                jobStatus = runCatching { JSONObject(raw).optString("status") }.getOrNull()
            }

            val finalState = when {
                jobStatus in setOf("SUCCESS", "FAILED", "CANCELLED") -> jobStatus!!
                runState in setOf("SUCCESS", "FAILED", "CANCELLED") -> runState!!
                jobStatus != null && jobStatus != "UNKNOWN" -> jobStatus!!
                runState != null && runState != "UNKNOWN" -> runState!!
                else -> current.state
            }

            val updated = current.copy(
                state = finalState,
                transferredBytes = runTransferred ?: current.transferredBytes,
                totalBytes = runTotal ?: current.totalBytes,
                errorMessage = runError ?: current.errorMessage
            )
            activeTransfer = updated

            if (updated.isFinished) {
                if (updated.state == "SUCCESS") {
                    if (current.isUpload && selectedRemote != null) {
                        loadDirectory(selectedRemote!!.id, currentPath)
                    }
                }
                break
            }
        }
    }

    LaunchedEffect(activeTransfer?.state) {
        if (activeTransfer?.state == "SUCCESS") {
            kotlinx.coroutines.delay(4000)
            if (activeTransfer?.state == "SUCCESS") {
                activeTransfer = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Remote Selector Row
        var remoteDropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = remoteDropdownExpanded,
            onExpandedChange = { remoteDropdownExpanded = !remoteDropdownExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedRemote?.let { "${it.name} (${it.type})" } ?: "选择远端",
                onValueChange = {},
                readOnly = true,
                label = { Text("当前远端") },
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
                            selectedRemote = r
                            remoteDropdownExpanded = false
                            currentPath = "/"
                            loadDirectory(r.id, "/")
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Breadcrumb & Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentPath != "/") {
                        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                        val target = if (parent.isBlank()) "/" else parent
                        selectedRemote?.let { loadDirectory(it.id, target) }
                    }
                },
                enabled = currentPath != "/"
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一层")
            }

            val pathSegments = remember(currentPath) {
                currentPath.split('/').filter { it.isNotBlank() }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = currentPath == "/",
                    onClick = { selectedRemote?.let { loadDirectory(it.id, "/") } },
                    label = { Text("根目录 /") }
                )
                var accumulated = ""
                pathSegments.forEach { seg ->
                    accumulated += "/$seg"
                    val target = accumulated
                    Spacer(Modifier.width(4.dp))
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = currentPath == target,
                        onClick = { selectedRemote?.let { loadDirectory(it.id, target) } },
                        label = { Text(seg) }
                    )
                }
            }

            IconButton(onClick = {
                scope.launch {
                    client.safeMode(bearer).onSuccess {
                        isSafeMode = JSONObject(it).optBoolean("enabled", false)
                    }
                }
                selectedRemote?.let { loadDirectory(it.id, currentPath) }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (isSafeMode) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "安全模式已开启：写操作（新建、上传、移动、删除等）已被锁定禁用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Action shortcuts: New folder & Upload
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (isSafeMode) {
                        onShowMessage("安全模式已开启，禁止新建目录")
                    } else {
                        showMkdirDialog = true
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = selectedRemote != null && !isSafeMode
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建目录")
            }

            Button(
                onClick = {
                    if (isSafeMode) {
                        onShowMessage("安全模式已开启，禁止上传文件")
                    } else {
                        showUploadDialog = true
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = selectedRemote != null && !isSafeMode
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("上传文件")
            }
        }

        Spacer(Modifier.height(8.dp))

        activeTransfer?.let { transfer ->
            TransferProgressCard(
                transfer = transfer,
                onCancel = {
                    scope.launch {
                        client.jobAction(transfer.jobId, "cancel", bearer).fold(
                            onSuccess = {
                                onShowMessage("已请求取消传输")
                                activeTransfer = transfer.copy(state = "CANCELLED")
                            },
                            onFailure = { onShowMessage("取消失败: ${it.message}") }
                        )
                    }
                },
                onDismiss = { activeTransfer = null }
            )
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            LoadingView(message = "正在读取目录内容…")
        } else if (selectedRemote == null) {
            EmptyView(title = "请先选择远端", message = "在顶部选择需要浏览的远端存储。")
        } else if (files.isEmpty()) {
            EmptyView(icon = Icons.Default.Folder, title = "该目录为空", message = "当前目录下没有找到任何文件或子目录。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.path }) { file ->
                    var menuExpanded by remember { mutableStateOf(false) }

                    ContentCard(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(12.dp),
                        onClick = {
                            if (file.isDir) {
                                val target = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
                                selectedRemote?.let { loadDirectory(it.id, target) }
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isDir) Icons.Default.Folder else Icons.Default.FilePresent,
                                contentDescription = null,
                                tint = if (file.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!file.isDir) {
                                        Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (file.modTime.isNotBlank()) {
                                        Text(file.modTime.take(19).replace('T', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (!file.isDir) {
                                    DropdownMenuItem(
                                        text = { Text("下载到本地") },
                                        leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            downloadTargetFile = file
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("复制" + if (isSafeMode) " (安全模式禁用)" else "") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                    enabled = !isSafeMode,
                                    onClick = {
                                        if (isSafeMode) {
                                            onShowMessage("安全模式已开启，禁止复制操作")
                                            return@DropdownMenuItem
                                        }
                                        menuExpanded = false
                                        moveTargetFile = file to true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("移动" + if (isSafeMode) " (安全模式禁用)" else "") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                    enabled = !isSafeMode,
                                    onClick = {
                                        if (isSafeMode) {
                                            onShowMessage("安全模式已开启，禁止移动操作")
                                            return@DropdownMenuItem
                                        }
                                        menuExpanded = false
                                        moveTargetFile = file to false
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "删除 (带预览)" + if (isSafeMode) " (安全模式禁用)" else "",
                                            color = if (isSafeMode) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = if (isSafeMode) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                                        )
                                    },
                                    enabled = !isSafeMode,
                                    onClick = {
                                        if (isSafeMode) {
                                            onShowMessage("安全模式已开启，禁止删除操作")
                                            return@DropdownMenuItem
                                        }
                                        menuExpanded = false
                                        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
                                        selectedRemote?.let { r ->
                                            scope.launch {
                                                isCalculatingPreview = true
                                                client.deletePreview(r.id, fullPath, bearer).fold(
                                                    onSuccess = { preview ->
                                                        isCalculatingPreview = false
                                                        val obj = JSONObject(preview)
                                                        val token = obj.optString("confirmationToken")
                                                        val count = obj.optInt("deleted", 1)
                                                        val bytes = obj.optLong("bytes", 0L)
                                                        val summary = "目标: $fullPath\n影响文件: $count 个" + if (bytes > 0) "\n占用大小: ${formatBytes(bytes)}" else ""
                                                        if (token.isNotBlank()) {
                                                            deleteCandidate = Triple(file, token, summary)
                                                        } else {
                                                            onShowMessage("未返回确认令牌: $preview")
                                                        }
                                                    },
                                                    onFailure = {
                                                        isCalculatingPreview = false
                                                        onShowMessage("删除预览失败: ${it.message}")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showMkdirDialog) {
        var dirName by remember { mutableStateOf(TextFieldValue("")) }
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = { Text("新建目录", fontWeight = FontWeight.Bold) },
            text = {
                MaterialTextField(value = dirName, onValueChange = { dirName = it }, label = "目录名称")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isSafeMode) {
                            onShowMessage("安全模式已开启，禁止新建目录")
                            showMkdirDialog = false
                            return@Button
                        }
                        val name = dirName.text.trim()
                        if (name.isNotBlank() && selectedRemote != null) {
                            val newPath = if (currentPath == "/") "/$name" else "$currentPath/$name"
                            scope.launch {
                                client.mkdir(selectedRemote!!.id, newPath, bearer).fold(
                                    onSuccess = {
                                        onShowMessage("目录创建成功")
                                        showMkdirDialog = false
                                        loadDirectory(selectedRemote!!.id, currentPath)
                                    },
                                    onFailure = { onShowMessage("创建目录失败: ${it.message}") }
                                )
                            }
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showMkdirDialog = false }) { Text("取消") }
            }
        )
    }

    if (showUploadDialog) {
        UploadFileDialog(
            currentRemotePath = currentPath,
            client = client,
            onDismiss = { showUploadDialog = false },
            onConfirmUpload = { localPath ->
                if (isSafeMode) {
                    onShowMessage("安全模式已开启，禁止上传文件")
                    showUploadDialog = false
                    return@UploadFileDialog
                }
                if (selectedRemote != null) {
                    val dest = "${selectedRemote!!.name}:$currentPath"
                    val fileName = localPath.substringAfterLast('/')
                    scope.launch {
                        client.upload(localPath, dest, bearer).fold(
                            onSuccess = { res ->
                                onShowMessage("已创建上传任务")
                                showUploadDialog = false
                                val jobId = runCatching { JSONObject(res).optString("jobId") }.getOrNull()
                                if (!jobId.isNullOrBlank()) {
                                    activeTransfer = ActiveTransfer(
                                        jobId = jobId,
                                        isUpload = true,
                                        fileName = fileName,
                                        state = "RUNNING"
                                    )
                                } else {
                                    loadDirectory(selectedRemote!!.id, currentPath)
                                }
                            },
                            onFailure = { onShowMessage("上传失败: ${it.message}") }
                        )
                    }
                }
            }
        )
    }

    downloadTargetFile?.let { file ->
        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
        var localDir by remember { mutableStateOf(TextFieldValue("/storage/emulated/0/Download")) }
        val targetDir = localDir.text.trim().trimEnd('/')
        val previewFullPath = if (targetDir.endsWith("/${file.name}")) targetDir else "$targetDir/${file.name}"
        AlertDialog(
            onDismissRequest = { downloadTargetFile = null },
            title = { Text("下载文件", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("远端源路径: $fullPath", style = MaterialTheme.typography.bodySmall)
                    MaterialTextField(value = localDir, onValueChange = { localDir = it }, label = "保存到本地目录")
                    Text(
                        "完整保存路径: $previewFullPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = localDir.text.trim().trimEnd('/')
                        if (input.isNotBlank() && selectedRemote != null) {
                            val cleanDir = if (input.endsWith("/${file.name}")) {
                                input.removeSuffix("/${file.name}").ifBlank { "/storage/emulated/0/Download" }
                            } else {
                                input
                            }
                            val remoteSrc = "${selectedRemote!!.name}:$fullPath"
                            val fileName = file.name
                            scope.launch {
                                client.download(remoteSrc, cleanDir, bearer).fold(
                                    onSuccess = { res ->
                                        onShowMessage("已创建下载任务")
                                        downloadTargetFile = null
                                        val jobId = runCatching { JSONObject(res).optString("jobId") }.getOrNull()
                                        if (!jobId.isNullOrBlank()) {
                                            activeTransfer = ActiveTransfer(
                                                jobId = jobId,
                                                isUpload = false,
                                                fileName = fileName,
                                                state = "RUNNING"
                                            )
                                        }
                                    },
                                    onFailure = { onShowMessage("下载失败: ${it.message}") }
                                )
                            }
                        }
                    }
                ) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { downloadTargetFile = null }) { Text("取消") }
            }
        )
    }

    moveTargetFile?.let { (file, isCopy) ->
        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
        var destInput by remember { mutableStateOf(TextFieldValue("${selectedRemote?.name}:$currentPath/copy_${file.name}")) }
        AlertDialog(
            onDismissRequest = { moveTargetFile = null },
            title = { Text(if (isCopy) "复制文件" else "移动文件", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("源文件: $fullPath", style = MaterialTheme.typography.bodySmall)
                    MaterialTextField(value = destInput, onValueChange = { destInput = it }, label = "目标路径 (remote:path)")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isSafeMode) {
                            onShowMessage("安全模式已开启，禁止移动/复制操作")
                            moveTargetFile = null
                            return@Button
                        }
                        val dest = destInput.text.trim()
                        if (dest.isNotBlank() && selectedRemote != null) {
                            val src = "${selectedRemote!!.name}:$fullPath"
                            scope.launch {
                                val action = if (isCopy) client.copy(src, dest, bearer) else client.move(src, dest, bearer)
                                action.fold(
                                    onSuccess = {
                                        onShowMessage(if (isCopy) "已创建复制任务" else "已创建移动任务")
                                        moveTargetFile = null
                                        loadDirectory(selectedRemote!!.id, currentPath)
                                    },
                                    onFailure = { onShowMessage("操作失败: ${it.message}") }
                                )
                            }
                        }
                    }
                ) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = { moveTargetFile = null }) { Text("取消") }
            }
        )
    }

    deleteCandidate?.let { (file, token, summary) ->
        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
        DangerousConfirmDialog(
            show = true,
            title = "确认删除: ${file.name}",
            message = summary,
            tokenBadge = token,
            confirmLabel = "确认删除",
            isLoading = isDeleting,
            onConfirm = {
                if (isSafeMode) {
                    onShowMessage("安全模式已开启，禁止删除操作")
                    deleteCandidate = null
                    return@DangerousConfirmDialog
                }
                selectedRemote?.let { r ->
                    scope.launch {
                        isDeleting = true
                        client.deleteConfirmed(r.id, fullPath, token, bearer).fold(
                            onSuccess = {
                                isDeleting = false
                                deleteCandidate = null
                                onShowMessage("已成功删除目标: ${file.name}")
                                loadDirectory(r.id, currentPath)
                            },
                            onFailure = {
                                isDeleting = false
                                onShowMessage("删除失败: ${it.message}")
                            }
                        )
                    }
                }
            },
            onDismiss = {
                if (!isDeleting) {
                    deleteCandidate = null
                }
            }
        )
    }

    LoadingProgressDialog(
        show = isCalculatingPreview,
        message = "正在计算删除影响范围与令牌…"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadFileDialog(
    currentRemotePath: String,
    client: GatewayClient,
    onDismiss: () -> Unit,
    onConfirmUpload: (String) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0: 文件列表模式, 1: 手动输入路径
    var localPathInput by remember { mutableStateOf(TextFieldValue("/data/media/0/Download/")) }
    var currentLocalDir by remember { mutableStateOf("/data/media/0/Download") }
    var localItems by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<LocalFileItem?>(null) }
    var isLoadingLocal by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val refreshLocalDir = { targetDir: String ->
        coroutineScope.launch {
            isLoadingLocal = true
            currentLocalDir = targetDir
            selectedItem = null
            localItems = client.listLocalDirectory(targetDir)
            isLoadingLocal = false
        }
    }

    LaunchedEffect(currentLocalDir) {
        refreshLocalDir(currentLocalDir)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 320.dp, max = 560.dp),
        title = {
            Text(
                text = "上传文件到当前目录",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mode Switch Tab
                TabRow(
                    selectedTabIndex = mode,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        text = { Text("文件列表模式") },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        text = { Text("手动输入路径") },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                if (mode == 0) {
                    // Quick location chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val shortcuts = listOf(
                            "Download" to "/data/media/0/Download",
                            "Documents" to "/data/media/0/Documents",
                            "DCIM" to "/data/media/0/DCIM",
                            "内部存储" to "/data/media/0",
                            "根目录" to "/"
                        )
                        shortcuts.forEach { (label, path) ->
                            FilterChip(
                                selected = currentLocalDir == path,
                                onClick = { refreshLocalDir(path) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Directory navigation bar
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isRoot = currentLocalDir == "/" || currentLocalDir.isEmpty()
                            IconButton(
                                onClick = {
                                    val parent = java.io.File(currentLocalDir).parent ?: "/"
                                    refreshLocalDir(parent)
                                },
                                enabled = !isRoot,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级", modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = currentLocalDir,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { refreshLocalDir(currentLocalDir) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Local files list
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 260.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        if (isLoadingLocal) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        } else if (localItems.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "目录为空或无读取权限",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(localItems) { item ->
                                    val isSelected = selectedItem?.path == item.path
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (item.isDirectory) {
                                                    refreshLocalDir(item.path)
                                                } else {
                                                    selectedItem = item
                                                    localPathInput = TextFieldValue(item.path)
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.FilePresent,
                                                contentDescription = null,
                                                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (item.isDirectory) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.isDirectory) {
                                                    Text(
                                                        text = formatBytes(item.size),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "已选择",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }

                    // Selection prompt
                    if (selectedItem != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "已选: ${selectedItem!!.name} (${formatBytes(selectedItem!!.size)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "提示: 点击文件夹进入，点击文件即可选中",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Manual mode
                    Text("输入本地文件绝对路径 (Root 提权读取):", style = MaterialTheme.typography.bodySmall)
                    MaterialTextField(value = localPathInput, onValueChange = { localPathInput = it }, label = "本地路径")
                }

                Text(
                    text = "目标远端路径: $currentRemotePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            val canUpload = if (mode == 0) selectedItem != null else localPathInput.text.isNotBlank()
            Button(
                onClick = {
                    val finalPath = if (mode == 0) selectedItem?.path ?: "" else localPathInput.text.trim()
                    if (finalPath.isNotBlank()) {
                        onConfirmUpload(finalPath)
                    }
                },
                enabled = canUpload
            ) {
                Text("开始上传")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

data class ActiveTransfer(
    val jobId: String,
    val isUpload: Boolean,
    val fileName: String,
    val state: String = "QUEUED",
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null
) {
    val progress: Float?
        get() = if (totalBytes != null && totalBytes > 0) {
            (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else null

    val isFinished: Boolean
        get() = state in setOf("SUCCESS", "FAILED", "CANCELLED")
}

@Composable
fun TransferProgressCard(
    transfer: ActiveTransfer,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        transfer.state == "SUCCESS" -> Icons.Default.CheckCircle
                        transfer.state == "FAILED" -> Icons.Default.Error
                        transfer.isUpload -> Icons.Default.CloudUpload
                        else -> Icons.Default.CloudDownload
                    },
                    contentDescription = null,
                    tint = when (transfer.state) {
                        "SUCCESS" -> MaterialTheme.colorScheme.primary
                        "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${if (transfer.isUpload) "上传" else "下载"}: ${transfer.fileName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when (transfer.state) {
                            "QUEUED" -> "等待调度中..."
                            "RUNNING" -> {
                                if (transfer.totalBytes != null && transfer.totalBytes > 0) {
                                    val percent = ((transfer.progress ?: 0f) * 100).toInt()
                                    "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)} ($percent%)"
                                } else if (transfer.transferredBytes > 0) {
                                    "已传输 ${formatBytes(transfer.transferredBytes)}"
                                } else {
                                    "正在传输..."
                                }
                            }
                            "SUCCESS" -> "传输完成" + if (transfer.totalBytes != null && transfer.totalBytes > 0) " (${formatBytes(transfer.totalBytes)})" else ""
                            "FAILED" -> "传输失败: ${transfer.errorMessage ?: "未知错误"}"
                            "CANCELLED" -> "传输已取消"
                            else -> transfer.state
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (transfer.state) {
                            "SUCCESS" -> MaterialTheme.colorScheme.primary
                            "FAILED" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (transfer.isFinished) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            when {
                transfer.state == "SUCCESS" -> {
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                transfer.state == "FAILED" || transfer.state == "CANCELLED" -> {
                    LinearProgressIndicator(
                        progress = { transfer.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                transfer.progress != null -> {
                    LinearProgressIndicator(
                        progress = { transfer.progress!! },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
                else -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
            }
        }
    }
}

