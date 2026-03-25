package com.anytypeio.anytype.feature.pebble.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightspark.composeqr.QrCodeView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhookQrScreen(
    viewModel: WebhookQrViewModel,
    onSendTestInput: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan to Configure") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.qrPayload.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    QrCodeView(
                        data = state.qrPayload,
                        modifier = Modifier.size(240.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            IpDropdown(
                addresses = state.localIpAddresses,
                selected = state.selectedIp,
                onSelect = viewModel::selectIp
            )

            Spacer(Modifier.height(8.dp))

            val webhookUrl = "http://${state.selectedIp}:${state.port}/api/v1/input"
            OutlinedTextField(
                value = webhookUrl,
                onValueChange = {},
                readOnly = true,
                label = { Text("Webhook URL") },
                trailingIcon = {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(webhookUrl)) }) {
                        Text("Copy")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.token,
                onValueChange = {},
                readOnly = true,
                label = { Text("Auth token") },
                visualTransformation = if (state.tokenVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { viewModel.setTokenVisible(!state.tokenVisible) }) {
                        Text(if (state.tokenVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (state.tokenVisible) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.token)) }) {
                    Text("Copy token")
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = onSendTestInput) {
                Text("Send Test Input →")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IpDropdown(
    addresses: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("IP address") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            addresses.forEach { (iface, ip) ->
                DropdownMenuItem(
                    text = { Text("$ip ($iface)", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onSelect(ip); expanded = false }
                )
            }
        }
    }
}
