package com.yuanqian.autofarm.data.model

import com.yuanqian.autofarm.data.repository.DepotRepository
import com.yuanqian.autofarm.data.repository.OperBoxRepository
import com.yuanqian.autofarm.data.resource.ActivityManager
import com.yuanqian.autofarm.data.resource.ItemHelper
import com.yuanqian.autofarm.data.resource.ResourceDataManager
import com.yuanqian.autofarm.domain.service.FightDropsRefresher
import com.yuanqian.autofarm.utils.i18n.UiText

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
