package com.android.rclone.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun DangerousConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    tokenBadge: String? = null,
    confirmLabel: String = "确认执行",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (!tokenBadge.isNullOrBlank()) {
                    Text(
                        "确认令牌: $tokenBadge (60 秒后失效)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun TokenEditorDialog(
    show: Boolean,
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    var text by remember(show, initialValue) { mutableStateOf(TextFieldValue(initialValue)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gateway Bearer Token", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Token 将使用 Android Keystore (AES-GCM) 硬件加密保存，明文绝不写入任何文件或调试日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                MaterialTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Token 密钥",
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(text.text.trim())
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun SimpleInputDialog(
    show: Boolean,
    title: String,
    fields: List<Pair<String, Boolean>>, // Label to isPassword
    confirmLabel: String = "提交",
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    var values by remember(show, fields) { mutableStateOf(fields.map { TextFieldValue("") }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { index, (label, isPassword) ->
                    MaterialTextField(
                        value = values[index],
                        onValueChange = { updated ->
                            values = values.toMutableList().also { it[index] = updated }
                        },
                        label = label,
                        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(values.map { it.text.trim() })
                onDismiss()
            }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
