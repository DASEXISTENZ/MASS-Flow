package com.mas.autofarm.domain.service.update.checker

import com.mas.autofarm.data.model.update.UpdateCheckResult

interface ResourceVersionChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
