package com.yuanqian.autofarm.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TaskProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val chain: List<TaskChainNode>,
    /** 绑定的工坊流程名（非空：该配置是流程的镜像，流程更新后自动同步） */
    val boundFlowName: String? = null,
    /** 上次同步的流程 project.json 修改时间（用于检测流程更新） */
    val boundFlowSyncedAt: Long = 0L,
)
