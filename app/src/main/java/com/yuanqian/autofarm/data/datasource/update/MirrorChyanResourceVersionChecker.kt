package com.yuanqian.autofarm.data.datasource.update

import com.yuanqian.autofarm.constant.MaaApi
import com.yuanqian.autofarm.data.api.MirrorChyanApiClient
import com.yuanqian.autofarm.data.api.MirrorChyanBizException
import com.yuanqian.autofarm.data.datasource.ResourceDownloader
import com.yuanqian.autofarm.data.model.update.UpdateCheckResult
import com.yuanqian.autofarm.data.model.update.UpdateError
import com.yuanqian.autofarm.data.model.update.UpdateInfo
import com.yuanqian.autofarm.domain.service.update.checker.ResourceVersionChecker

class MirrorChyanResourceVersionChecker(
    private val apiClient: MirrorChyanApiClient
) : ResourceVersionChecker {

    override suspend fun check(currentVersion: String): UpdateCheckResult {
        val result = apiClient.getLatest(
            MaaApi.MIRROR_CHYAN_RESOURCE,
            query = mapOf(
                "current_version" to currentVersion,
                "user_agent" to "MAA-Meow"
            )
        )

        return result.fold(
            onSuccess = { data ->
                val remoteVersion = data.versionName
                if (remoteVersion.isEmpty() || ResourceDownloader.compareVersions(currentVersion, remoteVersion) >= 0) {
                    UpdateCheckResult.UpToDate(currentVersion)
                } else {
                    UpdateCheckResult.Available(
                        UpdateInfo(
                            version = ResourceDownloader.formatVersionForDisplay(remoteVersion),
                            releaseNote = data.releaseNote
                        )
                    )
                }
            },
            onFailure = { e ->
                when (e) {
                    is MirrorChyanBizException -> UpdateCheckResult.Error(e.toUpdateError())
                    else -> UpdateCheckResult.Error(UpdateError.NetworkError(e.message))
                }
            }
        )
    }
}
