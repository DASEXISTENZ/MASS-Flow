package com.mas.autofarm.domain.service

import android.content.Context
import com.mas.autofarm.R
import com.mas.autofarm.constant.MaaFiles.ASSET_DIR_NAME
import com.mas.autofarm.constant.MaaFiles.OVERRIDES_ASSET_TASKS
import com.mas.autofarm.data.config.MaaPathConfig
import com.mas.autofarm.data.datasource.AssetExtractor
import com.mas.autofarm.domain.state.ResourceInitState
import com.mas.autofarm.utils.i18n.LocalizedException
import com.mas.autofarm.utils.i18n.uiTextDynamicOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class ResourceInitService(
    private val context: Context,
    private val assetExtractor: AssetExtractor,
    private val pathConfig: MaaPathConfig
) {
    private val _state = MutableStateFlow<ResourceInitState>(ResourceInitState.NotChecked)
    val state: StateFlow<ResourceInitState> = _state.asStateFlow()

    suspend fun checkAndInit() {
        val cur = _state.value
        if (cur !is ResourceInitState.NotChecked && cur !is ResourceInitState.Failed) {
            return
        }
        if (!_state.compareAndSet(cur, ResourceInitState.Checking)) {
            return
        }

        if (pathConfig.isResourceReady) {
            _state.value = ResourceInitState.Ready
            return
        }

        // 自修复：磁盘资源完好（version.json 存在）但版本号不匹配
        // （如覆盖安装后 versionCode 变化）→ 直接信任磁盘资源并标记当前版本，避免无谓重装
        if (File(pathConfig.resourceDir, com.mas.autofarm.constant.MaaFiles.VERSION_FILE).exists()) {
            pathConfig.markAppVersion()
            Timber.i("资源版本已自修复（磁盘资源完好，仅版本号不匹配）")
            _state.value = ResourceInitState.Ready
            return
        }

        doExtractFromAssets()
    }

    suspend fun reInitialize() {
        doExtractFromAssets()
    }

    suspend fun doExtractFromAssets() {
        // 防呆：本项目 APK 不带内置 Maa 资源（assets/MaaSync 仅 manifest）。
        // 若 assets 无实际资源且磁盘已有资源 → 绝不删除磁盘资源，仅同步版本号。
        val assetsHaveResource = runCatching {
            context.assets.list(ASSET_DIR_NAME)?.any { it != "asset_manifest.json" } ?: false
        }.getOrDefault(false)
        if (!assetsHaveResource && File(pathConfig.resourceDir, com.mas.autofarm.constant.MaaFiles.VERSION_FILE).exists()) {
            pathConfig.markAppVersion()
            Timber.i("assets 无内置资源，保留磁盘资源（仅同步版本号）")
            _state.value = ResourceInitState.Ready
            return
        }

        _state.value = ResourceInitState.Extracting(0, 0, context.getString(R.string.resource_init_preparing))

        try {
            withContext(Dispatchers.IO) {
                pathConfig.ensureDirectories()
                val resourceDir = File(pathConfig.resourceDir)
                if (resourceDir.exists() && !resourceDir.deleteRecursively()) {
                    Timber.w("清理旧资源目录失败: ${resourceDir.absolutePath}")
                }
                resourceDir.mkdirs()
            }

            val result = assetExtractor.extract(
                assetDir = ASSET_DIR_NAME,
                destDir = File(pathConfig.resourceDir),
                onProgress = { progress ->
                    _state.value = ResourceInitState.Extracting(
                        extractedCount = progress.extractedCount,
                        totalCount = progress.totalCount,
                        currentFile = progress.currentFile
                    )
                }
            )

            result.fold(
                onSuccess = {
                    pathConfig.markAppVersion()
                    doForceSyncOverridesTemplate()
                    Timber.i("资源初始化完成")
                    _state.value = ResourceInitState.Ready
                },
                onFailure = { e ->
                    _state.value = ResourceInitState.Failed(
                        (e as? LocalizedException)?.uiText
                            ?: uiTextDynamicOr(e.message, R.string.resource_init_error_copy_failed)
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "资源初始化失败")
            _state.value = ResourceInitState.Failed(
                (e as? LocalizedException)?.uiText
                    ?: uiTextDynamicOr(e.message, R.string.resource_init_error_unknown)
            )
        }
    }

    private fun doForceSyncOverridesTemplate() {
        val dest = pathConfig.overrideTasksFile
        runCatching {
            dest.parentFile?.mkdirs()
            context.assets.open(OVERRIDES_ASSET_TASKS).use { src ->
                dest.outputStream().use { src.copyTo(it) }
            }
            Timber.d("overrides 模板已同步: ${dest.absolutePath}")
        }.onFailure {
            Timber.w(it, "overrides 模板同步失败，跳过")
        }
    }
}
