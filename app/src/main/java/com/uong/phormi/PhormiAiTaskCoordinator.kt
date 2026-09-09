package com.uong.phormi

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates browser-AI work as durable lanes.
 *
 * A lane represents one browser task/tab target. Its checkpoint is persisted so
 * a provider failure does not erase the task's progress. Providers remain a
 * fallback chain inside AiController; this class is responsible for keeping
 * independent task memory separate and for coordinating multiple requested lanes.
 *
 * UI interaction is deliberately serialized through AiController's browser
 * surface because Android Accessibility exposes one foreground interaction
 * surface at a time. Multiple lanes may reason concurrently, but their physical
 * screen actions are not allowed to race each other.
 */
class PhormiAiTaskCoordinator(context: Context) {
    data class Lane(
        val id: String,
        val target: String,
        val instruction: String,
        var status: String = "queued",
        var lastCheckpoint: String = "",
        var updatedAt: Long = System.currentTimeMillis()
    )

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("phormi_ai_task_memory", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()
    private val controller by lazy { AiController(appContext) }

    fun createLane(target: String, instruction: String, id: String = UUID.randomUUID().toString()): Lane {
        val lane = Lane(id, target.trim(), instruction.trim())
        persist(lane)
        return lane
    }

    fun listLanes(): List<Lane> = readAll().sortedByDescending { it.updatedAt }

    fun getLane(id: String): Lane? = readAll().firstOrNull { it.id == id }

    fun cancelLane(id: String) {
        running.remove(id)?.cancel()
        getLane(id)?.let { persist(it.copy(status = "cancelled", updatedAt = System.currentTimeMillis())) }
    }

    /** Run one lane. The controller itself tries every configured provider in order. */
    fun runLane(laneId: String, onStatus: (String) -> Unit = {}): Job? {
        if (running.containsKey(laneId)) return running[laneId]
        val lane = getLane(laneId) ?: return null
        val job = scope.launch {
            val current = getLane(laneId) ?: return@launch
            persist(current.copy(status = "running", updatedAt = System.currentTimeMillis()))
            try {
                controller.runTask(
                    instruction = buildInstruction(current),
                    onStatus = { status ->
                        val checkpoint = status.take(500)
                        val latest = getLane(laneId) ?: return@runTask
                        persist(latest.copy(lastCheckpoint = checkpoint, status = status.take(120), updatedAt = System.currentTimeMillis()))
                        onStatus("[${latest.target}] $status")
                    }
                )
                val finished = getLane(laneId)
                if (finished != null) persist(finished.copy(status = "finished", updatedAt = System.currentTimeMillis()))
            } catch (t: Throwable) {
                val failed = getLane(laneId)
                if (failed != null) persist(failed.copy(status = "failed: ${(t.message ?: "error").take(180)}", updatedAt = System.currentTimeMillis()))
                onStatus("[${current.target}] failed: ${t.message ?: "error"}")
            } finally {
                running.remove(laneId)
            }
        }
        running[laneId] = job
        return job
    }

    /**
     * Start several lanes together. Their reasoning jobs can overlap, while
     * actual browser interaction remains protected by the single foreground
     * Accessibility surface used by AiController.
     */
    fun runLanes(laneIds: List<String>, onStatus: (String) -> Unit = {}): Job = scope.launch {
        coroutineScope {
            laneIds.distinct().mapNotNull { id ->
                runLane(id, onStatus)?.let { async { it.join() } }
            }.awaitAll()
        }
    }

    private fun buildInstruction(lane: Lane): String {
        val memory = lane.lastCheckpoint.takeIf { it.isNotBlank() }
        return buildString {
            append("Target browser tab/site: ").append(lane.target.ifBlank { "current tab" }).append(".\n")
            append("Task: ").append(lane.instruction).append("\n")
            if (memory != null) {
                append("This task is being resumed. Last durable checkpoint: ").append(memory).append("\n")
                append("Do not restart work that is already complete; inspect the current screen and continue from the checkpoint.\n")
            }
            append("Never request, reveal, copy, or transmit passwords, PINs, OTPs, CVVs, recovery codes, or other sensitive authentication secrets.")
        }
    }

    private fun persist(lane: Lane) {
        val all = readAll().filterNot { it.id == lane.id }.toMutableList()
        all += lane
        val json = JSONArray()
        all.takeLast(100).forEach {
            json.put(JSONObject()
                .put("id", it.id)
                .put("target", it.target)
                .put("instruction", it.instruction)
                .put("status", it.status)
                .put("checkpoint", it.lastCheckpoint)
                .put("updatedAt", it.updatedAt))
        }
        prefs.edit().putString("lanes", json.toString()).apply()
    }

    private fun readAll(): List<Lane> {
        val raw = prefs.getString("lanes", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Lane(
                        id = o.optString("id"),
                        target = o.optString("target"),
                        instruction = o.optString("instruction"),
                        status = o.optString("status", "queued"),
                        lastCheckpoint = o.optString("checkpoint"),
                        updatedAt = o.optLong("updatedAt", 0L)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun Lane.copy(
        status: String = this.status,
        lastCheckpoint: String = this.lastCheckpoint,
        updatedAt: Long = this.updatedAt
    ) = Lane(id, target, instruction, status, lastCheckpoint, updatedAt)
}
