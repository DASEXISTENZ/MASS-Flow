package com.mas.autofarm.remote

import android.graphics.Bitmap
import android.os.Process
import android.view.Surface
import com.mas.autofarm.ITouchEventCallback
import com.mas.autofarm.MaaCoreService
import com.mas.autofarm.RemoteService
import com.mas.autofarm.bridge.NativeBridgeLib
import com.mas.autofarm.constant.DefaultDisplayConfig
import com.mas.autofarm.constant.DisplayMode
import com.mas.autofarm.maa.InputControlUtils
import android.content.Intent
import com.mas.autofarm.remote.internal.ActivityUtils
import com.mas.autofarm.remote.internal.GameAudioMuteController
import com.mas.autofarm.remote.internal.PermissionGrantHelper
import com.mas.autofarm.remote.internal.PowerController
import com.mas.autofarm.remote.internal.PrimaryDisplayManager
import com.mas.autofarm.remote.internal.RemoteUtils
import com.mas.autofarm.remote.internal.ScreenManager
import com.mas.autofarm.remote.internal.VirtualDisplayManager
import com.mas.autofarm.remote.internal.WakeUnlockController
import com.mas.autofarm.third.FakeContext
import com.mas.autofarm.third.Ln
import com.mas.autofarm.third.Workarounds
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

class RemoteServiceImpl : RemoteService.Stub() {

    companion object {
        private const val TAG = "RemoteService"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        @JvmStatic
        fun performEmergencyCleanup() {
            Ln.i("$TAG: performEmergencyCleanup triggered")
            runCatching {
                GameAudioMuteController.restoreAll()
                PowerController.destroy()
                ScreenManager.destroy()
                MaaCoreManager.destroy()
            }.onFailure {
                Ln.e("$TAG: Emergency cleanup failed: ${it.message}")
            }
        }
    }

