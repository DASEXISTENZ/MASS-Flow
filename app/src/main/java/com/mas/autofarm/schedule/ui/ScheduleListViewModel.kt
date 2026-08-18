package com.mas.autofarm.schedule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mas.autofarm.data.model.TaskProfile
import com.mas.autofarm.data.preferences.TaskChainState
import com.mas.autofarm.schedule.data.ScheduleStrategyRepository
import com.mas.autofarm.schedule.model.ScheduleStrategy
import com.mas.autofarm.schedule.service.ScheduleAlarmManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

data class ScheduleListUiState(
    val strategies: List<ScheduleStrategy> = emptyList(),
    val profiles: List<TaskProfile> = emptyList(),
    val isLoading: Boolean = false
)

class ScheduleListViewModel(
    private val repository: ScheduleStrategyRepository,
    private val taskChainState: TaskChainState,
    private val alarmManager: ScheduleAlarmManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleListUiState())
    val state: StateFlow<ScheduleListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.strategies.collect { strategies ->
                _state.update { it.copy(strategies = strategies) }
            }
        }
        viewModelScope.launch {
            taskChainState.profiles.collect { profiles ->
                _state.update { it.copy(profiles = profiles) }
            }
        }
    }

    fun onToggleEnabled(strategyId: String, enabled: Boolean) {
        viewModelScope.launch {
            val strategy = repository.getById(strategyId) ?: return@launch
            val updated = strategy.copy(enabled = enabled)
            repository.setEnabled(strategyId, enabled)

            if (enabled) {
                alarmManager.scheduleNext(updated)
            } else {
                alarmManager.cancel(strategyId)
            }
        }
    }

    fun onDeleteStrategy(strategyId: String) {
        viewModelScope.launch {
            alarmManager.cancel(strategyId)
            repository.remove(strategyId)
        }
    }

    /** 计算策略的下次执行时间，用于 UI 显示 */
    fun getNextTriggerTime(strategy: ScheduleStrategy): String? {
        val next = alarmManager.computeNextTrigger(strategy)
        return next?.let {
            val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            it.format(formatter)
        }
    }
}
