package com.mas.autofarm.data.datasource.update

import com.mas.autofarm.constant.MaaApi
import com.mas.autofarm.domain.service.update.resolver.ResourceDownloadUrlResolver

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(MaaApi.GITHUB_RESOURCE)
    }
}
