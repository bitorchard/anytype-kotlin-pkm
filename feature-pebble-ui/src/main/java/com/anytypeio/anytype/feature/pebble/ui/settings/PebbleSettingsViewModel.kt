package com.anytypeio.anytype.feature.pebble.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class PebbleSettingsViewModel @Inject constructor(
    private val repo: PebbleSettingsRepository
) : ViewModel() {

    val settings: StateFlow<PebbleSettings> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PebbleSettings())

    fun setWebhookEnabled(enabled: Boolean) = save { copy(webhookEnabled = enabled) }
    fun setWebhookPort(port: Int) = save { copy(webhookPort = port) }
    fun setLlmProvider(provider: LlmProvider) = save { copy(llmProvider = provider) }
    fun setLlmApiKey(key: String) = save { copy(llmApiKey = key) }
    fun setLlmModel(model: String) = save { copy(llmModel = model) }
    fun setAutoApproveEnabled(enabled: Boolean) = save { copy(autoApproveEnabled = enabled) }
    fun setAutoApproveThreshold(value: Float) = save { copy(autoApproveThreshold = value) }
    fun setCreateNewThreshold(value: Float) = save { copy(createNewThreshold = value) }
    fun regenerateToken() = save { copy(webhookAuthToken = java.util.UUID.randomUUID().toString().take(32)) }

    private fun save(block: PebbleSettings.() -> PebbleSettings) {
        viewModelScope.launch { repo.update(block) }
    }
}
