package com.android.rclone.manager.ui.screens

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
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
import com.android.rclone.manager.data.model.FileItem
import com.android.rclone.manager.data.model.RemoteItem
import com.android.rclone.manager.data.model.formatBytes
import com.android.rclone.manager.data.model.parseFileList
import com.android.rclone.manager.data.model.parseRemotes
import com.android.rclone.manager.ui.component.ContentCard
import com.android.rclone.manager.ui.component.DangerousConfirmDialog
import com.android.rclone.manager.ui.component.EmptyView
import com.android.rclone.manager.ui.component.LoadingView
import com.android.rclone.manager.ui.component.MaterialTextField
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

    var showMkdirDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var downloadTargetFile by remember { mutableStateOf<FileItem?>(null) }
    var moveTargetFile by remember { mutableStateOf<Pair<FileItem, Boolean>?>(null) } // FileItem to isCopy (true=copy, false=move)
    var deleteCandidate by remember { mutableStateOf<Triple<FileItem, String, String>?>(null) } // FileItem, Token, summary

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
        client.remotes(bearer).onSuccess {
            val list = parseRemotes(it)
            remotes = list
            val target = if (!initialRemoteId.isNullOrBlank()) list.find { r -> r.id == initialRemoteId } else list.firstOrNull()
            selectedRemote = target
            target?.let { r -> loadDirectory(r.id, "/") }
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

            IconButton(onClick = { selectedRemote?.let { loadDirectory(it.id, currentPath) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        // Action shortcuts: New folder & Upload
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showMkdirDialog = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = selectedRemote != null
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建目录")
            }

            Button(
                onClick = { showUploadDialog = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = selectedRemote != null
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("上传文件")
            }
        }

        Spacer(Modifier.height(8.dp))

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
                                    text = { Text("复制") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        moveTargetFile = file to true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("移动") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        moveTargetFile = file to false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除 (带预览)", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
                                        selectedRemote?.let { r ->
                                            scope.launch {
                                                onShowMessage("正在计算删除影响范围…")
                                                client.deletePreview(r.id, fullPath, bearer).fold(
                                                    onSuccess = { preview ->
                                                        val obj = JSONObject(preview)
                                                        val token = obj.optString("confirmationToken")
                                                        val summary = "目标: $fullPath\n影响文件: ${obj.optInt("deleted", 1)} 个"
                                                        if (token.isNotBlank()) {
                                                            deleteCandidate = Triple(file, token, summary)
                                                        } else {
                                                            onShowMessage("未返回确认令牌: $preview")
                                                        }
                                                    },
                                                    onFailure = { onShowMessage("删除预览失败: ${it.message}") }
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
        var localPath by remember { mutableStateOf(TextFieldValue("/data/media/0/Download/")) }
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("上传文件到当前目录", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("输入本地文件绝对路径 (Root 提权读取):", style = MaterialTheme.typography.bodySmall)
                    MaterialTextField(value = localPath, onValueChange = { localPath = it }, label = "本地路径")
                    Text("目标路径: $currentPath", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val local = localPath.text.trim()
                        if (local.isNotBlank() && selectedRemote != null) {
                            val dest = "${selectedRemote!!.name}:$currentPath"
                            scope.launch {
                                client.upload(local, dest, bearer).fold(
                                    onSuccess = {
                                        onShowMessage("已创建上传任务")
                                        showUploadDialog = false
                                        loadDirectory(selectedRemote!!.id, currentPath)
                                    },
                                    onFailure = { onShowMessage("上传失败: ${it.message}") }
                                )
                            }
                        }
                    }
                ) { Text("开始上传") }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) { Text("取消") }
            }
        )
    }

    downloadTargetFile?.let { file ->
        val fullPath = if (currentPath == "/") "/${file.name}" else "$currentPath/${file.name}"
        var localDest by remember { mutableStateOf(TextFieldValue("/data/media/0/Download/${file.name}")) }
        AlertDialog(
            onDismissRequest = { downloadTargetFile = null },
            title = { Text("下载文件", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("远端源路径: $fullPath", style = MaterialTheme.typography.bodySmall)
                    MaterialTextField(value = localDest, onValueChange = { localDest = it }, label = "保存到本地路径")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val local = localDest.text.trim()
                        if (local.isNotBlank() && selectedRemote != null) {
                            val remoteSrc = "${selectedRemote!!.name}:$fullPath"
                            scope.launch {
                                client.download(remoteSrc, local, bearer).fold(
                                    onSuccess = {
                                        onShowMessage("已创建下载任务")
                                        downloadTargetFile = null
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
            onConfirm = {
                selectedRemote?.let { r ->
                    scope.launch {
                        client.deleteConfirmed(r.id, fullPath, token, bearer).fold(
                            onSuccess = {
                                onShowMessage("已提交删除任务")
                                deleteCandidate = null
                                loadDirectory(r.id, currentPath)
                            },
                            onFailure = { onShowMessage("删除失败: ${it.message}") }
                        )
                    }
                }
            },
            onDismiss = { deleteCandidate = null }
        )
    }
}
