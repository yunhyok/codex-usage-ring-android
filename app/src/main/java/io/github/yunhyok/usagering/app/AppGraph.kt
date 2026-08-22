package io.github.yunhyok.usagering.app

import android.content.Context
import android.content.Intent
import io.github.yunhyok.usagering.BuildConfig
import io.github.yunhyok.usagering.data.DefaultNativeCodexBridge
import io.github.yunhyok.usagering.data.MockUsageRepository
import io.github.yunhyok.usagering.data.NativeCodexBridge
import io.github.yunhyok.usagering.data.NativeUsageSource
import io.github.yunhyok.usagering.data.DeviceCodeLoginController
import io.github.yunhyok.usagering.data.LoginOperationCoordinator
import io.github.yunhyok.usagering.data.LoginPollingService
import io.github.yunhyok.usagering.data.StoredUsageRepository
import io.github.yunhyok.usagering.data.UsageRepository
import androidx.core.content.ContextCompat

object AppGraph {
    @Volatile private var repository: UsageRepository? = null
    @Volatile private var bridge: NativeCodexBridge? = null
    @Volatile private var login: DeviceCodeLoginController? = null
    @Volatile private var loginOperationCoordinator: LoginOperationCoordinator? = null

    fun nativeBridge(context: Context): NativeCodexBridge = bridge ?: synchronized(this) {
        bridge ?: DefaultNativeCodexBridge(context.applicationContext).also { bridge = it }
    }

    fun loginController(context: Context): DeviceCodeLoginController = login ?: synchronized(this) {
        login ?: DeviceCodeLoginController(context.applicationContext, nativeBridge(context)).also { login = it }
    }

    fun loginOperations(context: Context): LoginOperationCoordinator = loginOperationCoordinator ?: synchronized(this) {
        loginOperationCoordinator ?: run {
            val appContext = context.applicationContext
            LoginOperationCoordinator(
                loginController(appContext),
                startPolling = {
                    runCatching {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, LoginPollingService::class.java)
                                .setAction(LoginPollingService.ACTION_START),
                        )
                    }.isSuccess
                },
                stopPolling = {
                    appContext.stopService(Intent(appContext, LoginPollingService::class.java))
                },
            ).also { loginOperationCoordinator = it }
        }
    }

    fun initialize(context: Context) {
        if (BuildConfig.FLAVOR == "native") runCatching { nativeBridge(context).start() }
    }

    fun usageRepository(context: Context): UsageRepository = repository ?: synchronized(this) {
        repository ?: (if (BuildConfig.FLAVOR == "mock") MockUsageRepository(context) else {
            val native = nativeBridge(context)
            StoredUsageRepository(context.applicationContext, NativeUsageSource(native))
        }).also { repository = it }
    }

    fun mockRepository(context: Context): MockUsageRepository? =
        usageRepository(context) as? MockUsageRepository
}
