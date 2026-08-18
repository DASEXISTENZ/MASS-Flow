package com.mas.autofarm.data.datasource.update

import com.mas.autofarm.R
import com.mas.autofarm.constant.MaaApi
import com.mas.autofarm.data.api.CdkRequiredException
import com.mas.autofarm.data.api.MirrorChyanApiClient
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.domain.service.update.resolver.ResourceDownloadUrlResolver
import com.mas.autofarm.utils.i18n.LocalizedException
import com.mas.autofarm.utils.i18n.uiTextOf

class MirrorChyanResourceDownloadUrlResolver(
    private val apiClient: MirrorChyanApiClient,
    private val appSettingsManager: AppSettingsManager
) : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        val cdk = appSettingsManager.mirrorChyanCdk.value
        if (cdk.isBlank()) {
            return Result.failure(CdkRequiredException())
        }

        return apiClient.getLatest(
            MaaApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow",
                "cdk" to cdk
            )
        ).map { data ->
            data.url ?: throw LocalizedException(uiTextOf(R.string.update_error_empty_download_url))
        }
    }
}
