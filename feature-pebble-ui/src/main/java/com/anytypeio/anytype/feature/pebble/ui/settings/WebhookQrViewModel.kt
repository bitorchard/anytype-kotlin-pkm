package com.anytypeio.anytype.feature.pebble.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import javax.inject.Inject

data class QrUiState(
    val localIpAddresses: List<Pair<String, String>> = emptyList(),
    val selectedIp: String = "",
    val port: Int = 8391,
    val token: String = "",
    val qrPayload: String = "",
    val tokenVisible: Boolean = false
)

class WebhookQrViewModel @Inject constructor(
    private val settingsRepo: PebbleSettingsRepository
) : ViewModel() {

    private val _selectedIp = MutableStateFlow("")
    private val _tokenVisible = MutableStateFlow(false)
    private val _ipAddresses = MutableStateFlow(getLocalIpAddresses())

    val state: StateFlow<QrUiState> = combine(
        settingsRepo.observe(),
        _selectedIp,
        _tokenVisible,
        _ipAddresses
    ) { settings, selectedIp, tokenVisible, ips ->
        val resolvedIp = selectedIp.ifEmpty { ips.firstOrNull()?.second ?: "127.0.0.1" }
        QrUiState(
            localIpAddresses = ips,
            selectedIp = resolvedIp,
            port = settings.webhookPort,
            token = settings.webhookAuthToken,
            qrPayload = buildQrPayload(resolvedIp, settings.webhookPort, settings.webhookAuthToken),
            tokenVisible = tokenVisible
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, QrUiState())

    init {
        // Ensure a token is set on first launch.
        viewModelScope.launch {
            settingsRepo.update {
                if (webhookAuthToken.isEmpty()) copy(webhookAuthToken = generateToken())
                else this
            }
        }
    }

    fun selectIp(ip: String) { _selectedIp.value = ip }
    fun setTokenVisible(visible: Boolean) { _tokenVisible.value = visible }
    fun refreshIpAddresses() { _ipAddresses.value = getLocalIpAddresses() }

    private fun buildQrPayload(ip: String, port: Int, token: String): String {
        val map = mapOf(
            "url" to "http://$ip:$port/api/v1/input",
            "token" to token,
            "version" to 1
        )
        return Json.encodeToString(map)
    }

    private fun getLocalIpAddresses(): List<Pair<String, String>> = buildList {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.inetAddresses?.asSequence()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        add(iface.displayName to addr.hostAddress)
                    }
                }
            }
        }
    }.ifEmpty { listOf("lo" to "127.0.0.1") }

    private fun generateToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(32)
}
