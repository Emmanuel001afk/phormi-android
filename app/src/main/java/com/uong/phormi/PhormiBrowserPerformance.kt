package com.uong.phormi

import android.os.Debug
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Lightweight navigation telemetry with throttled memory sampling. */
object PhormiBrowserPerformance {
    data class NavigationSample(
        val startedAt: Long,
        val durationMs: Long,
        val url: String,
        val pssKb: Int,
        val recordedAt: Long
    )

    data class ProcessSnapshot(
        val pssKb: Int,
        val privateDirtyKb: Int,
        val recordedAt: Long
    )

    private const val MEMORY_SAMPLE_INTERVAL_MS = 5000L
    private val starts = ConcurrentHashMap<Int, Long>()
    private val lastSamples = ConcurrentHashMap<Int, NavigationSample>()
    @Volatile private var cachedMemory: ProcessSnapshot? = null

    fun start(tabId: Int) {
        starts[tabId] = SystemClock.elapsedRealtime()
    }

    fun finish(tabId: Int, url: String?) {
        val start = starts.remove(tabId) ?: return
        val now = SystemClock.elapsedRealtime()
        val memory = memorySnapshot(now)
        lastSamples[tabId] = NavigationSample(
            startedAt = start,
            durationMs = (now - start).coerceAtLeast(0L),
            url = url.orEmpty(),
            pssKb = memory.pssKb,
            recordedAt = memory.recordedAt
        )
    }

    fun latest(tabId: Int): NavigationSample? = lastSamples[tabId]

    fun memorySnapshot(now: Long = SystemClock.elapsedRealtime()): ProcessSnapshot {
        val old = cachedMemory
        if (old != null && now - old.recordedAt < MEMORY_SAMPLE_INTERVAL_MS) return old
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return ProcessSnapshot(
            pssKb = info.totalPss.coerceAtLeast(0),
            privateDirtyKb = info.totalPrivateDirty.coerceAtLeast(0),
            recordedAt = now
        ).also { cachedMemory = it }
    }

    fun clear(tabId: Int) {
        starts.remove(tabId)
        lastSamples.remove(tabId)
    }

    fun clearAll() {
        starts.clear()
        lastSamples.clear()
        cachedMemory = null
    }

    fun summary(tabId: Int): String {
        val sample = latest(tabId) ?: return "No completed navigation recorded yet."
        return String.format(
            Locale.US,
            "Last navigation: %d ms\nProcess PSS: %.1f MB\n%s",
            sample.durationMs,
            sample.pssKb / 1024.0,
            sample.url.ifBlank { "about:blank" }
        )
    }
}
