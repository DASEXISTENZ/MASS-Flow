package com.yuanqian.autofarm.domain.service.update.resolver

import com.yuanqian.autofarm.data.model.update.UpdateChannel

interface AppDownloadUrlResolver {
    suspend fun resolve(version: String, channel: UpdateChannel): Result<String>
}
