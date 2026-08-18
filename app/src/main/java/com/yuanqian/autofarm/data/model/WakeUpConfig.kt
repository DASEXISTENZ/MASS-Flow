package com.yuanqian.autofarm.data.model

import com.yuanqian.autofarm.maa.task.MaaTaskParams
import com.yuanqian.autofarm.maa.task.MaaTaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 开始唤醒配置
 *
 * MaaCore JSON 参数:
 * - client_type: 客户端类型字符串
 * - start_game_enabled: 是否启动游戏
 */
@Serializable
data class WakeUpConfig(
    /**
     * 客户端类型
     * 对应 WPF: ClientType
     * MaaCore JSON: client_type
     *
     * 选项：
     * - "Official": 官服
     * - "Bilibili": B服
     * - "YoStarEN": 国际服(YoStarEN)
     * - "YoStarJP": 日服(YoStarJP)
     * - "YoStarKR": 韩服(YoStarKR)
     * - "txwy": 繁中服(txwy)
     */
    val clientType: String = DEFAULT_CLIENT_TYPE,

    /**
     * 是否启用启动游戏
     * 对应 WPF: StartGame
     * MaaCore JSON: start_game_enabled
     */
    val startGameEnabled: Boolean = true,

    /**
     * 账号切换目标
     * 对应 WPF: AccountName
     * MaaCore JSON: account_name
     *
     */
    val accountName: String = ""
) : TaskParamProvider {
    companion object {
        /**
         * 客户端类型选项值列表
         */
        val CLIENT_TYPES: List<String> get() = com.yuanqian.autofarm.constant.Packages.clientTypes
        /** 默认客户端：取当前适配配置（game.json）的第一个客户端类型 */
        val DEFAULT_CLIENT_TYPE: String get() = com.yuanqian.autofarm.constant.Packages.clientTypes.firstOrNull() ?: ""

        /**
         * 客户端类型到服务器类型的映射
         * 用于资源更新等逻辑
         */
        fun getServerType(clientType: String): String = clientType
    }

    /**
     * 获取服务器类型
     */
    fun getServerType(): String = getServerType(clientType)
    override fun toTaskParams(ctx: TaskParamContext): List<MaaTaskParams> {
        val paramsJson = buildJsonObject {
            put("client_type", clientType)
            put("start_game_enabled", startGameEnabled)
        }
        return listOf(MaaTaskParams(MaaTaskType.START_UP, paramsJson.toString()))
    }
}
