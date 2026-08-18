package com.yuanqian.autofarm.data.model

import android.content.Context
import androidx.annotation.StringRes
import com.yuanqian.autofarm.R

/**
 * 任务类型（纯净骨架：仅保留通用任务）
 */
enum class TaskTypeInfo(
    @param:StringRes val nameRes: Int,
    val defaultConfig: () -> TaskParamProvider,
    val inDefaultChain: Boolean = true,
    val hidden: Boolean = false,
) {
    WAKE_UP(R.string.task_type_wake_up, { WakeUpConfig() }),
    USER_DATA_UPDATE(
        R.string.task_type_user_data_update,
        { UserDataUpdateConfig() },
        inDefaultChain = false,
        hidden = true,
    );

    fun defaultName(context: Context): String = context.getString(nameRes)
}