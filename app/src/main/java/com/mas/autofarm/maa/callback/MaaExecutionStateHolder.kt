package com.mas.autofarm.maa.callback

import com.mas.autofarm.domain.state.MaaExecutionState

interface MaaExecutionStateHolder {
    fun reportRunState(state: MaaExecutionState)
}
