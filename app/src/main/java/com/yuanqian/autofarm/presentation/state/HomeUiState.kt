package com.yuanqian.autofarm.presentation.state

import com.yuanqian.autofarm.data.model.update.UpdateProcessState
import com.yuanqian.autofarm.domain.models.OverlayControlMode
import com.yuanqian.autofarm.domain.models.RunMode
import com.yuanqian.autofarm.domain.state.ResourceInitState
import com.yuanqian.autofarm.utils.i18n.UiText

data class HomeUiState(
    val isShowControlOverlay: Boolean = false,
    val isLoading: Boolean = false,
    val resourceUpdateState: UpdateProcessState = UpdateProcessState.Idle,
    val serviceStatusText: UiText = UiText.Empty,
    val serviceStatusColor: StatusColorType = StatusColorType.NEUTRAL,
    val serviceStatusLoading: Boolean = false,
    val remoteServiceActive: Boolean = false,
    val resourceInitState: ResourceInitState = ResourceInitState.NotChecked,
    val runMode: RunMode = RunMode.BACKGROUND,
    val overlayControlMode: OverlayControlMode = OverlayControlMode.FLOAT_BALL,
    val isGranting: Boolean = false,
    val showRunModeUnsupportedDialog: Boolean = false,
    val runModeUnsupportedMessage: UiText = UiText.Empty
)
