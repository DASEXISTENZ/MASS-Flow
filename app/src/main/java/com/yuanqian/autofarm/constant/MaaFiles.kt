package com.yuanqian.autofarm.constant

object MaaFiles {
    const val MAA = "Maa"
    const val RESOURCE = "resource"
    const val CACHE = "cache"
    const val DEBUG = "debug"
    const val SCREENSHOTS = "screenshots"
    const val ASSET_DIR_NAME = "MaaSync/MaaResource"
    const val VERSION_FILE = "version.json"
    const val APP_VERSION_FILE = ".version"
    const val ASSET_VERSION_FILE = "$ASSET_DIR_NAME/$VERSION_FILE"

    /** Android 特化覆盖目录名 */
    const val OVERRIDES = "overrides"

    /** overrides 内置模板在 assets 中的路径 */
    const val OVERRIDES_ASSET_TASKS = "overrides/resource/tasks/tasks.json"
}