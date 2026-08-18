package com.yuanqian.autofarm.data.preferences

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuanqian.autofarm.constant.Packages
import com.yuanqian.autofarm.data.model.TaskChainNode
import com.yuanqian.autofarm.data.model.TaskParamProvider
import com.yuanqian.autofarm.data.model.TaskProfile
import com.yuanqian.autofarm.data.model.TaskTypeInfo
import com.yuanqian.autofarm.data.model.CustomFlowConfig
import com.yuanqian.autofarm.data.model.WakeUpConfig
import com.yuanqian.autofarm.manager.RemoteServiceManager
import com.yuanqian.autofarm.remote.PermissionGrantRequest
import com.yuanqian.autofarm.utils.JsonUtils
import com.yuanqian.autofarm.utils.i18n.LocaleBootstrap.resolveSelectedLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import java.util.UUID


class TaskChainState(
    private val context: Context,
    private val appSettings: AppSettingsManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = JsonUtils.common

    companion object {
        private val Context.store: DataStore<Preferences> by preferencesDataStore(
            name = "task_chain"
        )

        private val PROFILES_KEY = stringPreferencesKey("profiles")
        private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")

        private const val PROFILE_NAME_PREFIX = "配置-"
        private const val MAX_PROFILE_NAME_LENGTH = 20
    }

    private val _chain = MutableStateFlow(buildDefaultChain())
    val chain: StateFlow<List<TaskChainNode>> = _chain.asStateFlow()

    private val _profiles = MutableStateFlow<List<TaskProfile>>(emptyList())
    val profiles: StateFlow<List<TaskProfile>> = _profiles.asStateFlow()

    private val _profileId = MutableStateFlow("")
    val profileId: StateFlow<String> = _profileId.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _profileDeleted = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val profileDeleted: SharedFlow<String> = _profileDeleted.asSharedFlow()

    private val _lastUsedClientType = MutableStateFlow<String?>(null)

    private sealed interface PersistOp {
        data object Sync : PersistOp
        data class Flush(val done: CompletableDeferred<Unit>) : PersistOp
    }

    private val persistOps = Channel<PersistOp>(Channel.UNLIMITED)

    val clientType: String
        get() = getClientTypeOrNull() ?: com.yuanqian.autofarm.data.model.WakeUpConfig.DEFAULT_CLIENT_TYPE

    private fun doSync() {
        persistOps.trySend(PersistOp.Sync)
    }

    /**
     * 等到本 Flush 之前的 Sync 全部处理完。
     * 期间若有写盘失败，抛出 [IOException]（[importProfiles] 等「确认落盘」路径依赖此契约）。
     */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        persistOps.send(PersistOp.Flush(done))
        done.await()
    }

    private suspend fun doConsume() {
        var pendingError: IOException? = null
        for (op in persistOps) {
            try {
                when (op) {
                    PersistOp.Sync -> {
                        try {
                            sync()
                            pendingError = null
                        } catch (e: IOException) {
                            Timber.e(e, "写入任务链配置失败")
                            pendingError = e
                        }
                    }

                    is PersistOp.Flush -> {
                        val err = pendingError
                        if (err != null) {
                            pendingError = null
                            op.done.completeExceptionally(err)
                        } else {
                            op.done.complete(Unit)
                        }
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "任务链配置持久化队列处理失败")
                if (op is PersistOp.Flush) {
                    op.done.completeExceptionally(e)
                } else if (e is IOException) {
                    pendingError = e
                }
            }
        }
    }

    private suspend fun sync() {
        context.store.edit { prefs ->
            prefs[PROFILES_KEY] = json.encodeToString<List<TaskProfile>>(_profiles.value)
            prefs[ACTIVE_PROFILE_KEY] = _profileId.value
        }
    }

    init {
        syncScope.launch { doConsume() }
        scope.launch {
            try {
                val prefs = context.store.data.first()

                val storedProfiles = prefs[PROFILES_KEY]?.let {
                    runCatching { json.decodeFromString<List<TaskProfile>>(it) }.onFailure { e ->
                        Timber.e(e, "TaskChainState decodeFromString error")
                    }.getOrNull()
                }

                val needsDefaultProfile = storedProfiles.isNullOrEmpty()

                val profiles = storedProfiles?.takeIf { it.isNotEmpty() } ?: listOf(
                    TaskProfile(
                        name = "${PROFILE_NAME_PREFIX}1",
                        chain = buildDefaultChain(),
                    )
                )

                val storedActiveId = prefs[ACTIVE_PROFILE_KEY]

                val activeProfile =
                    profiles.firstOrNull { it.id == storedActiveId } ?: profiles.first()

                val needsSync = needsDefaultProfile || storedActiveId != activeProfile.id

                _profiles.value = profiles
                _profileId.value = activeProfile.id
                _chain.value = activeProfile.chain
                _isLoaded.value = true

                if (needsSync) {
                    doSync()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load profiles")
                _isLoaded.value = true
            }
        }
    }


    suspend fun addNode(typeInfo: TaskTypeInfo, afterIndex: Int = -1): String {
        val nodeId = mutate {
            val node = TaskChainNode(
                id = UUID.randomUUID().toString(),
                name = defaultTaskName(typeInfo),
                enabled = true,
                config = typeInfo.defaultConfig()
            )
            if (afterIndex < 0 || afterIndex >= it.size) {
                it.add(node)
            } else {
                it.add(afterIndex + 1, node)
            }
            Timber.d("Added node: %s (%s)", node.name, typeInfo.name)
            node.id
        }
        return nodeId
    }

    /**
     * 绑定流程到指定配置：替换该配置的链为流程节点镜像，并记录绑定信息与同步时间。
     * 若目标为当前激活配置，同步更新当前链。
     */
    suspend fun bindFlowToProfile(
        profileId: String,
        flowName: String,
        nodes: List<TaskChainNode>,
        flowMtime: Long,
    ) {
        val currentProfiles = _profiles.value
        // 保存当前链到活跃 Profile
        val savedProfiles = currentProfiles.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }
        val updated = savedProfiles.map { p ->
            if (p.id == profileId) {
                p.copy(
                    chain = nodes,
                    boundFlowName = flowName,
                    boundFlowSyncedAt = flowMtime,
                )
            } else p
        }
        _profiles.value = updated
        if (profileId == _profileId.value) {
            _chain.value = nodes
        }
        doSync()
        Timber.d("Bound flow %s to profile %s (%d nodes)", flowName, profileId, nodes.size)
    }

    /** 解除配置的流程绑定（保留当前链，仅清除绑定标记）。 */
    suspend fun unbindFlowFromProfile(profileId: String) {
        _profiles.value = _profiles.value.map { p ->
            if (p.id == profileId) p.copy(boundFlowName = null, boundFlowSyncedAt = 0L) else p
        }
        doSync()
        Timber.d("Unbound flow from profile %s", profileId)
    }

    /** 更新指定配置的链快照（同步流程后调用，保持数据一致）。 */
    suspend fun updateProfileChain(profileId: String, nodes: List<TaskChainNode>, syncedAt: Long) {
        _profiles.value = _profiles.value.map { p ->
            if (p.id == profileId) p.copy(chain = nodes, boundFlowSyncedAt = syncedAt) else p
        }
        if (profileId == _profileId.value) {
            _chain.value = nodes
        }
        doSync()
        Timber.d("Synced profile %s chain to %d nodes", profileId, nodes.size)
    }

    /** 批量追加节点（流程引用导入用）。返回新节点 id 列表。 */
    suspend fun appendNodes(nodes: List<TaskChainNode>): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val ids = mutableListOf<String>()
        mutate { current ->
            nodes.forEach { n ->
                current.add(n)
                ids.add(n.id)
            }
        }
        Timber.d("Appended %d nodes", nodes.size)
        return ids
    }

    suspend fun removeNode(nodeId: String) {
        mutate {
            it.removeAll { iter ->
                iter.id == nodeId
            }
            Timber.d("Removed node: %s", nodeId)
        }
    }

    suspend fun duplicateNode(nodeId: String): String {
        return mutate { current ->
            val idx = current.indexOfFirst { it.id == nodeId }
            if (idx >= 0) {
                val src = current[idx]
                // 去掉末尾 " N"（空格+数字）得到基础名
                val baseName = src.name.replace(Regex(" \\d+$"), "")
                // 收集链中所有以 "baseName N" 形式命名已占用的编号
                val usedNumbers = current.mapNotNull { node ->
                    Regex("^${Regex.escape(baseName)} (\\d+)$").matchEntire(node.name)?.groupValues?.get(
                        1
                    )?.toIntOrNull()
                }.toSet()
                // 取最小未占用的正整数（从 2 开始，1 留给源名称本身）
                val nextNum = generateSequence(2) { it + 1 }.first { it !in usedNumbers }
                val copy = src.copy(
                    id = UUID.randomUUID().toString(), name = "$baseName $nextNum"
                )
                current.add(idx + 1, copy)
                Timber.d("Duplicated node %s → %s (\"%s\")", nodeId, copy.id, copy.name)
                copy.id
            } else {
                Timber.w("duplicateNode: node %s not found", nodeId)
                ""
            }
        }
    }

    suspend fun renameNode(nodeId: String, newName: String) {
        mutate { current ->
            val idx = current.indexOfFirst { it.id == nodeId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(name = newName)
                Timber.d("Renamed node %s to: %s", nodeId, newName)
            } else {
                Timber.w("renameNode: node %s not found", nodeId)
            }
        }
    }

    suspend fun setNodeEnabled(nodeId: String, enabled: Boolean) {
        mutate { current ->
            val idx = current.indexOfFirst { it.id == nodeId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(enabled = enabled)
                Timber.d("Set node %s enabled: %s", nodeId, enabled)
            } else {
                Timber.w("setNodeEnabled: node %s not found", nodeId)
            }
        }
    }

    suspend fun updateNodeConfig(nodeId: String, config: TaskParamProvider) {
        mutate { current ->
            val idx = current.indexOfFirst { it.id == nodeId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(config = config)
            } else {
                Timber.w("updateNodeConfig: node %s not found", nodeId)
            }
        }
    }

    suspend fun reorderNodes(fromIndex: Int, toIndex: Int) {
        mutate { current ->
            require(fromIndex in current.indices) { "fromIndex out of bounds: $fromIndex" }
            require(toIndex in current.indices) { "toIndex out of bounds: $toIndex" }
            val node = current.removeAt(fromIndex)
            current.add(toIndex, node)
            Timber.d("Moved node from %d to %d", fromIndex, toIndex)
        }
    }

    fun getClientTypeOrNull(): String? {
        return findFirstEnabledConfig<WakeUpConfig>()?.clientType ?: _lastUsedClientType.value
    }

    fun saveLastUsedClientType(clientType: String) {
        _lastUsedClientType.value = clientType
    }

    inline fun <reified T : TaskParamProvider> findFirstEnabledConfig(): T? {
        return chain.value.filter { it.enabled }.firstNotNullOfOrNull { it.config as? T }
    }

    inline fun <reified T : TaskParamProvider> firstEnabledConfigFlow(): Flow<T?> {
        return chain.map { nodes ->
            nodes.filter { it.enabled }.firstNotNullOfOrNull { it.config as? T }
        }
    }

    // ========== Profile 管理 ==========

    suspend fun switchProfile(profileId: String) {
        val currentProfiles = _profiles.value
        val target = currentProfiles.find { it.id == profileId } ?: run {
            Timber.w("switchProfile: profile %s not found", profileId)
            return
        }
        // 保存当前链到旧 Profile
        val updatedProfiles = currentProfiles.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }
        // 加载新 Profile 的链
        _chain.value = target.chain
        _profileId.value = profileId
        _profiles.value = updatedProfiles
        // 持久化
        doSync()
        Timber.d("Switched to profile: %s (%s)", target.name, profileId)
    }

    suspend fun createProfile(name: String? = null): String {
        val currentProfiles = _profiles.value
        // 先保存当前活跃 Profile 的链
        val savedProfiles = currentProfiles.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }
        val newProfile = TaskProfile(
            name = uniqueProfileName(savedProfiles, name), chain = buildDefaultChain()
        )
        val updatedProfiles = savedProfiles + newProfile
        // 切换到新 Profile
        _chain.value = newProfile.chain
        _profileId.value = newProfile.id
        _profiles.value = updatedProfiles
        doSync()
        Timber.d("Created profile: %s (%s)", newProfile.name, newProfile.id)
        return newProfile.id
    }

    /** 生成唯一配置名：指定名优先，重名自动加后缀；未指定走「配置-N」自动编号。 */
    private fun uniqueProfileName(profiles: List<TaskProfile>, base: String?): String {
        if (base.isNullOrBlank()) return nextProfileName(profiles)
        val safe = base.trim().take(MAX_PROFILE_NAME_LENGTH)
        if (profiles.none { it.name == safe }) return safe
        var n = 2
        while (profiles.any { it.name == "$safe-$n" }) n++
        return "$safe-$n"
    }

    suspend fun removeProfile(profileId: String) {
        val currentProfiles = _profiles.value
        if (currentProfiles.size <= 1) {
            Timber.w("deleteProfile: cannot delete last profile")
            return
        }
        // 先保存当前链
        val savedProfiles = currentProfiles.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }
        val remaining = savedProfiles.filter { it.id != profileId }
        if (remaining.size == savedProfiles.size) {
            Timber.w("deleteProfile: profile %s not found", profileId)
            return
        }
        // 若删除的是活跃 Profile,切换到列表第一个
        val newActiveId = if (_profileId.value == profileId) {
            val first = remaining.first()
            _chain.value = first.chain
            first.id
        } else {
            _profileId.value
        }
        _profileId.value = newActiveId
        _profiles.value = remaining
        doSync()
        _profileDeleted.tryEmit(profileId)
        Timber.d("Deleted profile: %s", profileId)
    }

    suspend fun renameProfile(profileId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_PROFILE_NAME_LENGTH) {
            Timber.w("renameProfile: invalid name length: %d", trimmed.length)
            return
        }
        val currentProfiles = _profiles.value
        val updatedProfiles = currentProfiles.map { p ->
            if (p.id == profileId) p.copy(name = trimmed) else p
        }
        _profiles.value = updatedProfiles
        doSync()
        Timber.d("Renamed profile %s to: %s", profileId, trimmed)
    }

    suspend fun duplicateProfile(profileId: String): String? {
        val currentProfiles = _profiles.value
        // 先保存当前活跃 Profile 的链
        val savedProfiles = currentProfiles.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }
        val source = savedProfiles.find { it.id == profileId } ?: run {
            Timber.w("duplicateProfile: profile %s not found", profileId)
            return null
        }
        // 复制链时为每个节点生成新 ID
        val duplicatedChain = source.chain.map { it.copy(id = UUID.randomUUID().toString()) }
        val newProfile = TaskProfile(
            name = nextProfileName(savedProfiles), chain = duplicatedChain
        )
        val updatedProfiles = savedProfiles + newProfile
        _profiles.value = updatedProfiles
        doSync()
        Timber.d("Duplicated profile %s as: %s (%s)", profileId, newProfile.name, newProfile.id)
        return newProfile.id
    }

    suspend fun reorderProfiles(fromIndex: Int, toIndex: Int) {
        val current = _profiles.value
        if (fromIndex !in current.indices || toIndex !in current.indices) {
            Timber.w(
                "reorderProfiles: invalid index from=%d to=%d size=%d",
                fromIndex,
                toIndex,
                current.size
            )
            return
        }
        if (fromIndex == toIndex) return

        // 顺便把当前未保存的链快照写回 active profile, 避免重排时丢失正在编辑的内容
        val savedProfiles = current.map { p ->
            if (p.id == _profileId.value) p.copy(chain = _chain.value) else p
        }.toMutableList()
        val moved = savedProfiles.removeAt(fromIndex)
        savedProfiles.add(toIndex, moved)

        _profiles.value = savedProfiles
        doSync()
        Timber.d("Reordered profile from %d to %d", fromIndex, toIndex)
    }

    // ========== 内部工具方法 ==========

    private suspend inline fun <T> mutate(
        crossinline block: (MutableList<TaskChainNode>) -> T
    ): T {
        val current = _chain.value.toMutableList()
        val ret = block(current)
        reindex(current)
        val snapshot = current.toList()
        _chain.value = snapshot
        _profiles.value = _profiles.value.map { p ->
            if (p.id == _profileId.value) p.copy(chain = snapshot) else p
        }
        doSync()
        return ret
    }

    private fun reindex(nodes: MutableList<TaskChainNode>) {
        for (i in nodes.indices) {
            nodes[i] = nodes[i].copy(order = i)
        }
    }

    private fun buildDefaultChain(): List<TaskChainNode> {
        return TaskTypeInfo.entries.filter { it.inDefaultChain }.mapIndexed { index, info ->
            TaskChainNode(
                name = defaultTaskName(info),
                enabled = false,
                order = index,
                config = info.defaultConfig()
            )
        }
    }

    private fun defaultTaskName(typeInfo: TaskTypeInfo): String {
        val tag = resolveSelectedLanguage(appSettings.language.value).tag
        val localizedContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
        return typeInfo.defaultName(localizedContext)
    }

    suspend fun importProfiles(profiles: List<TaskProfile>, activeId: String) {
        val resolvedActiveId =
            profiles.find { it.id == activeId }?.id ?: profiles.firstOrNull()?.id ?: return
        val activeChain = profiles.find { it.id == resolvedActiveId }?.chain ?: buildDefaultChain()
        _profiles.value = profiles
        _profileId.value = resolvedActiveId
        _chain.value = activeChain
        doSync()
        // 导入是用户可见的终态操作（随后会提示「导入成功」），必须确认落盘再返回
        flush()
        Timber.d("Imported %d profiles, active: %s", profiles.size, resolvedActiveId)
    }

    private fun nextProfileName(profiles: List<TaskProfile>): String {
        val maxNum = profiles.mapNotNull { p ->
            if (p.name.startsWith(PROFILE_NAME_PREFIX)) {
                p.name.removePrefix(PROFILE_NAME_PREFIX).toIntOrNull()
            } else {
                null
            }
        }.maxOrNull() ?: 0
        return "$PROFILE_NAME_PREFIX${maxNum + 1}"
    }
}
