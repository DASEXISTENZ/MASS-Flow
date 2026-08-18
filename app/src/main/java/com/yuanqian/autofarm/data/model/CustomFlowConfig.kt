package com.yuanqian.autofarm.data.model

import com.yuanqian.autofarm.constant.Packages
import com.yuanqian.autofarm.maa.task.MaaTaskParams
import com.yuanqian.autofarm.maa.task.MaaTaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通用「工坊流程」任务：
 * - localFlowName 非空：直接由 FlowEngine 运行该工坊流程（流程信息节点负责启动应用）
 * - flowKind/detail：引用流程时写入的节点快照，用于配置展示（"点开能看到真实参数"）
 * - entryTask：兼容旧数据保留（本地流程为空时走 MaaCore Custom，当前已不使用）
 */
@Serializable
data class CustomFlowConfig(
    /** MaaCore tasks.json 入口任务名（localFlowName 为空时使用，兼容旧数据） */
    val entryTask: String = Packages.defaultTaskEntry,
    /** 本地工坊流程名（非空：直接用 FlowEngine 运行该流程，流程信息节点负责启动应用） */
    val localFlowName: String = "",
    /** 流程节点类型快照（INFO/TIME/IMAGE/ACTION），引用流程时写入 */
    val flowKind: String = "",
    /** 流程节点详情描述（如"等待1000ms"、"识别：xxx（阈值0.8）"），引用流程时写入 */
    val detail: String = "",
) : TaskParamProvider {
    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        val paramsJson = buildJsonObject {
            put("task", entryTask)
        }
        return listOf(MaaTaskParams(MaaTaskType.CUSTOM, paramsJson.toString()))
    }
}
