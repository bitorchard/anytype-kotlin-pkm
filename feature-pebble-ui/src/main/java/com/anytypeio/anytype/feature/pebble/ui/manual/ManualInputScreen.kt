package com.anytypeio.anytype.feature.pebble.ui.manual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Simple text-entry form that lets developers and first-time users submit a voice-note text
 * directly (bypassing the Pebble ring + webhook) to test the assimilation pipeline.
 *
 * The submitted input is enqueued via [ManualInputViewModel] → [InputQueue] and processed
 * by the background [InputProcessor] just like a real Pebble input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualInputScreen(
    viewModel: ManualInputViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test Input") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ManualInputState.Idle, is ManualInputState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.TopCenter)
                    ) {
                        Text(
                            text = "Enter a note as if spoken to your Pebble ring.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            label = { Text("Note text") },
                            placeholder = { Text("e.g. \"Aarav has a basketball game on Friday at 5pm\"") },
                            maxLines = 6
                        )
                        if (s is ManualInputState.Error) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Error: ${s.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.submit(inputText) },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Submit")
                        }
                    }
                }

                is ManualInputState.Submitting -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is ManualInputState.Submitted -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Submitted",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Input queued for processing in background.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "ID: ${s.inputId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to dashboard")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                inputText = ""
                                viewModel.reset()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Submit another")
                        }
                    }
                }
            }
        }
    }
}
