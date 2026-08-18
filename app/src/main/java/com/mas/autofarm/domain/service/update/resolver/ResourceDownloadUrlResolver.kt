package com.mas.autofarm.domain.service.update.resolver

interface ResourceDownloadUrlResolver {
    suspend fun resolve(currentVersion: String): Result<String>
}
