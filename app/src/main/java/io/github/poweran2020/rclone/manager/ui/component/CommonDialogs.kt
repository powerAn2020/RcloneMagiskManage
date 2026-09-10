package io.github.poweran2020.rclone.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.poweran2020.rclone.manager.R

@Composable
fun DangerousConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    tokenBadge: String? = null,
    showTokenValue: Boolean = false,
    confirmLabel: String? = null,
    isLoading: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    val resolvedConfirmLabel = confirmLabel ?: stringResource(R.string.action_confirm_execute)
    var remainingSeconds by remember(show, tokenBadge) { mutableStateOf(60) }
    LaunchedEffect(show, tokenBadge) {
        if (!show || tokenBadge.isNullOrBlank()) return@LaunchedEffect
        remainingSeconds = 60
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds--
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) onDismiss()
        },
        icon = {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (!tokenBadge.isNullOrBlank()) {
                    if (remainingSeconds > 0) {
                        val tokenText = if (showTokenValue) {
                            stringResource(R.string.token_badge_with_val, tokenBadge, remainingSeconds)
                        } else {
                            stringResource(R.string.token_badge_no_val, remainingSeconds)
                        }
                        Text(
                            tokenText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSeconds <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            fontWeight = if (remainingSeconds <= 15) FontWeight.Bold else FontWeight.Normal
                        )
                    } else {
                        Text(
                            stringResource(R.string.token_expired_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (isLoading) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.action_executing_wait),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading && (tokenBadge.isNullOrBlank() || remainingSeconds > 0),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_executing))
                } else {
                    Text(resolvedConfirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LoadingProgressDialog(
    show: Boolean,
    message: String? = null
) {
    if (!show) return
    val resolvedMessage = message ?: stringResource(R.string.action_processing_wait)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = resolvedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
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
        title = { Text(stringResource(R.string.token_editor_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.token_keystore_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                MaterialTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.token_secret_label),
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(text.text.trim())
                onDismiss()
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun SimpleInputDialog(
    show: Boolean,
    title: String,
    fields: List<Pair<String, Boolean>>, // Label to isPassword
    confirmLabel: String? = null,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    val resolvedConfirmLabel = confirmLabel ?: stringResource(R.string.action_submit)
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
                Text(resolvedConfirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun RootPermissionDialog(
    show: Boolean,
    isRetrying: Boolean = false,
    onRetry: () -> Unit,
    onOpenManager: (() -> Unit)? = null,
    onExit: () -> Unit
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = { /* 阻断式弹窗，禁止点击外部关闭 */ },
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = stringResource(R.string.root_perm_icon_desc),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(stringResource(R.string.root_perm_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.root_perm_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = stringResource(R.string.root_perm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRetry,
                enabled = !isRetrying
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.root_status_checking))
                } else {
                    Text(stringResource(R.string.root_btn_grant))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpenManager != null) {
                    TextButton(onClick = onOpenManager) {
                        Text(stringResource(R.string.root_perm_open_manager))
                    }
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.action_exit))
                }
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}
