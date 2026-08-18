package com.mas.autofarm.data.preferences

import com.mas.autofarm.data.model.TaskProfile
import com.mas.autofarm.data.notification.NotificationSettings
import com.mas.autofarm.domain.models.AppSettings
import com.mas.autofarm.schedule.model.ScheduleStrategy
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
