package com.mas.autofarm.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.mas.autofarm.presentation.state.UiEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 全局应用事件：MAS 精简版（成就已移除，事件流恒空）。 */
class AppEventsViewModel() : ViewModel() {
    val effects: Flow<UiEffect> = emptyFlow()
}
