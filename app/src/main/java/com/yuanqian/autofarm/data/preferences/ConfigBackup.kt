package com.yuanqian.autofarm.data.preferences

import com.yuanqian.autofarm.data.model.TaskProfile
import com.yuanqian.autofarm.data.notification.NotificationSettings
import com.yuanqian.autofarm.domain.models.AppSettings
import com.yuanqian.autofarm.schedule.model.ScheduleStrategy
import kotlinx.serialization.Serializable

@Serializable
data class ConfigBackup(
    val version: Int = 1,
    val exportedAt: String = "",
    val appSettings: AppSettings,
    val notificationSettings: NotificationSettings,
    val taskProfiles: List<TaskProfile>,
    val activeProfileId: String,
    val scheduleStrategies: List<ScheduleStrategy>
)
