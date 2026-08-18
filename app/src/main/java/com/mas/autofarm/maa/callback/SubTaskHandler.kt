package com.mas.autofarm.maa.callback

import android.content.Context
import com.alibaba.fastjson2.JSONObject
import com.mas.autofarm.R
import com.mas.autofarm.data.model.LogItem
import com.mas.autofarm.data.model.LogLevel
import com.mas.autofarm.data.preferences.TaskChainState
import com.mas.autofarm.data.repository.DepotRepository
import com.mas.autofarm.data.resource.ActivityManager
import com.mas.autofarm.data.resource.ResourceDataManager
import com.mas.autofarm.domain.service.MaaNotificationCenter
import com.mas.autofarm.domain.service.MaaSessionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * SubTask 级别回调处理器
 * 处理 SubTaskError(20000)、SubTaskStart(20001)、SubTaskCompleted(20002)、SubTaskExtraInfo(20003)
 * MAS 精简版：通用日志 + 工具箱结果转发
 */
class SubTaskHandler(
    applicationContext: Context,
    private val sessionLogger: MaaSessionLogger,
    private val resourceDataManager: ResourceDataManager,
    private val toolboxResultCollector: ToolboxResultCollector,
    private val notificationCenter: MaaNotificationCenter,
    private val chainState: TaskChainState,
    private val activityManager: ActivityManager,
    private val depotRepository: DepotRepository,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resources = applicationContext.resources
    private val packageName = applicationContext.packageName

    /** 每次新 session 开始时调用，重置跨任务状态 */
    fun resetSessionState() {
    }

    // ==================== SubTaskError (20000) ====================
    fun onSubTaskError(details: JSONObject) {
        val subtask = details.getString("subtask") ?: return
        val why = details.getString("why")
        val message = if (why.isNullOrBlank()) subtask else "$subtask: $why"
        append(message, LogLevel.ERROR)
    }

    // ==================== SubTaskStart (20001) ====================
    fun onSubTaskStart(details: JSONObject) {
        val subtask = details.getString("subtask") ?: return
        when (subtask) {
            "ProcessTask" -> {
                val innerDetails = details.getJSONObject("details")
                val task = innerDetails?.getString("task")
                if (!task.isNullOrBlank()) {
                    append(task, LogLevel.MESSAGE)
                }
            }
            else -> {
                // 其他 subtask 不处理
            }
        }
    }

    // ==================== SubTaskCompleted (20002) ====================
    fun onSubTaskCompleted(details: JSONObject) {
        val subtask = details.getString("subtask") ?: return
        val what = details.getString("what")
        if (!what.isNullOrBlank() && what != subtask) {
            append(what, LogLevel.MESSAGE)
        }
    }

    // ==================== SubTaskExtraInfo (20003) ====================
    fun onSubTaskExtraInfo(details: JSONObject) {
        // 工具箱结果通过 taskchain 路由转发
        val taskchain = details.getString("taskchain")
        val subDetails = details.getJSONObject("details")
        when (taskchain) {
            "Depot" -> {
                toolboxResultCollector.onDepotResult(subDetails)
                return
            }
            "OperBox" -> {
                toolboxResultCollector.onOperBoxResult(subDetails)
                return
            }
        }
        val what = details.getString("what") ?: return
        when (what) {
            "PixelPaintProgress" -> logPixelPaintProgress(
                toolboxResultCollector.onPixelPaintProgress(subDetails)
            )
        }
    }

    private fun logPixelPaintProgress(progress: PixelPaintProgress?) {
        progress ?: return
        if (progress.total > 0 && progress.done >= progress.total) {
            append(resources.getString(R.string.pixel_art_log_done), LogLevel.SUCCESS)
        } else {
            append(
                resources.getString(R.string.pixel_art_progress, progress.done, progress.total),
                LogLevel.TRACE,
            )
        }
    }

    // ==================== 字符串资源辅助方法 ====================
    private fun str(key: String): String = MaaStringRes.getString(resources, packageName, key)
    private fun str(key: String, vararg args: Any): String =
        MaaStringRes.getString(resources, packageName, key, *args)
    private fun append(content: String, level: LogLevel) {
        sessionLogger.append(content, level)
    }
}
