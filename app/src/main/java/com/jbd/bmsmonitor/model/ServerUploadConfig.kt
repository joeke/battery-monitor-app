package com.jbd.bmsmonitor.model

data class ServerUploadConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean
        get() = enabled && serverUrl.isNotBlank() && apiKey.isNotBlank()
}
