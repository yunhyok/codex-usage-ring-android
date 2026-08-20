package io.github.yunhyok.usagering.app

import android.content.Context
import io.github.yunhyok.usagering.BuildConfig
import io.github.yunhyok.usagering.data.DefaultNativeCodexBridge
import io.github.yunhyok.usagering.data.MockUsageRepository
import io.github.yunhyok.usagering.data.NativeCodexBridge
import io.github.yunhyok.usagering.data.StoredUsageRepository
import io.github.yunhyok.usagering.data.UsageRepository
import io.github.yunhyok.usagering.data.UsageSource
import io.github.yunhyok.usagering.domain.UsageSnapshotPatch

object AppGraph {
    @Volatile private var repository: UsageRepository? = null
    val nativeBridge: NativeCodexBridge = DefaultNativeCodexBridge()

    fun usageRepository(context: Context): UsageRepository = repository ?: synchronized(this) {
        repository ?: (if (BuildConfig.FLAVOR == "mock") MockUsageRepository(context) else StoredUsageRepository(
            context.applicationContext,
            object : UsageSource { override suspend fun fetch() = UsageSnapshotPatch() },
        )).also { repository = it }
    }

    fun mockRepository(context: Context): MockUsageRepository? =
        usageRepository(context) as? MockUsageRepository
}
