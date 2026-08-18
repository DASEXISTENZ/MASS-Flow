package com.mas.autofarm.koin

import com.mas.autofarm.data.api.ETagCacheManager
import com.mas.autofarm.data.api.HttpClientHelper
import com.mas.autofarm.data.api.MaaApiService
import com.mas.autofarm.data.api.MirrorChyanApiClient
import com.mas.autofarm.data.config.MaaPathConfig
import com.mas.autofarm.data.datasource.AppDownloader
import com.mas.autofarm.data.datasource.AssetExtractor
import com.mas.autofarm.data.datasource.ResourceDownloader
import com.mas.autofarm.data.datasource.ZipExtractor
import com.mas.autofarm.data.datasource.update.MirrorChyanAppVersionChecker
import com.mas.autofarm.data.datasource.update.MirrorChyanResourceVersionChecker
import com.mas.autofarm.data.log.ApplicationLogWriter
import com.mas.autofarm.data.notification.NotificationSettingsManager
import com.mas.autofarm.data.notification.provider.BarkProvider
import com.mas.autofarm.data.notification.provider.CustomWebhookProvider
import com.mas.autofarm.data.notification.provider.DingTalkProvider
import com.mas.autofarm.data.notification.provider.DiscordProvider
import com.mas.autofarm.data.notification.provider.DiscordWebhookProvider
import com.mas.autofarm.data.notification.provider.GotifyProvider
import com.mas.autofarm.data.notification.provider.KookProvider
import com.mas.autofarm.data.notification.provider.NotificationProvider
import com.mas.autofarm.data.notification.provider.QmsgProvider
import com.mas.autofarm.data.notification.provider.ServerChanProvider
import com.mas.autofarm.data.notification.provider.SmtpProvider
import com.mas.autofarm.data.notification.provider.TelegramProvider
import com.mas.autofarm.data.preferences.AppSettingsManager
import com.mas.autofarm.data.preferences.ConfigBackupManager
import com.mas.autofarm.data.preferences.TaskChainState
import com.mas.autofarm.data.repository.DepotRepository
import com.mas.autofarm.data.repository.OperBoxRepository
import com.mas.autofarm.data.resource.ActivityManager
import com.mas.autofarm.data.resource.BackgroundImageStore
import com.mas.autofarm.data.resource.ItemHelper
import com.mas.autofarm.data.resource.ItemIconLoader
import com.mas.autofarm.data.resource.ResourceDataManager
import com.mas.autofarm.domain.service.AppAliveChecker
import com.mas.autofarm.domain.service.AppWatchdog
import com.mas.autofarm.domain.service.WakeUnlockEngine
import com.mas.autofarm.domain.service.ExternalNotificationService
import com.mas.autofarm.domain.service.FightDropsRefresher
import com.mas.autofarm.domain.service.GameMuteCoordinator
import com.mas.autofarm.domain.service.LogExportService
import com.mas.autofarm.domain.service.MaaCompositionService
import com.mas.autofarm.domain.service.MaaEventNotifier
import com.mas.autofarm.domain.service.MaaNotificationCenter
import com.mas.autofarm.domain.service.MaaResourceLoader
import com.mas.autofarm.domain.service.MaaSessionLogger
import com.mas.autofarm.domain.service.RemoteAppAliveChecker
import com.mas.autofarm.domain.service.ResourceInitService
import com.mas.autofarm.domain.service.ScreenSaverController
import com.mas.autofarm.domain.service.TaskEndRegistry
import com.mas.autofarm.domain.service.ToolboxExportService
import com.mas.autofarm.domain.service.UnifiedStateDispatcher
import com.mas.autofarm.domain.service.update.UpdateService
import com.mas.autofarm.domain.service.update.checker.AppVersionChecker
import com.mas.autofarm.domain.service.update.checker.ResourceVersionChecker
import com.mas.autofarm.maa.callback.ConnectionInfoHandler
import com.mas.autofarm.maa.callback.MaaCallbackDispatcher
import com.mas.autofarm.maa.callback.MaaExecutionStateHolder
import com.mas.autofarm.maa.callback.SubTaskHandler
import com.mas.autofarm.maa.callback.TaskChainHandler
import com.mas.autofarm.maa.callback.TaskChainStatusTracker
import com.mas.autofarm.maa.callback.ToolboxResultCollector
import com.mas.autofarm.manager.PermissionManager
import com.mas.autofarm.manager.RemoteGameAudioAdapter
import com.mas.autofarm.manager.ShizukuReadinessProvider
import com.mas.autofarm.overlay.OverlayController
import com.mas.autofarm.overlay.OverlayViewModelOwner
import com.mas.autofarm.overlay.border.BorderOverlayManager
import com.mas.autofarm.overlay.screensaver.ScreenSaverOverlayManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.mas.autofarm.domain.launch.CountdownUI
import com.mas.autofarm.domain.launch.LaunchMutex
import com.mas.autofarm.domain.launch.LaunchPipeline
import com.mas.autofarm.domain.launch.LaunchRequest
import com.mas.autofarm.domain.launch.StartTaskChainUseCase
import com.mas.autofarm.manager.RemoteServiceManager
import com.mas.autofarm.schedule.LaunchIntentMapper
import com.mas.autofarm.schedule.data.ScheduleStrategyRepository
import com.mas.autofarm.schedule.service.CountdownUIImpl
import com.mas.autofarm.schedule.service.ScheduleAlarmManager
import com.mas.autofarm.schedule.service.ScheduleTriggerLogger
import com.mas.autofarm.utils.CrashHandler
import com.mas.autofarm.utils.log.LogTreeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

