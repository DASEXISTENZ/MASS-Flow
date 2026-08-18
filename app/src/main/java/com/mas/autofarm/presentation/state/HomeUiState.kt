package com.mas.autofarm.presentation.state

import com.mas.autofarm.data.model.update.UpdateProcessState
import com.mas.autofarm.domain.models.OverlayControlMode
import com.mas.autofarm.domain.models.RunMode
import com.mas.autofarm.domain.state.ResourceInitState
import com.mas.autofarm.utils.i18n.UiText

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
