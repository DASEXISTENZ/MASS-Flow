package com.yuanqian.autofarm.domain.service.update.checker

import com.yuanqian.autofarm.data.model.update.UpdateCheckResult
import com.yuanqian.autofarm.data.model.update.UpdateChannel

interface AppVersionChecker {
    suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult
}
