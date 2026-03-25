package com.anytypeio.anytype.feature.pebble.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pebbleDataStore by preferencesDataStore(name = "pebble_settings")

@Singleton
class PebbleSettingsRepository @Inject constructor(private val context: Context) {

    private val store get() = context.pebbleDataStore

    /** Synchronously returns the last-observed settings snapshot, or null if not yet loaded. */
    private var _snapshot: PebbleSettings? = null

    fun currentSnapshot(): PebbleSettings? = _snapshot

    fun observe(): Flow<PebbleSettings> = store.data.map { prefs ->
        PebbleSettings(
            webhookEnabled = prefs[WEBHOOK_ENABLED] ?: true,
            webhookPort = prefs[WEBHOOK_PORT] ?: 8391,
            webhookAuthToken = prefs[WEBHOOK_TOKEN] ?: generateToken(prefs),
            llmProvider = LlmProvider.valueOf(prefs[LLM_PROVIDER] ?: LlmProvider.ANTHROPIC.name),
            llmApiKey = prefs[LLM_API_KEY] ?: "",
            llmModel = prefs[LLM_MODEL] ?: "claude-sonnet-4-5",
            autoApproveEnabled = prefs[AUTO_APPROVE_ENABLED] ?: false,
            autoApproveThreshold = prefs[AUTO_APPROVE_THRESHOLD] ?: 0.85f,
            createNewThreshold = prefs[CREATE_NEW_THRESHOLD] ?: 0.50f
        ).also { _snapshot = it }
    }

    suspend fun update(block: PebbleSettings.() -> PebbleSettings) {
        store.edit { prefs ->
            val current = PebbleSettings(
                webhookEnabled = prefs[WEBHOOK_ENABLED] ?: true,
                webhookPort = prefs[WEBHOOK_PORT] ?: 8391,
                webhookAuthToken = prefs[WEBHOOK_TOKEN] ?: UUID.randomUUID().toString(),
                llmProvider = LlmProvider.valueOf(prefs[LLM_PROVIDER] ?: LlmProvider.ANTHROPIC.name),
                llmApiKey = prefs[LLM_API_KEY] ?: "",
                llmModel = prefs[LLM_MODEL] ?: "claude-sonnet-4-5",
                autoApproveEnabled = prefs[AUTO_APPROVE_ENABLED] ?: false,
                autoApproveThreshold = prefs[AUTO_APPROVE_THRESHOLD] ?: 0.85f,
                createNewThreshold = prefs[CREATE_NEW_THRESHOLD] ?: 0.50f
            )
            val updated = current.block()
            prefs[WEBHOOK_ENABLED] = updated.webhookEnabled
            prefs[WEBHOOK_PORT] = updated.webhookPort
            prefs[WEBHOOK_TOKEN] = updated.webhookAuthToken
            prefs[LLM_PROVIDER] = updated.llmProvider.name
            prefs[LLM_API_KEY] = updated.llmApiKey
            prefs[LLM_MODEL] = updated.llmModel
            prefs[AUTO_APPROVE_ENABLED] = updated.autoApproveEnabled
            prefs[AUTO_APPROVE_THRESHOLD] = updated.autoApproveThreshold
            prefs[CREATE_NEW_THRESHOLD] = updated.createNewThreshold
        }
    }

    private fun generateToken(prefs: androidx.datastore.preferences.core.Preferences): String {
        // Token is lazily generated on first read; the caller's update() will persist it.
        return prefs[WEBHOOK_TOKEN] ?: UUID.randomUUID().toString().take(32)
    }

    companion object {
        val WEBHOOK_ENABLED = booleanPreferencesKey("webhook_enabled")
        val WEBHOOK_PORT = intPreferencesKey("webhook_port")
        val WEBHOOK_TOKEN = stringPreferencesKey("webhook_token")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val AUTO_APPROVE_ENABLED = booleanPreferencesKey("auto_approve_enabled")
        val AUTO_APPROVE_THRESHOLD = floatPreferencesKey("auto_approve_threshold")
        val CREATE_NEW_THRESHOLD = floatPreferencesKey("create_new_threshold")
    }
}
