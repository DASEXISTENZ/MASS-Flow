package com.yuanqian.autofarm.data.notification.provider

import com.yuanqian.autofarm.utils.i18n.UiText

sealed interface NotificationSendResult {
    data object Success : NotificationSendResult
    data class Failed(val message: UiText) : NotificationSendResult
    data class Transient(val message: UiText) : NotificationSendResult
}
