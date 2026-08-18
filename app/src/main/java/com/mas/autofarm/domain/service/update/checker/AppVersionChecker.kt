package com.mas.autofarm.domain.service.update.checker

import com.mas.autofarm.data.model.update.UpdateCheckResult
import com.mas.autofarm.data.model.update.UpdateChannel

interface AppVersionChecker {
    suspend fun check(current: String, channel: UpdateChannel): UpdateCheckResult
}
