package io.github.poweran2020.rclone.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.compose.ui.res.stringResource
import io.github.poweran2020.rclone.manager.GatewayClient
import io.github.poweran2020.rclone.manager.R
import io.github.poweran2020.rclone.manager.data.model.JobItem
import io.github.poweran2020.rclone.manager.data.model.JobRunItem
import io.github.poweran2020.rclone.manager.data.model.RemoteItem
import io.github.poweran2020.rclone.manager.data.model.formatBytes
import io.github.poweran2020.rclone.manager.data.model.formatEpochTime
import io.github.poweran2020.rclone.manager.data.model.parseJobRuns
import io.github.poweran2020.rclone.manager.data.model.parseJobs
import io.github.poweran2020.rclone.manager.data.model.parseRemotes
import io.github.poweran2020.rclone.manager.ui.component.ContentCard
import io.github.poweran2020.rclone.manager.ui.component.EmptyView
import io.github.poweran2020.rclone.manager.ui.component.InfoRow
import io.github.poweran2020.rclone.manager.ui.component.LoadingView
import io.github.poweran2020.rclone.manager.ui.component.MaterialTextField
import io.github.poweran2020.rclone.manager.ui.component.RclonePathPickerField
import io.github.poweran2020.rclone.manager.ui.component.SectionTitle
import io.github.poweran2020.rclone.manager.ui.component.StatusBadge
import io.github.poweran2020.rclone.manager.ui.component.DangerousConfirmDialog
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    padding: PaddingValues,
    client: GatewayClient,
    bearer: String,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var jobs by remember { mutableStateOf<List<JobItem>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedRunsJob by remember { mutableStateOf<JobItem?>(null) }
    var jobRunsList by remember { mutableStateOf<List<JobRunItem>>(emptyList()) }
    var viewingLogJob by remember { mutableStateOf<Pair<JobItem, String>?>(null) } // JobItem to logText
    var jobToDelete by remember { mutableStateOf<JobItem?>(null) }
    var isDeletingJob by remember { mutableStateOf(false) }

    val loadJobs = {
        scope.launch {
            isLoading = true
            client.jobs(bearer).fold(
                onSuccess = { jobs = parseJobs(it) },
                onFailure = { onShowMessage("获取任务列表失败: ${it.message}") }
            )
            isLoading = false
        }
    }

    val loadRemotes = {
        scope.launch {
            client.remotes(bearer).fold(
                onSuccess = { remotes = parseRemotes(it) },
                onFailure = {}
            )
        }
    }

    LaunchedEffect(bearer) {
        loadJobs()
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
                SectionTitle(
                    text = stringResource(R.string.jobs_center_title, jobs.size),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { loadJobs() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_refresh), maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = {
                            loadRemotes()
                            showCreateDialog = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.jobs_btn_create), maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (isLoading) {
            item { LoadingView(message = stringResource(R.string.jobs_loading)) }
        } else if (jobs.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.jobs_empty_title),
                    message = stringResource(R.string.jobs_empty_message)
                )
            }
        } else {
            items(jobs, key = { it.id }) { job ->
                ContentCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusBadge(status = job.type.uppercase())
                            if (job.dryRun) {
                                StatusBadge(status = "DRY-RUN")
                            }
                        }
                        StatusBadge(status = job.status)
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.jobs_source_prefix, job.source),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (job.destination.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.jobs_dest_prefix, job.destination),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    job.schedule?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let {
                        Text(
                            text = stringResource(R.string.jobs_schedule_prefix, it) + (job.nextRunAt?.let { t -> stringResource(R.string.jobs_next_run_prefix, formatEpochTime(t)) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (job.status.uppercase()) {
                            "QUEUED", "STOPPED", "SUCCESS", "FAILED", "CANCELLED" -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            client.jobAction(job.id, "start", bearer).fold(
                                                onSuccess = { onShowMessage("任务已启动"); loadJobs() },
                                                onFailure = { onShowMessage("启动失败: ${it.message}") }
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.action_start))
                                }
                            }
                            "RUNNING" -> {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            client.jobAction(job.id, "pause", bearer).fold(
                                                onSuccess = { onShowMessage("已请求暂停"); loadJobs() },
                                                onFailure = { onShowMessage("暂停失败: ${it.message}") }
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.jobs_action_pause))
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            client.jobAction(job.id, "cancel", bearer).fold(
                                                onSuccess = { onShowMessage("已请求取消"); loadJobs() },
                                                onFailure = { onShowMessage("取消失败: ${it.message}") }
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                            "PAUSED" -> {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            client.jobAction(job.id, "resume", bearer).fold(
                                                onSuccess = { onShowMessage("已恢复执行"); loadJobs() },
                                                onFailure = { onShowMessage("恢复失败: ${it.message}") }
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.jobs_action_resume))
                                }
                            }
                        }

                        if (job.status.uppercase() in setOf("FAILED", "CANCELLED")) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        client.jobAction(job.id, "retry", bearer).fold(
                                            onSuccess = { onShowMessage("已提交重试"); loadJobs() },
                                            onFailure = { onShowMessage("重试失败: ${it.message}") }
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.jobRuns(job.id, bearer).fold(
                                        onSuccess = {
                                            jobRunsList = parseJobRuns(it)
                                            selectedRunsJob = job
                                        },
                                        onFailure = { onShowMessage("获取运行记录失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(R.string.job_btn_history))
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    client.jobLog(job.id, bearer).fold(
                                        onSuccess = { logText ->
                                            viewingLogJob = job to logText
                                        },
                                        onFailure = { onShowMessage("获取日志失败: ${it.message}") }
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(R.string.job_btn_logs))
                        }

                        if (job.status.uppercase() in setOf("CREATED", "SUCCESS", "FAILED", "CANCELLED")) {
                            OutlinedButton(
                                onClick = {
                                    jobToDelete = job
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Confirm Delete Job
    jobToDelete?.let { target ->
        DangerousConfirmDialog(
            show = true,
            title = stringResource(R.string.jobs_delete_dialog_title, target.type, target.id.take(8)),
            message = stringResource(R.string.jobs_delete_dialog_msg, target.source, target.destination),
            confirmLabel = stringResource(R.string.jobs_delete_btn_confirm),
            isLoading = isDeletingJob,
            onConfirm = {
                scope.launch {
                    isDeletingJob = true
                    client.deleteJob(target.id, bearer).fold(
                        onSuccess = {
                            isDeletingJob = false
                            onShowMessage("任务已删除")
                            jobToDelete = null
                            loadJobs()
                        },
                        onFailure = {
                            isDeletingJob = false
                            onShowMessage("删除失败: ${it.message}")
                        }
                    )
                }
            },
            onDismiss = {
                if (!isDeletingJob) {
                    jobToDelete = null
                }
            }
        )
    }

    // Dialog: Create Job
    if (showCreateDialog) {
        CreateJobDialog(
            remotes = remotes,
            client = client,
            bearer = bearer,
            onDismiss = { showCreateDialog = false },
            onSubmit = { type, src, dest, schedule, netPolicy, batPolicy, dryRun, options, onError ->
                scope.launch {
                    client.createJob(type, src, dest, bearer, schedule, netPolicy, batPolicy, dryRun, options).fold(
                        onSuccess = {
                            onShowMessage("任务创建成功")
                            showCreateDialog = false
                            loadJobs()
                        },
                        onFailure = {
                            val msg = it.message ?: "创建任务失败"
                            onShowMessage("创建任务失败: $msg")
                            onError(msg)
                        }
                    )
                }
            }
        )
    }

    // Dialog: Runs History
    selectedRunsJob?.let { job ->
        AlertDialog(
            onDismissRequest = { selectedRunsJob = null },
            title = { Text(stringResource(R.string.jobs_history_title, job.type, job.id.take(8)), fontWeight = FontWeight.Bold) },
            text = {
                if (jobRunsList.isEmpty()) {
                    Text(stringResource(R.string.jobs_history_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(jobRunsList) { run ->
                            ContentCard(insideMargin = PaddingValues(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("状态", style = MaterialTheme.typography.labelSmall)
                                    StatusBadge(status = run.state)
                                }
                                run.startedAt?.let { InfoRow(label = stringResource(R.string.jobs_history_start_time), value = formatEpochTime(it)) }
                                run.finishedAt?.let { InfoRow(label = stringResource(R.string.jobs_history_end_time), value = formatEpochTime(it)) }
                                run.transferredBytes?.let { bytes ->
                                    val text = if (bytes == 0L && run.state == "SUCCESS") {
                                        "0 B"
                                    } else {
                                        formatBytes(bytes)
                                    }
                                    InfoRow(label = stringResource(R.string.jobs_history_transferred_bytes), value = text)
                                }
                                run.transferredFiles?.let { files ->
                                    val text = if (files == 0L && run.state == "SUCCESS") {
                                        stringResource(R.string.count_unit, 0)
                                    } else {
                                        stringResource(R.string.count_unit, files)
                                    }
                                    InfoRow(label = stringResource(R.string.jobs_history_transferred_files), value = text)
                                }
                                val err = run.errorMessage?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                if (err != null) {
                                    Text(
                                        text = "${stringResource(R.string.jobs_history_error)}: $err",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    InfoRow(label = stringResource(R.string.jobs_history_error), value = stringResource(R.string.jobs_history_none))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRunsJob = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    // Dialog: View Job Log
    viewingLogJob?.let { (job, logContent) ->
        var viewMode by remember { mutableStateOf(LogViewMode.STRUCTURED) }
        val actualLog = remember(logContent) {
            val trimmed = logContent.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                runCatching {
                    val obj = JSONObject(trimmed)
                    if (obj.has("log")) obj.optString("log") else logContent
                }.getOrDefault(logContent)
            } else {
                logContent
            }
        }
        val (parsedEntries, latestStats) = remember(actualLog) { parseRcloneLogs(actualLog) }

        AlertDialog(
            onDismissRequest = { viewingLogJob = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.jobs_log_title), fontWeight = FontWeight.Bold)
                        Text(
                            text = "${job.type.uppercase()} • ${job.id.take(8)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("job-log", actualLog))
                        onShowMessage("日志已复制到剪贴板")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 三模式切换 Tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LogViewMode.values().forEach { mode ->
                            val selected = viewMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewMode = mode },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (logContent.isBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.jobs_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        when (viewMode) {
                            LogViewMode.STRUCTURED -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 顶部统计指标卡片
                                    if (latestStats != null) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column {
                                                        Text("传输数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(
                                                            "${formatBytes(latestStats.bytes)} / ${formatBytes(latestStats.totalBytes)}",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text("传输文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(
                                                            "${latestStats.transfers} / ${latestStats.totalTransfers} 个",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column {
                                                        Text("运行时长", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(
                                                            String.format(Locale.US, "%.1f 秒", latestStats.elapsedTime),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text("瞬时速度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(
                                                            "${formatBytes(latestStats.speed.toLong())}/s",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                if (latestStats.errors > 0) {
                                                    Text(
                                                        "异常错误: ${latestStats.errors} 次",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 事件流水
                                    val filteredEntries = parsedEntries.filterIndexed { index, item ->
                                        !item.isStatsHeartbeat || index == parsedEntries.lastIndex || item.stats?.transferringFiles?.isNotEmpty() == true || item.level == "ERROR"
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        filteredEntries.forEach { item ->
                                            val badgeColor = when (item.level) {
                                                "ERROR" -> MaterialTheme.colorScheme.error
                                                "WARN", "WARNING" -> Color(0xFFE65100)
                                                "NOTICE" -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.secondary
                                            }
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(6.dp),
                                                color = badgeColor.copy(alpha = 0.08f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = badgeColor.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = item.level,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = badgeColor,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        if (item.timeFormatted.isNotBlank()) {
                                                            Text(
                                                                item.timeFormatted,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        val displayMsg = item.msg.lines()
                                                            .map { it.trim() }
                                                            .filter { it.isNotBlank() }
                                                            .joinToString("\n")
                                                        Text(
                                                            displayMsg.ifBlank { "(无详细信息)" },
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LogViewMode.TERMINAL -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(Color(0xFF16181D), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        parsedEntries.forEach { entry ->
                                            val levelColor = when (entry.level) {
                                                "ERROR" -> Color(0xFFFF5252)
                                                "WARN", "WARNING" -> Color(0xFFFFB74D)
                                                "NOTICE" -> Color(0xFF69F0AE)
                                                "INFO" -> Color(0xFF40C4FF)
                                                else -> Color(0xFFB0BEC5)
                                            }
                                            val cleanedMsg = if (entry.isStatsHeartbeat) {
                                                entry.msg.lines()
                                                    .map { it.trim() }
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" | ")
                                            } else {
                                                entry.msg
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (entry.timeFormatted.isNotBlank()) {
                                                    Text(
                                                        text = "[${entry.timeFormatted}]",
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF78909C)
                                                    )
                                                }
                                                Text(
                                                    text = "[${entry.level.padEnd(5)}]",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = levelColor
                                                )
                                                Text(
                                                    text = cleanedMsg,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFECEFF1)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            LogViewMode.RAW -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(Color(0xFF21252B), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = logContent,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFABB2BF)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingLogJob = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateJobDialog(
    remotes: List<RemoteItem>,
    client: GatewayClient,
    bearer: String,
    onDismiss: () -> Unit,
    onSubmit: (type: String, src: String, dest: String, schedule: String?, net: String?, bat: String?, dryRun: Boolean, options: JSONObject?, onError: (String) -> Unit) -> Unit
) {
    var type by remember { mutableStateOf("copy") }
    var source by remember { mutableStateOf(TextFieldValue("")) }
    var destination by remember { mutableStateOf(TextFieldValue("")) }
    var networkPolicy by remember { mutableStateOf("ANY") }
    var batteryPolicy by remember { mutableStateOf("ANY") }
    var dryRun by remember { mutableStateOf(false) }

    var sourceError by remember { mutableStateOf<String?>(null) }
    var destinationError by remember { mutableStateOf<String?>(null) }
    var customScheduleError by remember { mutableStateOf<String?>(null) }
    var transfersError by remember { mutableStateOf<String?>(null) }
    var checkersError by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Schedule presets
    val schedulePresets = listOf(
        Pair("不自动调度 (仅手动执行)", null),
        Pair("开机时执行一次 (@reboot)", "@reboot"),
        Pair("每 5 分钟 (*/5 * * * *)", "*/5 * * * *"),
        Pair("每 15 分钟 (*/15 * * * *)", "*/15 * * * *"),
        Pair("每 30 分钟 (*/30 * * * *)", "*/30 * * * *"),
        Pair("每小时执行一次 (@hourly)", "@hourly"),
        Pair("每天执行一次 (@daily)", "@daily"),
        Pair("自定义表达式...", "CUSTOM")
    )
    var selectedScheduleIndex by remember { mutableStateOf(0) }
    var customScheduleText by remember { mutableStateOf(TextFieldValue("")) }
    var scheduleDropdownExpanded by remember { mutableStateOf(false) }

    // Advanced options
    var transfers by remember { mutableStateOf(TextFieldValue("4")) }
    var checkers by remember { mutableStateOf(TextFieldValue("8")) }
    var bwLimit by remember { mutableStateOf(TextFieldValue("")) }
    var overwrite by remember { mutableStateOf(false) }
    var deleteExcluded by remember { mutableStateOf(false) }

    val jobTypes = listOf("copy", "sync", "move", "bisync", "delete")
    val netPolicies = listOf("ANY", "WIFI", "UNMETERED", "VPN")
    val batPolicies = listOf("ANY", "CHARGING", "BATTERY_30")

    var typeDropdownExpanded by remember { mutableStateOf(false) }
    var netDropdownExpanded by remember { mutableStateOf(false) }
    var batDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(stringResource(R.string.jobs_dialog_create_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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

                // Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = {
                        if (!isSubmitting) typeDropdownExpanded = !typeDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.jobs_type_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSubmitting
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false }
                    ) {
                        jobTypes.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    type = item
                                    typeDropdownExpanded = false
                                    destinationError = null
                                    dialogError = null
                                }
                            )
                        }
                    }
                }

                RclonePathPickerField(
                    label = stringResource(R.string.jobs_source_label) + " *",
                    value = source,
                    onValueChange = {
                        source = it
                        sourceError = null
                        dialogError = null
                    },
                    remotes = remotes,
                    client = client,
                    bearer = bearer,
                    placeholder = "例如: remote:path 或 /sdcard/...",
                    isError = sourceError != null,
                    supportingText = {
                        if (sourceError != null) {
                            Text(sourceError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("传输源路径 (必填)")
                        }
                    }
                )

                if (type != "delete") {
                    RclonePathPickerField(
                        label = stringResource(R.string.jobs_dest_label) + " *",
                        value = destination,
                        onValueChange = {
                            destination = it
                            destinationError = null
                            dialogError = null
                        },
                        remotes = remotes,
                        client = client,
                        bearer = bearer,
                        placeholder = "例如: remote:path 或 /sdcard/...",
                        isError = destinationError != null,
                        supportingText = {
                            if (destinationError != null) {
                                Text(destinationError!!, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("传输目标路径 (必填)")
                            }
                        }
                    )
                }

                // Schedule Dropdown
                ExposedDropdownMenuBox(
                    expanded = scheduleDropdownExpanded,
                    onExpandedChange = {
                        if (!isSubmitting) scheduleDropdownExpanded = !scheduleDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = schedulePresets[selectedScheduleIndex].first,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.jobs_schedule_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scheduleDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSubmitting
                    )
                    ExposedDropdownMenu(
                        expanded = scheduleDropdownExpanded,
                        onDismissRequest = { scheduleDropdownExpanded = false }
                    ) {
                        schedulePresets.forEachIndexed { index, item ->
                            DropdownMenuItem(
                                text = { Text(item.first) },
                                onClick = {
                                    selectedScheduleIndex = index
                                    scheduleDropdownExpanded = false
                                    customScheduleError = null
                                    dialogError = null
                                }
                            )
                        }
                    }
                }

                if (schedulePresets[selectedScheduleIndex].second == "CUSTOM") {
                    MaterialTextField(
                        value = customScheduleText,
                        onValueChange = {
                            customScheduleText = it
                            customScheduleError = null
                            dialogError = null
                        },
                        label = "自定义调度表达式 (例如: @every 30s 或 */10 * * * *) *",
                        isError = customScheduleError != null,
                        supportingText = {
                            if (customScheduleError != null) {
                                Text(customScheduleError!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }

                // Network policy
                ExposedDropdownMenuBox(
                    expanded = netDropdownExpanded,
                    onExpandedChange = {
                        if (!isSubmitting) netDropdownExpanded = !netDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = networkPolicy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.jobs_net_policy_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = netDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSubmitting
                    )
                    ExposedDropdownMenu(
                        expanded = netDropdownExpanded,
                        onDismissRequest = { netDropdownExpanded = false }
                    ) {
                        netPolicies.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    networkPolicy = item
                                    netDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Battery policy
                ExposedDropdownMenuBox(
                    expanded = batDropdownExpanded,
                    onExpandedChange = {
                        if (!isSubmitting) batDropdownExpanded = !batDropdownExpanded
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = batteryPolicy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.jobs_bat_policy_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isSubmitting
                    )
                    ExposedDropdownMenu(
                        expanded = batDropdownExpanded,
                        onDismissRequest = { batDropdownExpanded = false }
                    ) {
                        batPolicies.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    batteryPolicy = item
                                    batDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.jobs_dry_run_label))
                    Switch(checked = dryRun, onCheckedChange = { dryRun = it }, enabled = !isSubmitting)
                }

                // Options
                Text(stringResource(R.string.jobs_advanced_options), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaterialTextField(
                        value = transfers,
                        onValueChange = {
                            transfers = it
                            transfersError = null
                            dialogError = null
                        },
                        label = "Transfers (1..32)",
                        modifier = Modifier.weight(1f),
                        isError = transfersError != null,
                        supportingText = {
                            if (transfersError != null) {
                                Text(transfersError!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                    MaterialTextField(
                        value = checkers,
                        onValueChange = {
                            checkers = it
                            checkersError = null
                            dialogError = null
                        },
                        label = "Checkers (1..64)",
                        modifier = Modifier.weight(1f),
                        isError = checkersError != null,
                        supportingText = {
                            if (checkersError != null) {
                                Text(checkersError!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
                MaterialTextField(
                    value = bwLimit,
                    onValueChange = { bwLimit = it },
                    label = stringResource(R.string.jobs_bw_limit_label)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.jobs_overwrite_label))
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it }, enabled = !isSubmitting)
                }

                if (type == "sync") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.jobs_delete_excluded_label))
                        Switch(checked = deleteExcluded, onCheckedChange = { deleteExcluded = it }, enabled = !isSubmitting)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    sourceError = null
                    destinationError = null
                    customScheduleError = null
                    transfersError = null
                    checkersError = null
                    dialogError = null

                    val src = source.text.trim()
                    val dest = destination.text.trim()
                    var hasError = false

                    if (src.isBlank()) {
                        sourceError = "源路径不能为空，例如 remote:path 或 /sdcard/..."
                        hasError = true
                    }

                    if (type != "delete" && dest.isBlank()) {
                        destinationError = "目标路径不能为空，例如 remote:path 或 /sdcard/..."
                        hasError = true
                    }

                    val isCustomSchedule = schedulePresets[selectedScheduleIndex].second == "CUSTOM"
                    val customSchedule = customScheduleText.text.trim()
                    if (isCustomSchedule && customSchedule.isBlank()) {
                        customScheduleError = "请输入有效的调度表达式 (如 @every 30s 或 */10 * * * *)"
                        hasError = true
                    }

                    val transfersVal = transfers.text.trim().toIntOrNull()
                    if (transfers.text.isNotBlank() && (transfersVal == null || transfersVal !in 1..32)) {
                        transfersError = "并发传输数范围为 1..32"
                        hasError = true
                    }

                    val checkersVal = checkers.text.trim().toIntOrNull()
                    if (checkers.text.isNotBlank() && (checkersVal == null || checkersVal !in 1..64)) {
                        checkersError = "并发检查数范围为 1..64"
                        hasError = true
                    }

                    if (hasError) {
                        dialogError = "表单存在未填写或格式错误的必填项，请检查标红提示"
                        return@Button
                    }

                    val optionsObj = JSONObject().apply {
                        transfersVal?.let { put("transfers", it) }
                        checkersVal?.let { put("checkers", it) }
                        if (bwLimit.text.isNotBlank()) put("bwLimit", bwLimit.text.trim())
                        if (overwrite) put("overwrite", true)
                        if (type == "sync" && deleteExcluded) put("deleteExcluded", true)
                    }.takeIf { it.length() > 0 }

                    val finalSchedule = if (isCustomSchedule) {
                        customSchedule.ifBlank { null }
                    } else {
                        schedulePresets[selectedScheduleIndex].second
                    }

                    isSubmitting = true
                    onSubmit(
                        type,
                        src,
                        dest,
                        finalSchedule,
                        networkPolicy,
                        batteryPolicy,
                        dryRun,
                        optionsObj,
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

enum class LogViewMode(val title: String) {
    STRUCTURED("结构化"),
    TERMINAL("控制台"),
    RAW("原始 JSON")
}

data class ParsedLogEntry(
    val raw: String,
    val timeFormatted: String,
    val level: String,
    val msg: String,
    val isStatsHeartbeat: Boolean,
    val stats: LogStatsData?
)

data class LogStatsData(
    val bytes: Long,
    val totalBytes: Long,
    val transfers: Long,
    val totalTransfers: Long,
    val errors: Long,
    val speed: Double,
    val elapsedTime: Double,
    val transferringFiles: List<String>
)

fun cleanRcloneMessage(rawMsg: String): String {
    return rawMsg.lines()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

fun parseRcloneLogs(logText: String): Pair<List<ParsedLogEntry>, LogStatsData?> {
    val lines = logText.lines().map { it.trim() }.filter { it.isNotBlank() }
    val entries = mutableListOf<ParsedLogEntry>()
    var latestStats: LogStatsData? = null

    for (line in lines) {
        if (line.startsWith("{") && line.endsWith("}")) {
            val json = runCatching { JSONObject(line) }.getOrNull()
            if (json != null) {
                val timeRaw = json.optString("time")
                val timeFormatted = if (timeRaw.contains("T")) {
                    timeRaw.substringAfter("T").substringBefore(".").take(8)
                } else timeRaw
                val level = json.optString("level", "info").uppercase(Locale.getDefault())
                val rawMsg = json.optString("msg").trim()
                val statsObj = json.optJSONObject("stats")
                val source = json.optString("source")
                val isStats = statsObj != null || source.contains("accounting/stats") || rawMsg.contains("Transferred:")

                val statsData = if (statsObj != null) {
                    val files = mutableListOf<String>()
                    val trArray = statsObj.optJSONArray("transferring")
                    if (trArray != null) {
                        for (i in 0 until trArray.length()) {
                            trArray.optJSONObject(i)?.optString("name")?.let { name ->
                                if (name.isNotBlank()) files.add(name)
                            }
                        }
                    }
                    LogStatsData(
                        bytes = statsObj.optLong("bytes", 0L),
                        totalBytes = statsObj.optLong("totalBytes", 0L),
                        transfers = statsObj.optLong("transfers", 0L),
                        totalTransfers = statsObj.optLong("totalTransfers", 0L),
                        errors = statsObj.optLong("errors", 0L),
                        speed = statsObj.optDouble("speed", 0.0),
                        elapsedTime = statsObj.optDouble("elapsedTime", 0.0),
                        transferringFiles = files
                    )
                } else null

                if (statsData != null) {
                    latestStats = statsData
                }

                entries.add(
                    ParsedLogEntry(
                        raw = line,
                        timeFormatted = timeFormatted,
                        level = level,
                        msg = rawMsg,
                        isStatsHeartbeat = isStats,
                        stats = statsData
                    )
                )
                continue
            }
        }

        // Plain text line
        val lvl = if (line.contains("error", ignoreCase = true)) "ERROR" else "INFO"
        entries.add(
            ParsedLogEntry(
                raw = line,
                timeFormatted = "",
                level = lvl,
                msg = line,
                isStatsHeartbeat = false,
                stats = null
            )
        )
    }

    return entries to latestStats
}
