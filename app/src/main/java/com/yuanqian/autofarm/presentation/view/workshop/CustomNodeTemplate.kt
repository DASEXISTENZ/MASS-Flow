package com.yuanqian.autofarm.presentation.view.workshop

import kotlinx.serialization.Serializable

/**
 * 自定义节点模板：通过 JSON 定义新的节点类型，系统自动识别。
 * category：EXECUTE（执行）/ JUDGE（判定）/ CONTROL（控制）
 * params：可调整参数，command 中用 {key} 占位符引用。
 */
@Serializable
data class CustomNodeParam(
    val key: String = "",
    val label: String = "",
    val type: String = "text", // text / number
    val default: String = "",
)

@Serializable
data class CustomNodeTemplate(
    val id: String = "",
    val name: String = "",
    val category: String = "EXECUTE", // EXECUTE / JUDGE / CONTROL
    val description: String = "",
    val params: List<CustomNodeParam> = emptyList(),
    /** 执行命令，{key} 会被参数值替换；判定类按退出码（0=成功→是线） */
    val command: String = "",
)

/** 节点类型中文标签 */
fun CustomNodeTemplate.categoryLabel(): String = when (category) {
    "JUDGE" -> "判定"
    "CONTROL" -> "控制"
    else -> "执行"
}