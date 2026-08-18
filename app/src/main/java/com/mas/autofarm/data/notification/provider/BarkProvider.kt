package com.mas.autofarm.data.notification.provider

import com.mas.autofarm.R
import com.mas.autofarm.data.api.HttpClientHelper
import com.mas.autofarm.data.notification.NotificationSettingsManager
import com.mas.autofarm.utils.JsonUtils
import com.mas.autofarm.utils.i18n.uiTextOf
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.net.URI

class BarkProvider(
    private val httpClient: HttpClientHelper,
    private val settingsManager: NotificationSettingsManager
) : NotificationProvider {

    override val id = "Bark"

    override suspend fun send(title: String, content: String): NotificationSendResult {
        val settings = settingsManager.settings.first()
        val barkServer = settings.barkServer.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_server_empty)
            )
        val sendKey = settings.barkSendKey.takeIf { it.isNotEmpty() }
            ?: return NotificationSendResult.Failed(
                uiTextOf(R.string.notification_err_bark_key_empty)
            )

        val normalizedBase = barkServer.trimEnd('/') + "/"
        val url = URI.create(normalizedBase).resolve("push").toString()
        val body = JsonUtils.common.encodeToString(
            BarkRequest(
                title = title,
                body = content,
                deviceKey = sendKey,
            )
        )

        return runCatching {
            httpClient.post(url, body).use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful &&
                    JsonUtils.common.decodeFromString<BarkResponse>(responseBody).code == 200
                ) {
                    NotificationSendResult.Success
                } else {
                    Timber.w("Bark rejected: HTTP %d, body=%s", response.code, responseBody)
                    NotificationSendResult.Failed(
                        uiTextOf(R.string.notification_err_http_status, response.code),
                    )
                }
            }
        }.getOrElse {
            Timber.e(it, "Bark send failed")
            NotificationSendResult.Transient(uiTextOf(R.string.notification_err_network))
        }
    }

    @Serializable
    private data class BarkRequest(
        val title: String,
        val body: String,
        @SerialName("device_key") val deviceKey: String,
        val group: String = "com.mas.autofarm",
        val icon: String = "",
    )

    @Serializable
    private data class BarkResponse(
        val code: Int = -1,
    )
}
