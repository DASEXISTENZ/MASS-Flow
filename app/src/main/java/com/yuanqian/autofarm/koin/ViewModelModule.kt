package com.yuanqian.autofarm.koin

import com.yuanqian.autofarm.presentation.viewmodel.AppEventsViewModel
import com.yuanqian.autofarm.presentation.viewmodel.BackgroundTaskViewModel
import com.yuanqian.autofarm.presentation.viewmodel.ErrorLogViewModel
import com.yuanqian.autofarm.presentation.viewmodel.ExpandedControlPanelViewModel
import com.yuanqian.autofarm.presentation.viewmodel.HomeViewModel
import com.yuanqian.autofarm.presentation.viewmodel.LogHistoryViewModel
import com.yuanqian.autofarm.presentation.viewmodel.NotificationSettingsViewModel
import com.yuanqian.autofarm.presentation.viewmodel.SettingsViewModel
import com.yuanqian.autofarm.presentation.viewmodel.TaskOverrideEditorViewModel
import com.yuanqian.autofarm.presentation.viewmodel.ToolboxViewModel
import com.yuanqian.autofarm.presentation.viewmodel.UpdateViewModel
import com.yuanqian.autofarm.schedule.ui.ScheduleEditViewModel
import com.yuanqian.autofarm.schedule.ui.ScheduleListViewModel
import com.yuanqian.autofarm.schedule.ui.ScheduleTriggerLogViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module


val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::AppEventsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::LogHistoryViewModel)
    viewModelOf(::ErrorLogViewModel)
    viewModelOf(::BackgroundTaskViewModel)
    viewModelOf(::ScheduleListViewModel)
    viewModelOf(::ScheduleEditViewModel)
    viewModelOf(::ScheduleTriggerLogViewModel)
    viewModelOf(::NotificationSettingsViewModel)
    viewModelOf(::TaskOverrideEditorViewModel)
}


val floatingWindowModule = module {
    singleOf(::ExpandedControlPanelViewModel)
    singleOf(::ToolboxViewModel)
}
