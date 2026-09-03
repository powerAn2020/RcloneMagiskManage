package com.android.rclone.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.TextField as M3TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun ContentCard(modifier: Modifier = Modifier, insideMargin: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val body: @Composable () -> Unit = { Column(Modifier.padding(insideMargin), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, content = { body() }) else Card(modifier = modifier, content = { body() })
}

@Composable
fun PreferenceRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) }, modifier = Modifier.fillMaxWidth(), trailingContent = { Text("›") })
    M3TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("打开") }
}

@Composable
fun TogglePreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, summary: String) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) }, trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) })
}

@Composable
fun MaterialTextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, singleLine: Boolean = true, visualTransformation: VisualTransformation = VisualTransformation.None) {
    M3TextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = singleLine, visualTransformation = visualTransformation)
}

@Composable
fun TextButton(text: String, onClick: () -> Unit) {
    M3TextButton(onClick = onClick) { Text(text) }
}

@Composable
fun TextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, singleLine: Boolean = true, visualTransformation: VisualTransformation = VisualTransformation.None) {
    M3TextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = singleLine, visualTransformation = visualTransformation)
}

@Composable
fun MaterialDialog(show: Boolean, title: String, summary: String? = null, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    if (show) AlertDialog(onDismissRequest = onDismissRequest, title = { Text(title) }, text = { Column { summary?.let { Text(it); Spacer(Modifier.height(8.dp)) }; content() } }, confirmButton = {})
}
