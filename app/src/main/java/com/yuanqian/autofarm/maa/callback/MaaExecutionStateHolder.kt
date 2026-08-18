package com.yuanqian.autofarm.maa.callback

import com.yuanqian.autofarm.domain.state.MaaExecutionState

interface MaaExecutionStateHolder {
    fun reportRunState(state: MaaExecutionState)
}
