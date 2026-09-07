package io.github.poweran2020.rclone.manager.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.data.model.FileItem
import io.github.poweran2020.rclone.manager.data.model.LocalFileItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.formatBytes
import io.github.poweran2020.rclone.manager.data.model.parseFileList
import kotlinx.coroutines.launch

@Composable
fun RclonePathPickerField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    remotes: List<RemoteItem>,
    client: GatewayClient,
    bearer: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null
) {
    var showPickerDialog by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { showPickerDialog = true }) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "浏览选择路径",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    )

    if (showPickerDialog) {
        PathPickerDialog(
            title = "选择 $label",
            initialPath = value.text,
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showPickerDialog = false },
            onConfirm = { chosen ->
                onValueChange(TextFieldValue(chosen))
                showPickerDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathPickerDialog(
    title: String,
    initialPath: String,
    remotes: List<RemoteItem>,
    client: GatewayClient,
    bearer: String,
    onDismiss: () -> Unit,
    onConfirm: (selectedPath: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    // Parse initial path to decide default tab and remote/path
    val isInitialRemote = initialPath.contains(":") && !initialPath.startsWith("/")
    val initialRemoteName = if (isInitialRemote) initialPath.substringBefore(":") else ""
    val initialRemotePath = if (isInitialRemote) {
        val p = initialPath.substringAfter(":").trim()
        if (p.startsWith("/")) p else "/$p"
    } else "/"

    val initialLocalPath = if (!isInitialRemote && initialPath.isNotBlank() && initialPath.startsWith("/")) {
        initialPath.trim()
    } else {
        "/data/media/0/Download"
    }

    var mode by remember { mutableStateOf(if (isInitialRemote || remotes.isNotEmpty()) 0 else 1) }

    // --- Remote State ---
    var selectedRemote by remember {
        mutableStateOf(remotes.find { it.name == initialRemoteName } ?: remotes.firstOrNull())
    }
    var currentRemotePath by remember { mutableStateOf(initialRemotePath) }
    var remoteFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoadingRemote by remember { mutableStateOf(false) }
    var selectedRemoteItem by remember { mutableStateOf<FileItem?>(null) }

    val loadRemoteDir = { r: RemoteItem, p: String ->
        scope.launch {
            isLoadingRemote = true
            selectedRemoteItem = null
            client.listFiles(r.id, p, bearer).fold(
                onSuccess = { raw ->
                    val resp = parseFileList(raw)
                    currentRemotePath = resp.path.ifBlank { "/" }
                    remoteFiles = resp.items
                },
                onFailure = {
                    remoteFiles = emptyList()
                }
            )
            isLoadingRemote = false
        }
    }

    LaunchedEffect(selectedRemote) {
        selectedRemote?.let { r -> loadRemoteDir(r, currentRemotePath) }
    }

    // --- Local State ---
    var currentLocalDir by remember { mutableStateOf(initialLocalPath) }
    var localFiles by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var isLoadingLocal by remember { mutableStateOf(false) }
    var selectedLocalItem by remember { mutableStateOf<LocalFileItem?>(null) }

    val loadLocalDir = { targetDir: String ->
        scope.launch {
            isLoadingLocal = true
            selectedLocalItem = null
            currentLocalDir = targetDir
            localFiles = client.listLocalDirectory(targetDir)
            isLoadingLocal = false
        }
    }

    LaunchedEffect(currentLocalDir) {
        loadLocalDir(currentLocalDir)
    }

    // Current resolved path
    val resolvedPath = remember(mode, selectedRemote, currentRemotePath, selectedRemoteItem, currentLocalDir, selectedLocalItem) {
        if (mode == 0) {
            val rName = selectedRemote?.name ?: "remote"
            if (selectedRemoteItem != null) {
                val item = selectedRemoteItem!!
                val full = if (currentRemotePath == "/") "/${item.name}" else "$currentRemotePath/${item.name}"
                "$rName:$full"
            } else {
                "$rName:$currentRemotePath"
            }
        } else {
            if (selectedLocalItem != null) {
                selectedLocalItem!!.path
            } else {
                currentLocalDir
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 340.dp, max = 640.dp),
        title = {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mode Tabs
                TabRow(selectedTabIndex = mode, modifier = Modifier.fillMaxWidth()) {
                    Tab(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        text = { Text("远端存储 (Remote)") },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        text = { Text("本地存储 (Local)") },
                        icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                if (mode == 0) {
                    // Remote View
                    if (remotes.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("未检测到已配置的远端，请在“远端”页添加配置或切至本地存储", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        // Remote Dropdown
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedRemote?.let { "${it.name} (${it.type})" } ?: "选择远端",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("选择远端") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                remotes.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text("${r.name} (${r.type})") },
                                        onClick = {
                                            selectedRemote = r
                                            currentRemotePath = "/"
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Navigation bar & Breadcrumb
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
                                val isRoot = currentRemotePath == "/"
                                IconButton(
                                    onClick = {
                                        if (!isRoot) {
                                            val parent = currentRemotePath.trimEnd('/').substringBeforeLast('/', "")
                                            val target = if (parent.isBlank()) "/" else parent
                                            selectedRemote?.let { loadRemoteDir(it, target) }
                                        }
                                    },
                                    enabled = !isRoot,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级", modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))

                                val segments = remember(currentRemotePath) {
                                    currentRemotePath.split('/').filter { it.isNotBlank() }
                                }
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = currentRemotePath == "/" && selectedRemoteItem == null,
                                        onClick = { selectedRemote?.let { loadRemoteDir(it, "/") } },
                                        label = { Text("根目录 /", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    var acc = ""
                                    segments.forEach { seg ->
                                        acc += "/$seg"
                                        val target = acc
                                        Spacer(Modifier.width(2.dp))
                                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(2.dp))
                                        FilterChip(
                                            selected = currentRemotePath == target && selectedRemoteItem == null,
                                            onClick = { selectedRemote?.let { loadRemoteDir(it, target) } },
                                            label = { Text(seg, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { selectedRemote?.let { loadRemoteDir(it, currentRemotePath) } },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // Remote File List
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 240.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        ) {
                            if (isLoadingRemote) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            } else if (remoteFiles.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(remoteFiles) { item ->
                                        val isSelected = selectedRemoteItem?.name == item.name
                                        Surface(
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (item.isDir) {
                                                        val next = if (currentRemotePath == "/") "/${item.name}" else "$currentRemotePath/${item.name}"
                                                        selectedRemote?.let { loadRemoteDir(it, next) }
                                                    } else {
                                                        selectedRemoteItem = if (isSelected) null else item
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
                                                    imageVector = if (item.isDir) Icons.Default.Folder else Icons.Default.FilePresent,
                                                    contentDescription = null,
                                                    tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (item.isDir) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.isDir) {
                                                    Text(formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (isSelected) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Local View
                    // Shortcuts
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
                        shortcuts.forEach { (lbl, path) ->
                            FilterChip(
                                selected = currentLocalDir == path && selectedLocalItem == null,
                                onClick = { loadLocalDir(path) },
                                label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Local Navigation Bar
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
                                    loadLocalDir(parent)
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
                                onClick = { loadLocalDir(currentLocalDir) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Local File List
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 240.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        if (isLoadingLocal) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        } else if (localFiles.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("目录为空或无读取权限", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(localFiles) { item ->
                                    val isSelected = selectedLocalItem?.path == item.path
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (item.isDirectory) {
                                                    loadLocalDir(item.path)
                                                } else {
                                                    selectedLocalItem = if (isSelected) null else item
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
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (item.isDirectory) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!item.isDirectory) {
                                                Text(formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (isSelected) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }

                // Current Selection Badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                            text = "已选: $resolvedPath",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (resolvedPath.isNotBlank()) {
                        onConfirm(resolvedPath)
                    }
                }
            ) {
                Text("确定选择")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
