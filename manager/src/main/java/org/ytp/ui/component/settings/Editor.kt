package org.ytp.ui.component.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SettingsEditor(
    modifier: Modifier,
    label: String,
    text: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        label = { Text(label) },
        onValueChange = onValueChange,
        singleLine = true,
        shape = androidx.compose.material3.MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    )
}

@Preview
@Composable
private fun SettingsCheckBoxPreview() {
    Column {
        SettingsEditor(
            Modifier.padding(horizontal = 8.dp),
            "标签",
            "编辑框文字",
            onValueChange = {

            },
        )
    }
}
