package com.mas.autofarm.data.model

import com.mas.autofarm.data.repository.DepotRepository
import com.mas.autofarm.data.repository.OperBoxRepository
import com.mas.autofarm.data.resource.ActivityManager
import com.mas.autofarm.data.resource.ItemHelper
import com.mas.autofarm.data.resource.ResourceDataManager
import com.mas.autofarm.domain.service.FightDropsRefresher
import com.mas.autofarm.utils.i18n.UiText

/**
 * 展开环境：只读世界状态 + 本趟 [appendLog] / [FightDropsRefresher.stage]。
 * 非值对象；配置类不得反向抓依赖。
 */
class TaskParamContext(
    val node: TaskChainNode,
    val clientType: String,
    val activityManager: ActivityManager,
    val depotRepository: DepotRepository,
    val operBoxRepository: OperBoxRepository,
    val itemHelper: ItemHelper,
    val resourceDataManager: ResourceDataManager,
    val dropsRefresher: FightDropsRefresher,
    val logSink: PreflightLogSink,
) {
    fun appendLog(text: UiText, level: LogLevel = LogLevel.INFO) {
        logSink.append(text, level)
    }
}
