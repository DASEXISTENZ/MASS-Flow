package com.mas.autofarm.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.fastjson2.JSON
import com.mas.autofarm.BuildConfig
import com.mas.autofarm.R
import com.mas.autofarm.constant.MaaFiles.VERSION_FILE
import com.mas.autofarm.data.config.MaaPathConfig
import com.mas.autofarm.data.datasource.ResourceDownloader
import com.mas.autofarm.data.model.update.UpdateChannel
import com.mas.autofarm.data.model.update.UpdateCheckResult
import com.mas.autofarm.data.model.update.UpdateInfo
import com.mas.autofarm.data.model.update.UpdateProcessState
import com.mas.autofarm.data.model.update.UpdateSource
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.domain.service.MaaResourceLoader
import com.mas.autofarm.domain.service.update.UpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File

@OptIn(FlowPreview::class)
class UpdateViewModel(
    private val appContext: Context,
    private val updateService: UpdateService,
    private val appSettingsManager: AppSettingsManager,
    private val maaResourceLoader: MaaResourceLoader,
    private val pathConfig: MaaPathConfig,
) : ViewModel() {

    // ==================== 资源更新 ====================

    val resourceUpdateState = updateService.resourceProcessState

    private val _currentResourceVersion = MutableStateFlow("")
    val currentResourceVersion: StateFlow<String> = _currentResourceVersion.asStateFlow()

    val updateSource: StateFlow<UpdateSource> = appSettingsManager.updateSource

    val mirrorChyanCdk: StateFlow<String> = appSettingsManager.mirrorChyanCdk

    val updateChannel: StateFlow<UpdateChannel> = appSettingsManager.updateChannel

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // 检查状态
    private val _appChecking = MutableStateFlow(false)
    val appChecking: StateFlow<Boolean> = _appChecking.asStateFlow()

    private val _resourceChecking = MutableStateFlow(false)
    val resourceChecking: StateFlow<Boolean> = _resourceChecking.asStateFlow()

    // 检查结果（仅手动触发时写入）
    private val _appCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val appCheckResult: StateFlow<UpdateCheckResult?> = _appCheckResult.asStateFlow()

    private val _resourceCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val resourceCheckResult: StateFlow<UpdateCheckResult?> = _resourceCheckResult.asStateFlow()

    init {
        viewModelScope.launch {
            refreshResourceVersion()
        }
        mirrorChyanCdk
            .drop(1)
            .filter { it.isNotBlank() }
            .debounce(1000L)
            .onEach {
                updateService.checkAppUpdate(channel = updateChannel.value)
            }
            .launchIn(viewModelScope)
    }


    suspend fun refreshResourceVersion() {
        val raw = loadResourceVersion()
        _currentResourceVersion.value = ResourceDownloader.formatVersionForDisplay(raw)
    }

    private suspend fun loadResourceVersion(): String {
        val dir = pathConfig.resourceDir
        return runCatching {
            withContext(Dispatchers.IO) {
                File(dir, VERSION_FILE).takeIf { it.exists() }?.source()?.buffer()
                    ?.readUtf8()
                    ?.let { JSON.parseObject(it).getString("last_updated") }.orEmpty()
            }
        }.onFailure {
            Timber.w(it, "读取资源版本失败")
        }.getOrDefault("")

    }

    fun setUpdateSource(source: UpdateSource) {
        viewModelScope.launch {
            appSettingsManager.setUpdateSource(source)
        }
    }

    fun setMirrorChyanCdk(cdk: String) {
        viewModelScope.launch {
            appSettingsManager.setMirrorChyanCdk(cdk)
        }
    }

    fun checkResourceUpdate() {
        val currentState = resourceUpdateState.value
        if (_resourceChecking.value || currentState is UpdateProcessState.Downloading || currentState is UpdateProcessState.Extracting || currentState is UpdateProcessState.Installing) {
            return
        }
        viewModelScope.launch {
            _resourceChecking.value = true
            val currentVersion = loadResourceVersion()
            Timber.d("当前资源版本: $currentVersion, 下载源: ${updateSource.value}")
            _resourceCheckResult.value = updateService.checkResourceUpdate(currentVersion)
            _resourceChecking.value = false
        }
    }

    fun dismissResourceCheckResult() {
        _resourceCheckResult.value = null
    }

    fun confirmResourceDownload() {
        viewModelScope.launch {
            val file = File(pathConfig.resourceDir)

            val currentVersion = loadResourceVersion()
            val result = updateService.downloadResource(
                source = updateSource.value,
                currentVersion = currentVersion,
                target = file
            )
            if (result.isSuccess) {
                refreshResourceVersion()
                maaResourceLoader.load()
            }
        }
    }




    fun reset() {
        updateService.resetResourceProcess()
    }

    // ==================== App 更新 ====================

    val appUpdateState = updateService.appProcessState

    val currentAppVersion: String = BuildConfig.VERSION_NAME

    fun checkAppUpdate() {
        val currentState = appUpdateState.value
        if (_appChecking.value || currentState is UpdateProcessState.Downloading || currentState is UpdateProcessState.Installing) {
            return
        }
        viewModelScope.launch {
            _appChecking.value = true
            Timber.i("检查 App 更新 (MirrorChyan)")
            val result = updateService.checkAppUpdate(channel = updateChannel.value)
            saveAppChangelog((result as? UpdateCheckResult.Available)?.info)
            _appCheckResult.value = result
            _appChecking.value = false
        }
    }

    fun dismissAppCheckResult() {
        _appCheckResult.value = null
    }

    fun confirmAppDownload(version: String) {
        Timber.i("确认下载 App 更新: version=$version")
        viewModelScope.launch {
            updateService.downloadApp(
                source = updateSource.value,
                version = version,
                channel = updateChannel.value
            )
        }
    }

    fun resetAppUpdate() {
        updateService.resetAppProcess()
    }

    // ==================== 更新公告 ====================

    private val _changelogDialog = MutableStateFlow<String?>(null)
    val changelogDialog: StateFlow<String?> = _changelogDialog.asStateFlow()

    fun checkPendingChangelog() {
        val version = appSettingsManager.pendingChangelogVersion.value
        val content = appSettingsManager.pendingChangelogContent.value
        val isNewVersion = version == BuildConfig.VERSION_NAME
        if (version.isNotEmpty() && content.isNotEmpty() && isNewVersion) {
            _changelogDialog.value = content
        }
    }

    fun dismissChangelog() {
        _changelogDialog.value = null
        viewModelScope.launch {
            appSettingsManager.clearPendingChangelog()
        }
    }

    private fun saveAppChangelog(appInfo: UpdateInfo?) {
        if (appInfo == null || appInfo.releaseNote.isNullOrBlank()) return
        viewModelScope.launch {
            appSettingsManager.savePendingChangelog(appInfo.version, appInfo.releaseNote)
        }
    }
}