    init {
        RemoteBootTrace.mark("CTOR_START")
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { performEmergencyCleanup() }
        }.apply { name = "remote-shutdown-hook" })
    }

    private val virtualDisplayMode = AtomicInteger(DisplayMode.PRIMARY)
    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)
    private var setup = false

    init {
        Workarounds.apply()
        startHeartbeatWatchdog()
        RemoteBootTrace.mark("CTOR_BEFORE_MAA_SERVICE")
        Ln.i("$TAG: RemoteServiceImpl init, version: ${MaaCoreManager.maaService.GetVersion()}")
        RemoteBootTrace.mark("CTOR_AFTER_MAA_SERVICE")
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return
        }
        Ln.i("$TAG: destroy()")
        InputControlUtils.setTouchCallback(null)
        performEmergencyCleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun getMaaCoreService(): MaaCoreService {
        return MaaCoreManager.maaService
    }

    override fun version(): String {
        val maaVersion = MaaCoreManager.MaaContext?.AsstGetVersion() ?: "Not loaded"
        return """
            ==== Build Info ====
            BridgeInfo: ${NativeBridgeLib.ping()}
            MaaCore Version: $maaVersion
            =====================
        """.trimIndent()
    }

    override fun pid(): Int = Process.myPid()

    override fun setup(userDir: String?, isDebug: Boolean): Boolean {
        if (!setup) {
            val ctx = MaaCoreManager.MaaContext ?: run {
                Ln.e("$TAG: setup failed - MaaContext is null")
                return false
            }
            Ln.i("NativeBridgeLib ping ${NativeBridgeLib.ping()}")
            with(ctx) {
                if (!AsstSetUserDir(userDir)) {
                    Ln.e("$TAG: setup failed - AsstSetUserDir($userDir) returned false")
                    return false
                }
                Ln.i("MaaCore ${AsstGetVersion()}")
            }
            PermissionGrantHelper.disablePhantomProcessKiller()
            setup = true
        }
        return true
    }

    override fun test(map: MutableMap<String, String>) {
    }

    override fun screencap(width: Int, height: Int) {
    }

    /** 物理屏截图（screencap 命令，Shizuku 权限），返回文件路径或 null */
    override fun capturePhysicalPng(dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) {
            Ln.w("$TAG: capturePhysicalPng - blank dirPath")
            return null
        }
        return try {
            val dir = File(dirPath).apply { mkdirs() }
            val file = File(dir, "phys_${System.currentTimeMillis()}.png")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "/system/bin/screencap -p '${file.absolutePath}'"))
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (file.exists() && file.length() > 0) {
                Ln.i("$TAG: capturePhysicalPng saved ${file.absolutePath} (${file.length()}B)")
                file.absolutePath
            } else {
                Ln.w("$TAG: capturePhysicalPng - screencap produced no file")
                file.delete()
                null
            }
        } catch (e: Exception) {
            Ln.e("$TAG: capturePhysicalPng error: ${e.message}")
            null
        }
    }

    override fun captureFramePng(dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) {
            Ln.w("$TAG: captureFramePng - blank dirPath")
            return null
        }
        val bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: run {
            Ln.w("$TAG: captureFramePng - no frame available")
            return null
        }
        return try {
            val dir = File(dirPath).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "screenshot_$timestamp.png")
            val ok = FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!ok) {
                Ln.e("$TAG: captureFramePng - PNG compress failed")
                file.delete()
                return null
            }
            Ln.i("$TAG: captureFramePng saved ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Ln.e("$TAG: captureFramePng error: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        return ScreenManager.setForcedDisplaySize(width, height)
    }

    override fun clearForcedDisplaySize(): Boolean {
        return ScreenManager.clearForcedDisplaySize()
    }

    override fun grantPermissions(request: PermissionGrantRequest): PermissionStateInfo {
        val packageName = request.packageName
        val uid = if (request.uid > 0) request.uid
        else RemoteUtils.getAppUid(packageName).takeIf { it > 0 } ?: request.uid
        val p = request.permissions

        with(PermissionGrantHelper) {
            return PermissionStateInfo(
                accessibilityPermission = if (p and PermissionGrantRequest.PERM_ACCESSIBILITY != 0) grantAccessibilityService(
                    request.accessibilityServiceId
                ) else false,
                floatingWindowPermission = if (p and PermissionGrantRequest.PERM_FLOATING_WINDOW != 0) grantFloatingWindowPermission(
                    packageName,
                    uid
                ) else false,
                notificationPermission = if (p and PermissionGrantRequest.PERM_NOTIFICATION != 0) grantNotificationPermission(
                    packageName,
                    uid
                ) else false,
                batteryOptimizationExempt = if (p and PermissionGrantRequest.PERM_BATTERY != 0) grantBatteryOptimizationExemption(
                    packageName
                ) else false,
                storagePermission = if (p and PermissionGrantRequest.PERM_STORAGE != 0) grantStoragePermission(
                    packageName,
                    uid
                ) else false,
                backgroundUnrestricted = if (p and PermissionGrantRequest.PERM_BACKGROUND != 0) grantBackgroundUnrestricted(
                    packageName,
                    uid
                ) else false,
            )
        }
    }

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        Ln.i("$TAG: setTouchCallback(${callback != null})")
        InputControlUtils.setTouchCallback(callback)
    }

    private fun resolveTouchDisplayId(): Int {
        // PRIMARY（前台直连）→ 注入物理屏（displayId=0）；BACKGROUND → 注入虚拟屏
        return if (virtualDisplayMode.get() == DisplayMode.PRIMARY) 0
        else VirtualDisplayManager.getDisplayId()
    }

    override fun touchDown(x: Int, y: Int) {
        val displayId = resolveTouchDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.down(x, y, displayId)
            InputControlUtils.move(x, y, displayId)
        }
    }

    override fun touchMove(x: Int, y: Int) {
        val displayId = resolveTouchDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.move(x, y, displayId)
        }
    }

    override fun touchUp(x: Int, y: Int) {
        val displayId = resolveTouchDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.up(x, y, displayId)
        }
    }

    override fun setDisplayPower(on: Boolean) {
        PowerController.setDisplayPower(on)
    }

    override fun startVirtualDisplay(): Int {
        Ln.i("$TAG: startVirtualDisplay() ${virtualDisplayMode.get()}")
        return when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.start()
            DisplayMode.BACKGROUND -> VirtualDisplayManager.start().also { displayId ->
                if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                    PowerController.startUserActivityKeepAlive(displayId)
                }
            }

            else -> DefaultDisplayConfig.DISPLAY_NONE
        }
    }

    override fun stopVirtualDisplay() {
        Ln.i("$TAG: stopVirtualDisplay() ${virtualDisplayMode.get()}")
        when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.stop()
            DisplayMode.BACKGROUND -> {
                PowerController.stopUserActivityKeepAlive()
                VirtualDisplayManager.stop()
            }
        }
        GameAudioMuteController.restoreAll()
    }

    override fun setPlayAudioOpAllowed(packageName: String?, isAllowed: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val ok = GameAudioMuteController.setMuted(packageName, muted = !isAllowed)
        if (!ok) {
            Ln.w("$TAG: setPlayAudioOpAllowed($packageName, allowed=$isAllowed) failed")
        }
        return ok
    }

    override fun isAppAlive(packageName: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            when (exitCode) {
                0 if output.isNotEmpty() -> AppAliveStatus.ALIVE
                1 if output.isEmpty() && errorOutput.isEmpty() -> AppAliveStatus.DEAD
                else -> {
                    Ln.w(
                        "$TAG: isAppAlive unexpected result for $packageName: exitCode=$exitCode, stdout=$output, stderr=$errorOutput"
                    )
                    AppAliveStatus.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Ln.w("isAppAlive check failed for $packageName", e)
            AppAliveStatus.UNKNOWN
        }
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
        Ln.i("$TAG: heartbeat received, app pid=$pid")
    }

    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val targetDisplayId = VirtualDisplayManager.getDisplayId()
        if (targetDisplayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        return ActivityUtils.isAppOnDisplay(packageName, targetDisplayId)
    }

    override fun moveAppToVirtualDisplay(packageName: String): Boolean {
        val targetDisplayId = VirtualDisplayManager.getDisplayId()
        if (targetDisplayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: moveAppToVirtualDisplay: no active virtual display")
            return false
        }
        Ln.i("$TAG: moveAppToVirtualDisplay($packageName) -> display $targetDisplayId")
        return ActivityUtils.repinAppToDisplay(packageName, targetDisplayId)
    }

    /** @return [com.mas.autofarm.constant.WakeUnlockResult] */
    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    /** @return [com.mas.autofarm.constant.WakeUnlockResult] */
    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    /** @return [com.mas.autofarm.constant.WakeUnlockResult] */
    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    override fun isPackageInstalled(packageName: String): Boolean {
        return try {
            FakeContext.get().packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
            false
        }
    }

    override fun startActivity(intent: Intent): Boolean {
        return ActivityUtils.startActivity(intent)
    }

    override fun startActivityOnDisplay(intent: Intent, displayId: Int): Boolean {
        return ActivityUtils.startActivity(intent, displayId)
    }

    override fun forceStopApp(packageName: String): Boolean = runCatching {
        com.mas.autofarm.third.wrappers.ServiceManager.getActivityManager()
            .forceStopPackage(packageName)
        true
    }.getOrDefault(false)

    override fun inputText(text: String): Boolean = runCatching {
        val displayId = com.mas.autofarm.remote.internal.VirtualDisplayManager.getDisplayId()
        if (displayId == com.mas.autofarm.constant.DefaultDisplayConfig.DISPLAY_NONE) return false
        text.forEach { c ->
            val key = charToKeyCode(c) ?: return@forEach
            com.mas.autofarm.maa.InputControlUtils.keyDown(key, displayId)
            com.mas.autofarm.maa.InputControlUtils.keyUp(key, displayId)
        }
        true
    }.getOrDefault(false)

    private fun charToKeyCode(c: Char): Int? = when (c) {
        in 'a'..'z', in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
        in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (c - '0')
        ' ' -> android.view.KeyEvent.KEYCODE_SPACE
        '\n' -> android.view.KeyEvent.KEYCODE_ENTER
        '.' -> android.view.KeyEvent.KEYCODE_PERIOD
        ',' -> android.view.KeyEvent.KEYCODE_COMMA
        '-' -> android.view.KeyEvent.KEYCODE_MINUS
        '@' -> android.view.KeyEvent.KEYCODE_AT
        '/' -> android.view.KeyEvent.KEYCODE_SLASH
        ':' -> android.view.KeyEvent.KEYCODE_SEMICOLON
        '_' -> android.view.KeyEvent.KEYCODE_MINUS
        '?' -> android.view.KeyEvent.KEYCODE_SLASH
        else -> null
    }

    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        Ln.i("$TAG: setForceFullscreenOnVirtualDisplay($enabled)")
        ActivityUtils.forceFullscreenOnVirtualDisplay = enabled
    }

    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        Ln.i("$TAG: setVirtualDisplayResolution(${width}x${height}, dpi=$dpi)")
        VirtualDisplayManager.setResolution(width, height, dpi)
    }

    override fun setVirtualDisplayMode(mode: Int): Boolean {
        when (mode) {
            DisplayMode.PRIMARY -> {
                VirtualDisplayManager.stop()
                virtualDisplayMode.set(mode)
                return true
            }

            DisplayMode.BACKGROUND -> {
                PrimaryDisplayManager.stop()
                virtualDisplayMode.set(mode)
                return true
            }
        }
        return false
    }

    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) {
                    continue
                }
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) no longer exists, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }
}
