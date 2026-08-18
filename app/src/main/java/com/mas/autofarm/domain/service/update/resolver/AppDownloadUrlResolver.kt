package com.mas.autofarm.domain.service.update.resolver

import com.mas.autofarm.data.model.update.UpdateChannel

interface AppDownloadUrlResolver {
    suspend fun resolve(version: String, channel: UpdateChannel): Result<String>
}
