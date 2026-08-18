package com.yuanqian.autofarm.domain.models

import com.yuanqian.autofarm.constant.DisplayMode

enum class RunMode(
    val displayMode: Int
) {
    FOREGROUND(DisplayMode.PRIMARY),

    BACKGROUND(DisplayMode.BACKGROUND)
}
