package com.uong.phormi

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Same-process editors and IMEs share the UI looper. Synchronous remote text queries
 * can wait for that very looper and time out/ANR. Keep reads off it and serve a small,
 * revision-checked local context; writes still go to the real editor in key order.
 */
internal class PhormiEditorConnection(
    val source: InputConnection,
    initialStart: Int,
    initialEnd: Int,
    private val changed: () -> Unit
) : InputConnectionWrapper(source, false) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "PhormiEditorContext").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<Snapshot?>? = null
    internal data class Snapshot(val left: String, val selection: String, val right: String)
    @Volatile private var revision = 0L
    @Volatile private var closed = false
    private var start = minOf(initialStart, initialEnd).coerceAtLeast(0)
    private var end = maxOf(initialStart, initialEnd).coerceAtLeast(start)
    private var before = ""
    private var selected = ""
    private var after = ""
    private var fresh = initialStart == 0 && initialEnd == 0

    init { refresh(0) }
    fun dispose() { closed = true; task?.cancel(true); worker.shutdownNow() }

    /** Reads never wait on the UI looper; stale snapshots cannot replace newer keys. */
    fun refresh(delayMs: Long = 35): java.util.concurrent.Future<Snapshot?>? {
        if (closed) return null
        task?.cancel(false)
        val expected = revision
        task = worker.schedule(java.util.concurrent.Callable {
            try {
                val left = source.getTextBeforeCursor(2048, 0)?.toString() ?: return@Callable null
                val selection = source.getSelectedText(0)?.toString().orEmpty().take(4096)
                val right = source.getTextAfterCursor(1024, 0)?.toString().orEmpty()
                val snapshot = Snapshot(left, selection, right)
                main.post { apply(snapshot, expected) }
                snapshot
            } catch (_: Exception) { null }
        }, delayMs, TimeUnit.MILLISECONDS)
        return task
    }
    private fun apply(snapshot: Snapshot, expected: Long) {
        if (!closed && revision == expected) {
            before = snapshot.left; selected = snapshot.selection; after = snapshot.right; fresh = true
            changed()
        }
    }
    /** Capture/validate an AI edit against fresh editor text, not an optimistic key cache. */
    suspend fun synchronize(): Boolean {
        if (closed) return false
        fresh = false; val expected = ++revision
        val future = refresh(0) ?: return false
        val snapshot = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get(3, TimeUnit.SECONDS) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { return false }
        if (snapshot == null || closed || revision != expected) return false
        apply(snapshot, expected); return true
    }
    fun selectionChanged(newStart: Int, newEnd: Int) {
        if (newStart != start || newEnd != end) {
            start = minOf(newStart, newEnd).coerceAtLeast(0); end = maxOf(newStart, newEnd).coerceAtLeast(start)
            revision++; fresh = false
            refresh()
        }
    }
    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = if (fresh) before.takeLast(length.coerceAtLeast(0)) else ""
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = if (fresh) after.take(length.coerceAtLeast(0)) else ""
    override fun getSelectedText(flags: Int): CharSequence? = if (fresh) selected.takeIf { it.isNotEmpty() } else null
    override fun getCursorCapsMode(reqModes: Int): Int = if (fresh) TextUtils.getCapsMode(before, before.length, reqModes) else 0
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        revision++
        if (fresh && newCursorPosition == 1) {
            before = (before + value).takeLast(2048); selected = ""
        } else fresh = false
        start += value.length; end = start
        val result = source.commitText(text ?: "", newCursorPosition)
        refresh(); return result
    }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        revision++
        if (fresh && beforeLength <= before.length && afterLength <= after.length) {
            before = before.dropLast(beforeLength.coerceAtLeast(0)); after = after.drop(afterLength.coerceAtLeast(0))
        } else fresh = false
        start = (start - beforeLength.coerceAtLeast(0)).coerceAtLeast(0); end = start
        val result = source.deleteSurroundingText(beforeLength, afterLength)
        refresh(); return result
    }
    override fun setSelection(start: Int, end: Int): Boolean {
        revision++; fresh = false; this.start = start.coerceAtLeast(0); this.end = end.coerceAtLeast(0)
        val result = source.setSelection(start, end); refresh(); return result
    }
    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        revision++; fresh = false
        val result = if (event != null) source.sendKeyEvent(event) else false
        refresh(); return result
    }
}
