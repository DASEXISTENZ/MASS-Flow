package com.yuanqian.autofarm.koin

import com.yuanqian.autofarm.data.api.ETagCacheManager
import com.yuanqian.autofarm.data.api.HttpClientHelper
import com.yuanqian.autofarm.data.api.MaaApiService
import com.yuanqian.autofarm.data.api.MirrorChyanApiClient
import com.yuanqian.autofarm.data.config.MaaPathConfig
import com.yuanqian.autofarm.data.datasource.AppDownloader
import com.yuanqian.autofarm.data.datasource.AssetExtractor
import com.yuanqian.autofarm.data.datasource.ResourceDownloader
import com.yuanqian.autofarm.data.datasource.ZipExtractor
import com.yuanqian.autofarm.data.datasource.update.MirrorChyanAppVersionChecker
import com.yuanqian.autofarm.data.datasource.update.MirrorChyanResourceVersionChecker
import com.yuanqian.autofarm.data.log.ApplicationLogWriter
import com.yuanqian.autofarm.data.notification.NotificationSettingsManager
import com.yuanqian.autofarm.data.notification.provider.BarkProvider
import com.yuanqian.autofarm.data.notification.provider.CustomWebhookProvider
import com.yuanqian.autofarm.data.notification.provider.DingTalkProvider
import com.yuanqian.autofarm.data.notification.provider.DiscordProvider
import com.yuanqian.autofarm.data.notification.provider.DiscordWebhookProvider
import com.yuanqian.autofarm.data.notification.provider.GotifyProvider
import com.yuanqian.autofarm.data.notification.provider.KookProvider
import com.yuanqian.autofarm.data.notification.provider.NotificationProvider
import com.yuanqian.autofarm.data.notification.provider.QmsgProvider
import com.yuanqian.autofarm.data.notification.provider.ServerChanProvider
import com.yuanqian.autofarm.data.notification.provider.SmtpProvider
import com.yuanqian.autofarm.data.notification.provider.TelegramProvider
import com.yuanqian.autofarm.data.preferences.AppSettingsManager
import com.yuanqian.autofarm.data.preferences.ConfigBackupManager
import com.yuanqian.autofarm.data.preferences.TaskChainState
import com.yuanqian.autofarm.data.repository.DepotRepository
import com.yuanqian.autofarm.data.repository.OperBoxRepository
import com.yuanqian.autofarm.data.resource.ActivityManager
import com.yuanqian.autofarm.data.resource.BackgroundImageStore
import com.yuanqian.autofarm.data.resource.ItemHelper
import com.yuanqian.autofarm.data.resource.ItemIconLoader
import com.yuanqian.autofarm.data.resource.ResourceDataManager
import com.yuanqian.autofarm.domain.service.AppAliveChecker
import com.yuanqian.autofarm.domain.service.AppWatchdog
import com.yuanqian.autofarm.domain.service.WakeUnlockEngine
import com.yuanqian.autofarm.domain.service.ExternalNotificationService
import com.yuanqian.autofarm.domain.service.FightDropsRefresher
import com.yuanqian.autofarm.domain.service.GameMuteCoordinator
import com.yuanqian.autofarm.domain.service.LogExportService
import com.yuanqian.autofarm.domain.service.MaaCompositionService
import com.yuanqian.autofarm.domain.service.MaaEventNotifier
import com.yuanqian.autofarm.domain.service.MaaNotificationCenter
import com.yuanqian.autofarm.domain.service.MaaResourceLoader
import com.yuanqian.autofarm.domain.service.MaaSessionLogger
import com.yuanqian.autofarm.domain.service.RemoteAppAliveChecker
import com.yuanqian.autofarm.domain.service.ResourceInitService
import com.yuanqian.autofarm.domain.service.ScreenSaverController
import com.yuanqian.autofarm.domain.service.TaskEndRegistry
import com.yuanqian.autofarm.domain.service.ToolboxExportService
import com.yuanqian.autofarm.domain.service.UnifiedStateDispatcher
import com.yuanqian.autofarm.domain.service.update.UpdateService
import com.yuanqian.autofarm.domain.service.update.checker.AppVersionChecker
import com.yuanqian.autofarm.domain.service.update.checker.ResourceVersionChecker
import com.yuanqian.autofarm.maa.callback.ConnectionInfoHandler
import com.yuanqian.autofarm.maa.callback.MaaCallbackDispatcher
import com.yuanqian.autofarm.maa.callback.MaaExecutionStateHolder
import com.yuanqian.autofarm.maa.callback.SubTaskHandler
import com.yuanqian.autofarm.maa.callback.TaskChainHandler
import com.yuanqian.autofarm.maa.callback.TaskChainStatusTracker
import com.yuanqian.autofarm.maa.callback.ToolboxResultCollector
import com.yuanqian.autofarm.manager.PermissionManager
import com.yuanqian.autofarm.manager.RemoteGameAudioAdapter
import com.yuanqian.autofarm.manager.ShizukuReadinessProvider
import com.yuanqian.autofarm.overlay.OverlayController
import com.yuanqian.autofarm.overlay.OverlayViewModelOwner
import com.yuanqian.autofarm.overlay.border.BorderOverlayManager
import com.yuanqian.autofarm.overlay.screensaver.ScreenSaverOverlayManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.yuanqian.autofarm.domain.launch.CountdownUI
import com.yuanqian.autofarm.domain.launch.LaunchMutex
import com.yuanqian.autofarm.domain.launch.LaunchPipeline
import com.yuanqian.autofarm.domain.launch.LaunchRequest
import com.yuanqian.autofarm.domain.launch.StartTaskChainUseCase
import com.yuanqian.autofarm.manager.RemoteServiceManager
import com.yuanqian.autofarm.schedule.LaunchIntentMapper
import com.yuanqian.autofarm.schedule.data.ScheduleStrategyRepository
import com.yuanqian.autofarm.schedule.service.CountdownUIImpl
import com.yuanqian.autofarm.schedule.service.ScheduleAlarmManager
import com.yuanqian.autofarm.schedule.service.ScheduleTriggerLogger
import com.yuanqian.autofarm.utils.CrashHandler
import com.yuanqian.autofarm.utils.log.LogTreeHolder
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
