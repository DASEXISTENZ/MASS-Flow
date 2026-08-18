package com.mas.autofarm.koin

import com.mas.autofarm.presentation.viewmodel.AppEventsViewModel
import com.mas.autofarm.presentation.viewmodel.BackgroundTaskViewModel
import com.mas.autofarm.presentation.viewmodel.ErrorLogViewModel
import com.mas.autofarm.presentation.viewmodel.ExpandedControlPanelViewModel
import com.mas.autofarm.presentation.viewmodel.HomeViewModel
import com.mas.autofarm.presentation.viewmodel.LogHistoryViewModel
import com.mas.autofarm.presentation.viewmodel.NotificationSettingsViewModel
import com.mas.autofarm.presentation.viewmodel.SettingsViewModel
import com.mas.autofarm.presentation.viewmodel.TaskOverrideEditorViewModel
import com.mas.autofarm.presentation.viewmodel.ToolboxViewModel
import com.mas.autofarm.presentation.viewmodel.UpdateViewModel
import com.mas.autofarm.schedule.ui.ScheduleEditViewModel
import com.mas.autofarm.schedule.ui.ScheduleListViewModel
import com.mas.autofarm.schedule.ui.ScheduleTriggerLogViewModel
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
