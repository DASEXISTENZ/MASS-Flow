package com.yuanqian.autofarm.presentation.state

import com.yuanqian.autofarm.presentation.view.workshop.FlowEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局共享的工坊流程运行时状态（跨页面：后台任务页 / 小工具页共享同一实例）。
 * 避免底部导航各 destination 各自持有 BackgroundTaskViewModel 导致状态不可见。
 */
object FlowRuntimeHolder {

    private val _flowRunning = MutableStateFlow(false)
    val flowRunning: StateFlow<Boolean> = _flowRunning.asStateFlow()

    private val _flowPaused = MutableStateFlow(false)
    val flowPaused: StateFlow<Boolean> = _flowPaused.asStateFlow()

    /** 当前运行的引擎（供暂停/停止），由 ViewModel 生命周期管理 */
    @Volatile
    var engine: FlowEngine? = null
        private set

    /** 流程引擎启动时调用 */
    fun onEngineStart(e: FlowEngine) {
        engine = e
        _flowRunning.value = true
        _flowPaused.value = false
    }

    /** 流程引擎结束（无论正常/错误/停止）时调用 */
    fun onEngineEnd() {
        engine = null
        _flowRunning.value = false
        _flowPaused.value = false
    }

    /** 暂停/继续切换；无引擎时不生效 */
    fun togglePause() {
        val e = engine ?: return
        if (e.isPaused) {
            e.resume()
            _flowPaused.value = false
        } else {
            e.pause()
            _flowPaused.value = true
        }
    }
}
