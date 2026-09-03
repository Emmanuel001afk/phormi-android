package com.uong.phormi

import android.os.Debug
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Lightweight browser navigation/performance telemetry kept in-process only. */
object PhormiBrowserPerformance {
    data class NavigationSample(
        val startedAt: Long,
        val durationMs: Long,
        val url: String,
        val pssKb: Int
    )

    private val starts = ConcurrentHashMap<Int, Long>()
    private val lastSamples = ConcurrentHashMap<Int, NavigationSample>()

    fun start(tabId: Int) {
        starts[tabId] = SystemClock.elapsedRealtime()
    }

    fun finish(tabId: Int, url: String?) {
        val start = starts.remove(tabId) ?: return
        val duration = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        lastSamples[tabId] = NavigationSample(
            startedAt = start,
            durationMs = duration,
            url = url.orEmpty(),
            pssKb = info.totalPss.coerceAtLeast(0)
        )
    }

    fun latest(tabId: Int): NavigationSample? = lastSamples[tabId]

    fun clear(tabId: Int) {
        starts.remove(tabId)
        lastSamples.remove(tabId)
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
