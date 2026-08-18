package com.yuanqian.autofarm.constant

/**
 * 默认允许的一些宽高配置
 */
object DefaultDisplayConfig {
    const val VD_NAME = "MAA_VD"
    const val DISPLAY_NONE = -1

    // 720p (默认，适用于大多数客户端)
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 160

    const val ASPECT_RATIO_WIDTH = 16
    const val ASPECT_RATIO_HEIGHT = 9

    /** 16:9 宽高比 */
    val ASPECT_RATIO: Float get() = WIDTH.toFloat() / HEIGHT

    const val FRAME_INTERVAL_MS = 16L

    data class Resolution(val width: Int, val height: Int, val dpi: Int)

    val RES_720P = Resolution(1280, 720, 160)
    val RES_1080P = Resolution(1920, 1080, 240)
    /** 20:9 高分档（匹配主流竖屏游戏原生比例，识别细节更足） */
    val RES_1600x720 = Resolution(1600, 720, 240)


    /** 用户可选的后台虚拟屏分辨率偏好 */
    enum class ResolutionPreference { P720, P1600x720, P1080 }

    /**
     * 根据用户偏好解析最终分辨率（通用壳：不区分客户端）。
     */
    fun resolveResolution(
        clientType: String,
        preference: ResolutionPreference
    ): Resolution = when (preference) {
        ResolutionPreference.P720 -> RES_720P
        ResolutionPreference.P1600x720 -> RES_1600x720
        ResolutionPreference.P1080 -> RES_1080P
    }
}
