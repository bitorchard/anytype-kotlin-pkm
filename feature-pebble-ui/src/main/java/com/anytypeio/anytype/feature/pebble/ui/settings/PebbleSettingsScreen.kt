package com.anytypeio.anytype.feature.pebble.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PebbleSettingsScreen(
    viewModel: PebbleSettingsViewModel,
    onNavigateToQr: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pebble Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionHeader("Connection")
            SettingRow(label = "Webhook enabled") {
                Switch(
                    checked = settings.webhookEnabled,
                    onCheckedChange = viewModel::setWebhookEnabled
                )
            }
            PortField(
                port = settings.webhookPort,
                onPortChange = { viewModel.setWebhookPort(it) }
            )
            SettingRow(label = "Auth token") {
                TextButton(onClick = viewModel::regenerateToken) { Text("Regenerate") }
            }
            SettingRow(label = "Scan to configure") {
                TextButton(onClick = onNavigateToQr) { Text("Show QR") }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SectionHeader("LLM")

            LlmProviderDropdown(
                selected = settings.llmProvider,
                onSelected = viewModel::setLlmProvider
            )
            ApiKeyField(
                apiKey = settings.llmApiKey,
                onApiKeyChange = viewModel::setLlmApiKey
            )
            LlmModelField(
                model = settings.llmModel,
                onModelChange = viewModel::setLlmModel,
                provider = settings.llmProvider
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SectionHeader("Approval")

            SettingRow(label = "Auto-approve") {
                Switch(
                    checked = settings.autoApproveEnabled,
                    onCheckedChange = viewModel::setAutoApproveEnabled
                )
            }
            SliderRow(
                label = "Auto-approve threshold",
                value = settings.autoApproveThreshold,
                onValueChange = viewModel::setAutoApproveThreshold
            )
            SliderRow(
                label = "Create-new threshold",
                value = settings.createNewThreshold,
                onValueChange = viewModel::setCreateNewThreshold
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SectionHeader("Debug & Logs")

            SettingRow(label = "View debug trace") {
                TextButton(onClick = onNavigateToDebug) { Text("Open") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun PortField(port: Int, onPortChange: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            value.toIntOrNull()?.let(onPortChange)
        },
        label = { Text("Webhook port") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ApiKeyField(apiKey: String, onApiKeyChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("LLM API key") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide" else "Show")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmProviderDropdown(selected: LlmProvider, onSelected: (LlmProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("LLM provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(vertical = 4.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LlmProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = { onSelected(provider); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmModelField(model: String, onModelChange: (String) -> Unit, provider: LlmProvider) {
    val models = when (provider) {
        LlmProvider.ANTHROPIC -> listOf("claude-sonnet-4-5", "claude-opus-4-5", "claude-haiku-3-5")
        LlmProvider.OPENAI -> listOf("gpt-4o", "gpt-4o-mini", "o1-preview")
    }
    var expanded by remember(provider) { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = model,
            onValueChange = {},
            readOnly = true,
            label = { Text("LLM model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(vertical = 4.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    onClick = { onModelChange(m); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f))
            Text(text = "%.2f".format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
