package com.yuanqian.autofarm.utils.i18n

import android.content.Context
import com.yuanqian.autofarm.R
import com.yuanqian.autofarm.domain.models.OverlayControlMode
import com.yuanqian.autofarm.domain.models.RemoteBackend
import com.yuanqian.autofarm.domain.models.RunMode
import com.yuanqian.autofarm.domain.service.MaaResourceLoader

fun Context.runModeDisplayName(mode: RunMode): String {
    return when (mode) {
        RunMode.FOREGROUND -> getString(R.string.home_run_mode_foreground)
        RunMode.BACKGROUND -> getString(R.string.home_run_mode_background)
    }
}

fun Context.overlayControlModeDisplayName(mode: OverlayControlMode): String {
    return when (mode) {
        OverlayControlMode.ACCESSIBILITY -> getString(R.string.home_overlay_mode_accessibility)
        OverlayControlMode.FLOAT_BALL -> getString(R.string.home_overlay_mode_float_ball)
    }
}

fun Context.wakeUpClientTypeDisplayName(clientType: String): String =
    com.yuanqian.autofarm.constant.Packages.displayNameOf(clientType) ?: clientType

fun Context.remoteBackendPermissionLabel(backend: RemoteBackend): String {
    return getString(R.string.remote_backend_permission_label, backend.display)
}

fun Context.resourceLoaderMessage(state: MaaResourceLoader.State): String {
    return when (state) {
        is MaaResourceLoader.State.Loading -> {
            state.message.ifBlank { getString(R.string.resource_loader_loading_message) }
        }

        is MaaResourceLoader.State.Reloading -> {
            state.message.ifBlank { getString(R.string.resource_loader_reloading_message) }
        }

        else -> ""
    }
}
