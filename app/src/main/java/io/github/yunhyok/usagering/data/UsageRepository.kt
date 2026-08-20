package io.github.yunhyok.usagering.data

import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageSnapshotPatch

interface UsageRepository {
    suspend fun read(nowEpochMillis: Long = System.currentTimeMillis()): UsageSnapshot?
    suspend fun refresh(nowEpochMillis: Long = System.currentTimeMillis()): UsageSnapshot
}

interface UsageSource {
    suspend fun fetch(): UsageSnapshotPatch
}
