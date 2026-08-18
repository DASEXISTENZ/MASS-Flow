package com.yuanqian.autofarm.data.datasource.update

import com.yuanqian.autofarm.constant.MaaApi
import com.yuanqian.autofarm.domain.service.update.resolver.ResourceDownloadUrlResolver

class GitHubResourceDownloadUrlResolver : ResourceDownloadUrlResolver {

    override suspend fun resolve(currentVersion: String): Result<String> {
        return Result.success(MaaApi.GITHUB_RESOURCE)
    }
}
