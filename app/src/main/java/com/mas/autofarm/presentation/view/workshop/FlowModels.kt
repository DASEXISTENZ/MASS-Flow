package com.mas.autofarm.presentation.view.workshop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * 原生工坊数据模型（逻辑链独立版）：
 * - 节点只是节点（时间/图像/动作），不含逻辑
 * - 逻辑全部在线上：是/非/与/或 四种逻辑链
 * - 是/非：单开端（图像识别结果）→ 末端
 * - 与/或：多开端汇聚 → 末端
 */

/** 节点类型：INFO=流程信息（第一个节点强制）；LOOP=循环（控制）；APP_STATE=应用状态（判定）
 * TAP/SWIPE/WAIT/BACK/INPUT=细分执行节点（参数存 action）；LOOP_START=循环起点（控制） */
@Serializable
enum class FlowNodeKind {
    INFO, TIME, IMAGE, ACTION, LOOP, APP_STATE, TAP, SWIPE, WAIT, BACK, INPUT, LOOP_START,
    CONJUNCTION, DISJUNCTION, LOOP_END,
}

@Serializable
data class FlowNode(
    val id: String,
    val name: String = "",
    val kind: FlowNodeKind = FlowNodeKind.ACTION,
    val x: Float = 0f,
    val y: Float = 0f,
    // 时间节点：等待时长 / 直到模板（可空）/ 直到超时
    val durationMs: Long = 1000,
    val untilTemplateId: String = "",
    val untilTimeoutMs: Long = 0,
    // 图像节点：模板识别 / 阈值 / ROI / 未命中重试
    val templateId: String = "",
    val threshold: Double = 0.8,
    val roi: List<Int>? = null,
    val retryIntervalMs: Long = 2000,
    val maxRetries: Int = 0,
    // 流程信息节点（INFO）：要打开的应用
    val appPackage: String = "",
    val launchApp: Boolean = true,
    // 动作节点
    val action: FlowAction = FlowAction.Wait(500),
    // 自定义节点（CustomNodeStore 模板）
    val customNodeId: String = "",
    val customParams: Map<String, String> = emptyMap(),
    // 虚拟屏分辨率偏好（"P720"/"P1600x720"/"P1080"，空=默认）
    val resolution: String = "",
    // 循环节点：times=次数循环（durationMs 存次数）/ until=直到识别（untilTemplateId/untilTimeoutMs）
    val loopMode: String = "times",
    // 循环终点：绑定的循环起点 id（无=🚫）；超时保护（0=不启用）
    val loopStartId: String = "",
    val loopTimeoutMs: Long = 0,
    // 等待直到判定：TIME/WAIT 节点可等待某判定节点为真（untilJudgeId），与 untilTemplateId 二选一
    val untilJudgeId: String = "",
    // 应用状态判定节点
    val appStatePkg: String = "",
    val appStateMode: String = "foreground", // foreground / alive
)

/** 逻辑链类型：顺序（普通顺序执行）/ 是 / 非 / 与 / 或 */
@Serializable
enum class LinkType { SEQUENCE, YES, NO, AND, OR }

/**
 * 逻辑链（连线）：
 * - YES/NO：fromIds 单开端（图像节点识别结果），toId 末端
 * - AND/OR：fromIds 多开端（全部满足=与，任一满足=或），toId 末端
 */
@Serializable
data class FlowLink(
    val id: String,
    val type: LinkType,
    val fromIds: List<String>,
    val toId: String,
)

@Serializable
sealed class FlowAction {
    /** 点击固定坐标（durationMs>0 为长按） */
    @Serializable @SerialName("Tap") data class Tap(val x: Int, val y: Int, val durationMs: Long = 60) : FlowAction()

    /** 点击模板命中位置中心 */
    @Serializable @SerialName("TapTemplate") data object TapTemplate : FlowAction()

    /** 等待 */
    @Serializable @SerialName("Wait") data class Wait(val ms: Long) : FlowAction()

    /** 返回（back） */
    @Serializable @SerialName("Back") data class Back(val times: Int = 1) : FlowAction()

