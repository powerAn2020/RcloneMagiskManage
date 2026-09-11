package io.github.poweran2020.rclone.manager.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.poweran2020.rclone.manager.GatewayClient
import kotlinx.coroutines.launch

@Composable
fun CoreLogDialog(
    show: Boolean,
    client: GatewayClient,
    bearer: String? = null,
    onDismiss: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lineCount by remember { mutableStateOf(500) }
    var logText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val fetchLogs = {
        scope.launch {
            isLoading = true
            client.readCoreLogs(lineCount, bearer).fold(
                onSuccess = { content ->
                    logText = content
                    isLoading = false
                },
                onFailure = { err ->
                    logText = "[读取失败] ${err.message ?: "无法读取核心日志"}"
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(show, lineCount) {
        if (show) {
            fetchLogs()
        }
    }

    val lines = remember(logText) {
        if (logText.isBlank()) emptyList() else logText.lines()
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "核心运行日志",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "gateway.log (${lines.size} 行)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { fetchLogs() },
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新日志", modifier = Modifier.size(18.dp))
                        }
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("gateway-core-log", logText))
                            onShowMessage("核心日志已复制到剪贴板")
                        },
                        enabled = logText.isNotBlank(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = !isLoading && logText.isNotBlank(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "清空日志",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 行数筛选 Chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(100, 300, 500, 1000).forEach { count ->
                        FilterChip(
                            selected = lineCount == count,
                            onClick = { lineCount = count },
                            label = { Text("${count}行", style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                // 终端风格日志显示框
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF18181B) // 深灰/黑底终端风
                ) {
                    if (isLoading && lines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else if (lines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "暂无运行日志或日志为空",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA1A1AA)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(lines) { line ->
                                val textColor = when {
                                    line.contains("ERROR", true) || line.contains("failed", true) -> Color(0xFFF87171) // 红
                                    line.contains("WARN", true) -> Color(0xFFFBBF24) // 黄
                                    line.contains("INFO", true) -> Color(0xFF60A5FA) // 蓝
                                    line.contains("DEBUG", true) -> Color(0xFF9CA3AF) // 灰
                                    else -> Color(0xFFE4E4E7) // 白灰
                                }
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("关闭")
            }
        }
    )

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isClearing) showClearConfirm = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认清空核心运行日志？", fontWeight = FontWeight.Bold) },
            text = { Text("将截断并清空 /data/adb/rclone-manage/logs/gateway.log 文件内容，该操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isClearing = true
                            client.clearCoreLogs(bearer).fold(
                                onSuccess = {
                                    isClearing = false
                                    showClearConfirm = false
                                    onShowMessage("核心日志已清空")
                                    fetchLogs()
                                },
                                onFailure = { err ->
                                    isClearing = false
                                    onShowMessage("清空失败: ${err.message}")
                                }
                            )
                        }
                    },
                    enabled = !isClearing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isClearing) "正在清空…" else "确认清空")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    enabled = !isClearing
                ) {
                    Text("取消")
                }
            }
        )
    }
}
