package com.yuanqian.autofarm.remote

import com.yuanqian.autofarm.maa.MaaCoreLibrary
import com.yuanqian.autofarm.third.Ln
import com.sun.jna.Native

object MaaCoreManager {
    private const val TAG = "MaaCoreManager"

    val MaaContext: MaaCoreLibrary? by lazy {
        runCatching {
            System.setProperty("jna.tmpdir", "/data/local/tmp")
            RemoteBootTrace.mark("MAA_LOAD_BEGIN", "jna.tmpdir=/data/local/tmp")
            Ln.i("$TAG: Loading MaaCore...")
            Native.load("MaaCore", MaaCoreLibrary::class.java).also {
                RemoteBootTrace.mark("MAA_LOAD_OK")
                Ln.i("$TAG: MaaCore loaded successfully")
            }
        }.onFailure {
            RemoteBootTrace.mark("MAA_LOAD_FAIL", "${it.javaClass.simpleName}: ${it.message}")
            Ln.e("$TAG: Failed to load MaaCore: ${it.message}")
            Ln.e(it.stackTraceToString())
        }.getOrNull()
    }

    val maaService: MaaCoreServiceImpl = MaaCoreServiceImpl(MaaContext)

    fun destroy() {
        Ln.i("$TAG: destroy()")
        maaService.DestroyInstance()
    }
}