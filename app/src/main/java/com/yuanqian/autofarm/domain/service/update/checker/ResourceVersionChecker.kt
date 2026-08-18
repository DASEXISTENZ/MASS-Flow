package com.yuanqian.autofarm.domain.service.update.checker

import com.yuanqian.autofarm.data.model.update.UpdateCheckResult

interface ResourceVersionChecker {
    suspend fun check(currentVersion: String): UpdateCheckResult
}
