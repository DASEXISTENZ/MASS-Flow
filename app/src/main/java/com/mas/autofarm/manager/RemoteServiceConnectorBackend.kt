package com.mas.autofarm.manager

import android.os.IBinder
import com.mas.autofarm.domain.models.RemoteBackend

interface RemoteServiceConnectorBackend {
    val backend: RemoteBackend

    fun connect(callbacks: Callbacks)

    fun disconnect(currentBinder: IBinder?)

    interface Callbacks {
        fun onConnected(backend: RemoteBackend, binder: IBinder)

        fun onDisconnected(backend: RemoteBackend)

        fun onError(backend: RemoteBackend, throwable: Throwable)
    }
}
