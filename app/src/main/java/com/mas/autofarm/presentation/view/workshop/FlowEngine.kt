package com.mas.autofarm.presentation.view.workshop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mas.autofarm.RemoteService
import kotlinx.coroutines.delay
import java.io.File

/**
 * FlowEngine：自研流程执行器。
 * 读取工坊导出的 FlowProject（节点/逻辑链/突发），驱动 MaaCore bridge 截图与输入执行。
 */
class FlowEngine(
    private val context: android.content.Context,
    private val remote: RemoteService,
    private val onLog: (String) -> Unit = {},
    private val onNode: (String) -> Unit = {},
) {

    enum class EngineState { IDLE, RUNNING, STOPPED, ERROR }

    var state: EngineState = EngineState.IDLE
        private set

    private var stopRequested = false
    private var screenshotDir: File? = null
    private var lastMatchPoint: Pair<Int, Int>? = null
    private var lastBurstContinue: String? = null
    private var burstPendingJump = false
    /** 循环节点已执行次数（每次 run 重置） */
    private val loopCounts = mutableMapOf<String, Int>()
    /** 循环终点开始时间（超时保护用） */
    private val loopStartTimes = mutableMapOf<String, Long>()
    /** 信号表：节点id → (输入来源节点id → 信号值true/false)。判定广播→合取/析取/循环终点读取 */
    private val signalTable = mutableMapOf<String, MutableMap<String, Boolean>>()
    @Volatile
    private var pauseRequested = false

    fun stop() {
        stopRequested = true
    }

    /** 暂停：在节点边界挂起，可配合虚拟屏预览点选校准坐标 */
    fun pause() {
        pauseRequested = true
        onLog("⏸ 已暂停（可在预览上点选校准坐标）")
    }

    /** 继续执行 */
    fun resume() {
        pauseRequested = false
        onLog("▶ 继续执行")
    }

    val isPaused: Boolean get() = pauseRequested

    /** 执行整个流程（协程中调用） */
    suspend fun run(project: FlowProject, templates: List<FlowTemplate>) {
        state = EngineState.RUNNING
        stopRequested = false
        loopCounts.clear()
        signalTable.clear()
        lastMatchPoint = null
        onLog("开始执行流程：${project.name}（节点${project.nodes.size} 逻辑链${project.links.size} 突发${project.bursts.size}）")
        try {
            val startId = project.nodes.firstOrNull()?.id ?: return
            var currentId: String? = startId
            var guard = 0
            while (currentId != null && !stopRequested) {
                // 暂停挂起：等待恢复或停止
                while (pauseRequested && !stopRequested) {
                    delay(200)
                }
                if (stopRequested) break
                if (guard++ > 500) {
                    onLog("执行步数超限，中止（可能死循环）")
                    break
                }
                // 突发检查（始终监听）
                val burst = project.bursts.firstOrNull { it.alwaysOn }
                if (burst != null) {
                    checkBurst(burst, project, templates)
                }
                // 突发命中：跳转继续节点
                if (burstPendingJump) {
                    burstPendingJump = false
                    onLog("→ 跳转到继续节点：${project.nodes.find { it.id == lastBurstContinue }?.name ?: lastBurstContinue}")
                    currentId = lastBurstContinue
                    continue
                }
                val node = project.nodes.find { it.id == currentId }
                if (node == null) {
                    onLog("节点不存在，停止")
                    break
                }
                onNode(node.name)
                val next = executeNode(node, project, templates)
                // 无线出口时：按节点列表顺序继续（顺序执行语义），最后节点自然结束
                currentId = next ?: sequentialNext(node, project)
            }
            if (!stopRequested) onLog("流程执行完成")
        } catch (e: Exception) {
            state = EngineState.ERROR
            onLog("引擎错误：${e.message}")
        } finally {
            if (stopRequested) state = EngineState.STOPPED
            else if (state != EngineState.ERROR) state = EngineState.IDLE
        }
    }

    /** 节点无连线出口时，按 nodes 顺序取下一个节点（导入的顺序流程无需连线也能跑）。 */
    private fun sequentialNext(node: FlowNode, project: FlowProject): String? {
        val idx = project.nodes.indexOfFirst { it.id == node.id }
        if (idx < 0) return null
        return project.nodes.getOrNull(idx + 1)?.id
    }

    /** 主线跳转：判定节点的是/非线目标若为汇聚/循环终点 → 只作信号不跳转；否则跳转；都没有走顺序 */
    private fun nextOfBranch(node: FlowNode, project: FlowProject): String? {
        listOf(LinkType.YES, LinkType.NO).forEach { type ->
            project.links.filter { it.type == type && it.fromIds == listOf(node.id) }
                .map { it.toId }
                .firstOrNull { to ->
                    val n = project.nodes.find { it.id == to }
                    n == null || n.kind !in setOf(
                        FlowNodeKind.CONJUNCTION, FlowNodeKind.DISJUNCTION, FlowNodeKind.LOOP_END
                    )
                }?.let { return it }
        }
        return sequentialNext(node, project)
    }

    /** 判定广播：把"信号"送到该判定的是/非线目标（目标为汇聚/循环终点时登记到信号表） */
    private fun sendSignal(fromId: String, project: FlowProject) {
        project.links.filter { it.fromIds == listOf(fromId) }.forEach { link ->
            val target = project.nodes.find { it.id == link.toId } ?: return@forEach
            if (target.kind == FlowNodeKind.CONJUNCTION || target.kind == FlowNodeKind.DISJUNCTION ||
                target.kind == FlowNodeKind.LOOP_END
            ) {
                // 是线到达=该条件(判定为真)成立；非线到达=该条件(判定为假)成立——都记为"信号已到"
                signalTable.getOrPut(target.id) { mutableMapOf() }[fromId] = true
                onLog("📡 信号 ${nodeName(fromId, project)} → ${nodeName(target.id, project)}（${link.type.name}）")
            }
        }
    }

    private fun nodeName(id: String, project: FlowProject): String =
        project.nodes.find { it.id == id }?.name ?: id

    private suspend fun executeNode(node: FlowNode, project: FlowProject, templates: List<FlowTemplate>): String? {
        if (stopRequested) return null
        return when (node.kind) {
            FlowNodeKind.INFO -> {
                executeInfo(node)
                sendSignal(node.id, project)
                nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
            }
            FlowNodeKind.TIME -> { val r = executeTime(node, project, templates); sendSignal(node.id, project); r }
            FlowNodeKind.WAIT -> { val r = executeTime(node, project, templates); sendSignal(node.id, project); r }
            FlowNodeKind.IMAGE -> executeImage(node, project, templates)
            FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
            FlowNodeKind.BACK, FlowNodeKind.INPUT -> {
                if (node.customNodeId.isNotBlank()) {
                    return executeCustom(node, project)
                }
                executeAction(node.action)
                sendSignal(node.id, project)
                nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
            }
            FlowNodeKind.LOOP_START -> {
                // 循环起点：标记循环体入口，本身不做事，按连线/顺序继续
                onLog("↩ 循环起点：${node.name}")
                nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
            }
            FlowNodeKind.LOOP -> {
                if (node.loopMode == "until") {
                    // 直到识别：命中→出循环（非线），未命中→进循环体（是线）
                    if (node.untilTemplateId.isBlank()) {
                        onLog("「${node.name}」直到循环未选模板，退出循环")
                        return nextOf(node.id, LinkType.NO, project) ?: firstOut(node.id, project)
                    }
                    val deadline = System.currentTimeMillis() + (node.untilTimeoutMs.takeIf { it > 0 } ?: node.durationMs)
                    while (System.currentTimeMillis() < deadline && !stopRequested) {
                        if (matchTemplateOnce(node.untilTemplateId, templates, 0.7)) {
                            onLog("「${node.name}」直到识别到 ${node.untilTemplateId}，退出循环")
                            return nextOf(node.id, LinkType.NO, project) ?: firstOut(node.id, project)
                        }
                        delay(300)
                    }
                    onLog("「${node.name}」未识别到，继续循环体")
                    return nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
                }
                // 次数循环：已执行次数 < 次数 → 进循环体（是线）；否则退出（非线）
                val maxTimes = node.durationMs.toInt().coerceAtLeast(1)
                val n = (loopCounts[node.id] ?: 0) + 1
                loopCounts[node.id] = n
                if (n >= maxTimes) {
                    onLog("「${node.name}」循环完成（$n/$maxTimes），退出循环")
                    return nextOf(node.id, LinkType.NO, project) ?: firstOut(node.id, project)
                }
                onLog("「${node.name}」第 ${n}/${maxTimes} 次，进入循环体")
                return nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
            }
FlowNodeKind.APP_STATE -> {
                val pkg = node.appStatePkg
                if (pkg.isBlank()) {
                    onLog("「${node.name}」未设置应用包名")
                    sendSignal(node.id, project)
                    return nextOfBranch(node, project)
                }
                val ok = if (node.appStateMode == "alive") {
                    runCatching { remote.isAppAlive(pkg) > 0 }.getOrDefault(false)
                } else {
                    runCatching { remote.isAppOnVirtualDisplay(pkg) }.getOrDefault(false)
                }
                onLog("「${node.name}」应用 $pkg ${if (ok) "在虚拟屏" else "不在虚拟屏"}（${node.appStateMode}）")
                sendSignal(node.id, project)
                return nextOfBranch(node, project)
            }
            FlowNodeKind.CONJUNCTION -> {
                // 合取：所有输入线（是/非线）都收到信号 → 输出真（走是线/顺序）；任一缺失 → 输出假
                val inputs = project.links.filter { it.toId == node.id }
                val arrived = inputs.count { signalTable[node.id]?.containsKey(it.fromIds.firstOrNull()) == true }
                onLog("「${node.name}」合取 ${arrived}/${inputs.size} 路信号到")
                return if (inputs.isNotEmpty() && arrived == inputs.size) {
                    onLog("合取成立 → 是")
                    nextOfBranch(node, project)
                } else {
                    onLog("合取不成立 → 非")
                    nextOfBranch(node, project)
                }
            }
            FlowNodeKind.DISJUNCTION -> {
                // 析取：任一输入线收到信号 → 输出真；全部无信号 → 输出假
                val inputs = project.links.filter { it.toId == node.id }
                val arrived = inputs.any { signalTable[node.id]?.containsKey(it.fromIds.firstOrNull()) == true }
                onLog("「${node.name}」析取 ${if (arrived) "有信号→是" else "无信号→非"}")
                return nextOfBranch(node, project)
            }
            FlowNodeKind.LOOP_END -> {
                executeLoopEnd(node, project)
            }
        }
    }

    /** 循环终点：判定成功（继续信号到）→回循环起点；否则结束（走主线）；超时强制放行 */
    private suspend fun executeLoopEnd(node: FlowNode, project: FlowProject): String? {
        val startId = node.loopStartId
        if (startId.isBlank()) {
            onLog("「${node.name}」循环终点未绑定起点（🚫）")
            return nextOfBranch(node, project)
        }
        // 次数模式（兼容旧 LOOP times）
        if (node.loopMode == "times") {
            val maxTimes = node.durationMs.toInt().coerceAtLeast(1)
            val n = (loopCounts[node.id] ?: 0) + 1
            loopCounts[node.id] = n
            if (n >= maxTimes) {
                onLog("「${node.name}」循环完成（$n/$maxTimes），退出循环")
                return nextOfBranch(node, project)
            }
            onLog("「${node.name}」第 $n/$maxTimes 次，进入循环体")
            return startId
        }
        // 判定信号模式：唯一进线收到信号 → 继续循环；否则结束
        // 超时保护：超时后直接放行（强制成功 → 继续循环？或退出？）—— 按用户定义：超时=强制判定成功=继续循环，不再判定
        if (node.loopTimeoutMs > 0) {
            val loopStart = loopStartTimes.getOrPut(node.id) { System.currentTimeMillis() }
            if (System.currentTimeMillis() - loopStart > node.loopTimeoutMs) {
                onLog("「${node.name}」循环超时（>${node.loopTimeoutMs}ms），强制放行继续循环")
                return startId
            }
        }
        val inputs = project.links.filter { it.toId == node.id }
        val continueSignal = inputs.any { signalTable[node.id]?.containsKey(it.fromIds.firstOrNull()) == true }
        if (continueSignal) {
            onLog("「${node.name}」循环判定满足 → 继续循环")
            return startId
        }
        onLog("「${node.name}」循环判定未满足 → 结束循环")
        return nextOfBranch(node, project)
    }

    /** 自定义节点：按模板执行命令，退出码0=成功→是线，否则→非线 */
    private suspend fun executeCustom(node: FlowNode, project: FlowProject): String? {
        val tpl = com.mas.autofarm.presentation.view.workshop.CustomNodeStore.find(context, node.customNodeId)
            ?: run {
                onLog("自定义节点模板不存在：" + node.customNodeId)
                return nextOf(node.id, LinkType.NO, project) ?: firstOut(node.id, project)
            }
        var cmd = tpl.command
        tpl.params.forEach { p -> cmd = cmd.replace("{" + p.key + "}", node.customParams[p.key] ?: p.default) }
        onLog("自定义(" + tpl.categoryLabel() + ")：" + cmd)
        val ok = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val code = if (proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) proc.exitValue() else {
                proc.destroy()
                -1
            }
            if (out.isNotBlank()) onLog(out.trim().take(300))
            code == 0
        }.getOrDefault(false)
        onLog(if (ok) "自定义节点成功（是）" else "自定义节点失败（非）")
        sendSignal(node.id, project)
        return nextOfBranch(node, project)
    }

    /** 流程信息节点：启动目标应用到后台虚拟屏（不打扰用户） */
    private suspend fun executeInfo(node: FlowNode) {
        if (!node.launchApp || node.appPackage.isBlank()) {
            onLog("流程信息：不启动应用（包名:${node.appPackage.ifBlank { "未设置" }}）")
            return
        }
        val pkg = node.appPackage
        onLog("启动应用：$pkg（后台虚拟屏）")
        try {
            // 0. 按流程信息节点设置的分辨率创建虚拟屏
            if (node.resolution.isNotBlank()) {
                val res = com.mas.autofarm.constant.DefaultDisplayConfig.resolveResolution(
                    node.resolution, com.mas.autofarm.constant.DefaultDisplayConfig.ResolutionPreference.valueOf(node.resolution)
                )
                runCatching { remote.setVirtualDisplayResolution(res.width, res.height, res.dpi) }
                    .onSuccess { onLog("虚拟屏分辨率 ${res.width}x${res.height} dpi=${res.dpi}") }
            }
            // 1. 确保虚拟屏运行，拿到 displayId
            var displayId = runCatching { remote.startVirtualDisplay() }.getOrDefault(-1)
            if (displayId <= 0) {
                onLog("虚拟屏启动失败")
                return
            }
            onLog("虚拟屏就绪 display=$displayId")
            // 2. 已存活则直接移入虚拟屏
            val alive = runCatching { remote.isAppAlive(pkg) > 0 }.getOrDefault(false)
            if (alive) {
                runCatching { remote.moveAppToVirtualDisplay(pkg) }
                onLog("应用已在运行，移入虚拟屏")
                return
            }
            // 3. 直接启动到虚拟屏（全程后台，不经过主屏）
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                onLog("找不到应用：$pkg")
                return
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { remote.startActivityOnDisplay(intent, displayId) }.getOrDefault(false)
            if (started) {
                onLog("已直接启动到虚拟屏 display=$displayId（全程后台）")
                return
            }
            // 4. 兜底：主屏启动后快速拉回
            onLog("直启失败，主屏启动后快速拉回…")
            runCatching { remote.startActivity(intent) }
            delay(600)
            repeat(8) {
                if (stopRequested) return
                if (runCatching { remote.moveAppToVirtualDisplay(pkg) }.getOrDefault(false)) {
                    onLog("已移入虚拟屏：$pkg")
                    return
                }
                delay(600)
            }
            onLog("移入虚拟屏失败（重试后），应用可能在前台运行")
        } catch (e: Exception) {
            onLog("启动应用异常：${e.message}")
        }
    }

    private suspend fun executeTime(node: FlowNode, project: FlowProject, templates: List<FlowTemplate>): String? {
        if (node.untilJudgeId.isNotBlank()) {
            // 等待直到某判定为真（多次识别，直到命中或超时）
            val judge = project.nodes.find { it.id == node.untilJudgeId }
            if (judge == null) {
                onLog("「${node.name}」直到判定不存在：${node.untilJudgeId}")
            } else {
                val timeout = if (node.untilTimeoutMs > 0) node.untilTimeoutMs else node.durationMs
                val deadline = System.currentTimeMillis() + timeout
                while (System.currentTimeMillis() < deadline && !stopRequested) {
                    val hit = matchJudgeOnce(judge, project, templates)
                    if (hit) {
                        onLog("「${node.name}」直到判定「${judge.name}」成立，提前继续")
                        return nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
                    }
                    delay(300)
                }
                onLog("「${node.name}」等待判定超时（${timeout}ms）")
            }
        } else if (node.untilTemplateId.isNotBlank()) {
            val timeout = if (node.untilTimeoutMs > 0) node.untilTimeoutMs else node.durationMs
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline && !stopRequested) {
                val hit = matchTemplateOnce(node.untilTemplateId, templates, 0.7)
                if (hit) {
                    onLog("「${node.name}」直到识别到 ${node.untilTemplateId}，提前继续")
                    return nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
                }
                delay(300)
            }
            onLog("「${node.name}」等待超时（${timeout}ms）")
        } else {
            onLog("「${node.name}」等待 ${node.durationMs}ms")
            delay(node.durationMs)
        }
        return nextOf(node.id, LinkType.YES, project) ?: firstOut(node.id, project)
    }

    /** 只执行判定的识别部分（不广播、不跳转），供"等待直到判定"复用 */
    private suspend fun matchJudgeOnce(judge: FlowNode, project: FlowProject, templates: List<FlowTemplate>): Boolean {
        return when (judge.kind) {
            FlowNodeKind.IMAGE ->
                if (judge.templateId.isBlank()) false
                else matchTemplateOnce(judge.templateId, templates, judge.threshold)
            FlowNodeKind.APP_STATE -> {
                val pkg = judge.appStatePkg
                if (pkg.isBlank()) false
                else if (judge.appStateMode == "alive") runCatching { remote.isAppAlive(pkg) > 0 }.getOrDefault(false)
                else runCatching { remote.isAppOnVirtualDisplay(pkg) }.getOrDefault(false)
            }
            else -> false
        }
    }

    private suspend fun executeImage(node: FlowNode, project: FlowProject, templates: List<FlowTemplate>): String? {
        if (node.templateId.isBlank()) {
            onLog("「${node.name}」未选模板，按未命中处理")
            sendSignal(node.id, project)
            return nextOfBranch(node, project)
        }
        var attempts = 0
        val maxRetries = node.maxRetries
        while (true) {
            if (stopRequested) return null
            attempts++
            val hit = matchTemplateOnce(node.templateId, templates, node.threshold)
            if (hit) {
                onLog("「${node.name}」识别到 ${node.templateId}（第${attempts}次）")
                sendSignal(node.id, project)
                return nextOfBranch(node, project)
            }
            if (maxRetries > 0 && attempts <= maxRetries) {
                onLog("「${node.name}」未命中，${node.retryIntervalMs}ms 后重试（$attempts/$maxRetries）")
                delay(node.retryIntervalMs)
            } else {
                onLog("「${node.name}」未识别到 ${node.templateId}，走非路径")
                sendSignal(node.id, project)
                return nextOfBranch(node, project)
            }
        }
    }

    private suspend fun matchTemplateOnce(templateId: String, templates: List<FlowTemplate>, threshold: Double): Boolean {
        return runCatching {
            val tpl = templates.find { it.id == templateId } ?: return false
            val shot = takeScreenshot() ?: return false
            val screen = BitmapFactory.decodeFile(shot.absolutePath) ?: return false
            val tplBmp = BitmapFactory.decodeFile(tpl.file) ?: return false
            val found = matchTemplate(screen, tplBmp, threshold)
            lastMatchPoint = found
            found != null
        }.getOrDefault(false)
    }

    private suspend fun takeScreenshot(): File? {
        val dir = screenshotDir ?: File("/data/local/tmp", "flow_shots").apply { mkdirs() }.also { screenshotDir = it }
        return runCatching { remote.captureFramePng(dir.absolutePath)?.let { File(it) } }.getOrNull()
    }

    private suspend fun executeAction(action: FlowAction) {
        when (action) {
            is FlowAction.Tap -> {
                onLog("点击 (${action.x},${action.y}) ${action.durationMs}ms")
                remote.touchDown(action.x, action.y)
                delay(action.durationMs)
                remote.touchUp(action.x, action.y)
            }
            is FlowAction.Input -> {
                onLog("输入文本：${action.text}")
                remote.inputText(action.text)
            }
            is FlowAction.TapTemplate -> {
                val p = lastMatchPoint
                if (p != null) {
                    onLog("点击模板中心 (${p.first},${p.second})")
                    remote.touchDown(p.first, p.second)
                    delay(60)
                    remote.touchUp(p.first, p.second)
                } else {
                    onLog("无模板命中点，跳过点击")
                }
            }
            is FlowAction.Wait -> {
                onLog("等待 ${action.ms}ms")
                delay(action.ms)
            }
            is FlowAction.Back -> {
                repeat(action.times) {
                    onLog("返回")
                    remote.touchDown(60, 1200)
                    delay(60)
                    remote.touchUp(60, 1200)
                    delay(300)
                }
            }
            is FlowAction.Swipe -> {
                onLog("滑动 (${action.fromX},${action.fromY})→(${action.toX},${action.toY})")
                remote.touchDown(action.fromX, action.fromY)
                delay(100)
                remote.touchMove(action.toX, action.toY)
                delay(action.durationMs)
                remote.touchUp(action.toX, action.toY)
            }
        }
    }

    /** 突发检查：判定命中 → 中断 → 执行区间 → 命中继续节点 */
    private suspend fun checkBurst(burst: FlowBurst, project: FlowProject, templates: List<FlowTemplate>) {
        if (stopRequested) return
        val judge = project.nodes.find { it.id == burst.judgeNodeId } ?: return
        if (judge.kind != FlowNodeKind.IMAGE || judge.templateId.isBlank()) return
        val hit = matchTemplateOnce(judge.templateId, templates, judge.threshold)
        if (hit) {
            onLog("🚨 突发「${burst.name}」判定命中：${judge.templateId}，中断执行突发区间")
            for (nid in burst.nodeIds) {
                if (stopRequested) return
                val n = project.nodes.find { it.id == nid } ?: continue
                onNode(n.name)
                when (n.kind) {
                    FlowNodeKind.INFO -> { /* 突发区间不含流程信息 */ }
                    FlowNodeKind.ACTION, FlowNodeKind.TAP, FlowNodeKind.SWIPE,
                    FlowNodeKind.BACK, FlowNodeKind.INPUT -> executeAction(n.action)
                    FlowNodeKind.TIME, FlowNodeKind.WAIT -> {
                        delay(n.durationMs)
                        onLog("突发「${n.name}」等待 ${n.durationMs}ms")
                    }
                    FlowNodeKind.LOOP_START -> { /* 循环起点占位 */ }
                    FlowNodeKind.CONJUNCTION, FlowNodeKind.DISJUNCTION,
                    FlowNodeKind.LOOP_END -> { /* 突发区间不含控制节点 */ }
                    FlowNodeKind.IMAGE -> {
                        var guard2 = 0
                        while (!matchTemplateOnce(n.templateId, templates, n.threshold) && guard2 < 10 && !stopRequested) {
                            delay(300); guard2++
                        }
                    }
                    FlowNodeKind.LOOP, FlowNodeKind.APP_STATE -> { /* 突发区间不含控制/判定 */ }
                }
            }
            onLog("突发执行完，跳转继续节点")
            lastBurstContinue = burst.hitContinueId
            burstPendingJump = true
        }
    }

    private fun linkTypeLabel(type: com.mas.autofarm.presentation.view.workshop.LinkType): String = when (type) {
        com.mas.autofarm.presentation.view.workshop.LinkType.SEQUENCE -> "顺序"
        com.mas.autofarm.presentation.view.workshop.LinkType.YES -> "是"
        com.mas.autofarm.presentation.view.workshop.LinkType.NO -> "非"
        com.mas.autofarm.presentation.view.workshop.LinkType.AND -> "与"
        com.mas.autofarm.presentation.view.workshop.LinkType.OR -> "或"
    }

    private fun nextOf(fromId: String, type: LinkType, project: FlowProject): String? {
        val to = project.links
            .filter { it.type == type && it.fromIds == listOf(fromId) }
            .firstOrNull()?.toId
        if (to != null) {
            val fromName = project.nodes.find { it.id == fromId }?.name ?: fromId
            val toName = project.nodes.find { it.id == to }?.name ?: to
            onLog("[${linkTypeLabel(type)}] $fromName → $toName")
        }
        return to
    }

    private fun firstOut(fromId: String, project: FlowProject): String? {
        val link = project.links.firstOrNull { it.fromIds.contains(fromId) && it.toId != fromId }
        if (link != null) {
            val fromName = project.nodes.find { it.id == fromId }?.name ?: fromId
            val toName = project.nodes.find { it.id == link.toId }?.name ?: link.toId
            onLog("[任意线] $fromName → $toName")
        }
        return link?.toId
    }

    /**
     * 自研模板匹配：灰度平均绝对差（透明像素掩码跳过）。
     */
    private fun matchTemplate(screen: Bitmap, tpl: Bitmap, threshold: Double): Pair<Int, Int>? {
        val sw = screen.width
        val sh = screen.height
        val tw = tpl.width
        val th = tpl.height
        if (tw <= 0 || th <= 0 || tw > sw || th > sh) return null
        val sGray = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) sGray[y * sw + x] = gray(screen.getPixel(x, y))
        val tGray = IntArray(tw * th)
        val tAlpha = BooleanArray(tw * th)
        var tCount = 0
        for (y in 0 until th) for (x in 0 until tw) {
            val px = tpl.getPixel(x, y)
            tGray[y * tw + x] = gray(px)
            val a = android.graphics.Color.alpha(px)
            tAlpha[y * tw + x] = a > 16
            if (a > 16) tCount++
        }
        if (tCount == 0) return null
        var best = Double.MAX_VALUE
        var bx = 0
        var by = 0
        for (y in 0..(sh - th) step 2) {
            for (x in 0..(sw - tw) step 2) {
                var sum = 0.0
                var cnt = 0
                for (ty in 0 until th) {
                    val sy = y + ty
                    if (sy >= sh) break
                    for (tx in 0 until tw) {
                        if (!tAlpha[ty * tw + tx]) continue
                        sum += Math.abs(sGray[sy * sw + x + tx] - tGray[ty * tw + tx])
                        cnt++
                    }
                }
                if (cnt > 0) {
                    val avg = sum / cnt
                    if (avg < best) {
                        best = avg
                        bx = x
                        by = y
                    }
                }
            }
        }
        val score = 1.0 - best / 255.0
        return if (score >= threshold) Pair(bx + tw / 2, by + th / 2) else null
    }

    private fun gray(px: Int): Int {
        val r = android.graphics.Color.red(px)
        val g = android.graphics.Color.green(px)
        val b = android.graphics.Color.blue(px)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}