val appModule = module {


    singleOf(::CrashHandler)
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    singleOf(::HttpClientHelper)
    singleOf(::ETagCacheManager)
    singleOf(::MaaApiService)
    singleOf(::PermissionManager)
    singleOf(::ShizukuReadinessProvider)


    singleOf(::AppSettingsManager)
    singleOf(::BackgroundImageStore)
    singleOf(::ScheduleStrategyRepository)
    singleOf(::ScheduleTriggerLogger)
    singleOf(::ScheduleAlarmManager)
    singleOf(::LaunchMutex)
    singleOf(::StartTaskChainUseCase)
    single(named("launchPipeline")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<CountdownUI> {
        CountdownUIImpl(
            overlayController = get(),
            onUserEvent = { event -> get<LaunchPipeline>().submit(event) },
        )
    }
    single {
        val appContext = get<Context>()
        LaunchPipeline(
            scope = get(named("launchPipeline")),
            mutex = get(),
            appSettingsManager = get(),
            wakeUnlockEngine = get(),
            chainState = get(),
            compositionService = get(),
            triggerLogger = get(),
            scheduleRepository = get(),
            startTaskChain = get(),
            countdownUI = get(),
            screenSaver = get(),
            taskEndRegistry = get(),
            keyguardLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isKeyguardLocked
            },
            deviceSecure = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isDeviceSecure
            },
            screenInteractive = {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isInteractive
            },
            activityLauncher = { request: LaunchRequest ->
                withTimeoutOrNull(10.seconds) {
                    runCatching {
                        RemoteServiceManager.useRemoteService(timeoutMs = 8_000L) {
                            it.startActivity(LaunchIntentMapper.toShowIntent(appContext, request))
                        }
                        true
                    }.getOrDefault(false)
                } ?: false
            },
        )
    }
    singleOf(::TaskChainState)
    singleOf(::ConfigBackupManager)
    singleOf(::MaaPathConfig)
    singleOf(::ResourceDownloader)
    singleOf(::AppDownloader)
    singleOf(::ZipExtractor)
    singleOf(::AssetExtractor)

    // MirrorChyan API Client
    singleOf(::MirrorChyanApiClient)

    // Version Checkers
    single<AppVersionChecker> { MirrorChyanAppVersionChecker(get(), get()) }
    single<ResourceVersionChecker> { MirrorChyanResourceVersionChecker(get()) }

    singleOf(::UpdateService)

    singleOf(::ResourceInitService)
    singleOf(::MaaResourceLoader)
    singleOf(::MaaSessionLogger)

    // 外部通知
    singleOf(::NotificationSettingsManager)
    single { ServerChanProvider(get(), get()) } bind NotificationProvider::class
    single { TelegramProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordProvider(get(), get()) } bind NotificationProvider::class
    single { DingTalkProvider(get(), get()) } bind NotificationProvider::class
    single { KookProvider(get(), get()) } bind NotificationProvider::class
    single { DiscordWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { SmtpProvider(get()) } bind NotificationProvider::class
    single { BarkProvider(get(), get()) } bind NotificationProvider::class
    single { QmsgProvider(get(), get()) } bind NotificationProvider::class
    single { GotifyProvider(get(), get()) } bind NotificationProvider::class
    single { CustomWebhookProvider(get(), get()) } bind NotificationProvider::class
    single { ExternalNotificationService(get(), get(), getAll()) }

    // 通知
    singleOf(::MaaEventNotifier)
    singleOf(::MaaNotificationCenter)

    // 仓库 / 干员箱持久化（按配置档分片）
    single { DepotRepository.create(get(), get()) }
    single { OperBoxRepository.create(get(), get()) }

    // 回调处理链
    singleOf(::ConnectionInfoHandler)
    singleOf(::ToolboxResultCollector)
    singleOf(::TaskChainStatusTracker)
    singleOf(::FightDropsRefresher)
    singleOf(::TaskChainHandler)
    singleOf(::SubTaskHandler)
    single<AppAliveChecker> { RemoteAppAliveChecker() }
    singleOf(::AppWatchdog)
    singleOf(::MaaCompositionService)
    single<MaaExecutionStateHolder> { get<MaaCompositionService>() }
    single { GameMuteCoordinator(get(), RemoteGameAudioAdapter) }
    singleOf(::MaaCallbackDispatcher)

    // 定时唤醒 + 解锁
    singleOf(::WakeUnlockEngine)

    singleOf(::UnifiedStateDispatcher)
    // scope 走构造默认值，singleOf 会试图解析它
    single { TaskEndRegistry(compositionService = get()) }
    singleOf(::LogExportService)
    singleOf(::ToolboxExportService)


    singleOf(::BorderOverlayManager)
    singleOf(::ScreenSaverOverlayManager) { bind<ScreenSaverController>() }
    singleOf(::OverlayViewModelOwner)
    singleOf(::OverlayController)


    singleOf(::ItemHelper)
    singleOf(::ItemIconLoader)
    singleOf(::ActivityManager)
    singleOf(::ResourceDataManager)
    singleOf(::ApplicationLogWriter)
    singleOf(::LogTreeHolder)

    // 前台模式自动任务
}