    /** 文本输入 */
    @Serializable @SerialName("Input") data class Input(val text: String = "") : FlowAction()

    /** 滑动 */
    @Serializable @SerialName("Swipe") data class Swipe(
        val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 200,
    ) : FlowAction()
}

/** 突发判定：主流程之外的红色应急区间（中断处理） */
@Serializable
data class FlowBurst(
    val id: String,
    val name: String = "突发判定",
    /** 判定节点（区间内选1个识别节点：命中→触发突发，未命中→不改变路径） */
    val judgeNodeId: String,
    /** 命中后执行的节点区间（有序） */
    val nodeIds: List<String>,
    /** 命中执行完后的继续节点 */
    val hitContinueId: String,
    /** 未命中的继续节点（null = 不改变主流程路径） */
    val missContinueId: String? = null,
    /** 始终监听：流程运行期间突发始终生效（true） */
    val alwaysOn: Boolean = true,
)

@Serializable
data class FlowProject(
    val name: String = "未命名流程",
    val nodes: List<FlowNode> = emptyList(),
    val links: List<FlowLink> = emptyList(),
    val bursts: List<FlowBurst> = emptyList(),
    val templates: List<FlowTemplate> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class FlowTemplate(
    val id: String,
    val name: String,
    val file: String,
    val width: Int = 0,
    val height: Int = 0,
    val category: String = "",
    val recResolution: String = "",
)

/** 导出为 MaaFW Pipeline JSON（是→next，非→onError；与/或由 FlowEngine 运行时处理） */
fun FlowProject.toPipelineJson(): String {
    val tasks = buildJsonObject {
        nodes.forEach { node ->
            if (node.kind == FlowNodeKind.INFO) return@forEach
            val task = buildJsonObject {
                // 时间节点：等待
                if (node.kind == FlowNodeKind.TIME) {
                    put("action", "DoNothing")
                    put("preDelay", node.durationMs)
                }
                // 图像节点：模板匹配
                if (node.kind == FlowNodeKind.IMAGE) {
                    put("algorithm", "MatchTemplate")
                    put("template", node.templateId + ".png")
                    node.roi?.let { put("roi", buildJsonArray { it.forEach { v -> add(JsonPrimitive(v)) } }) }
                    put("threshold", node.threshold)
                }
                // 动作节点
                if (node.kind == FlowNodeKind.ACTION) {
                    when (val act = node.action) {
                        is FlowAction.Tap -> {
                            put("action", "Click")
                            put("specificRect", buildJsonArray {
                                add(JsonPrimitive(act.x)); add(JsonPrimitive(act.y)); add(JsonPrimitive(1)); add(JsonPrimitive(1))
                            })
                        }
                        is FlowAction.TapTemplate -> put("action", "ClickSelf")
                        is FlowAction.Wait -> put("action", "DoNothing")
                        is FlowAction.Back -> {
                            put("action", "Click")
                            put("specificRect", buildJsonArray {
                                add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(1)); add(JsonPrimitive(1))
                            })
                            put("times", act.times)
                        }
                        is FlowAction.Swipe -> {
                            put("action", "Swipe")
                            put("specificRect", buildJsonArray {
                                add(JsonPrimitive(act.fromX)); add(JsonPrimitive(act.fromY)); add(JsonPrimitive(1)); add(JsonPrimitive(1))
                            })
                            put("duration", act.durationMs)
                        }
                        is FlowAction.Input -> put("action", "DoNothing")
                    }
                }
                // 出口：是线 → next（MaaFW 识别成功路径）；非线 → onError
                val yesTo = links.filter { it.type == LinkType.YES && it.fromIds == listOf(node.id) }.map { it.toId }
                if (yesTo.isNotEmpty()) put("next", buildJsonArray { yesTo.forEach { add(JsonPrimitive(it)) } })
                val noTo = links.filter { it.type == LinkType.NO && it.fromIds == listOf(node.id) }.map { it.toId }
                if (noTo.isNotEmpty()) put("onError", buildJsonArray { noTo.forEach { add(JsonPrimitive(it)) } })
            }
            put(node.name, task)
        }
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), tasks)
}