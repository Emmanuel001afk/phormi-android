package com.uong.phormi

import android.content.Context
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.graphics.Rect
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlin.math.abs
import kotlin.math.max
import java.io.File

/** Full-screen local media player used directly by completed Downloads rows. */
@UnstableApi
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var lockButton: TextView
    private lateinit var brightnessHint: TextView
    private lateinit var volumeHint: TextView
    private lateinit var seekHint: TextView
    private lateinit var speedButton: TextView
    private lateinit var resizeButton: TextView
    private lateinit var pipButton: TextView

    private var locked = false
    private var baseBrightness = 0.5f
    private var isVideo = false
    private var cacheFallbackAttempted = false
    private var gestureStartX = 0f
    private var gestureStartPosition = 0L
    private var sleepRunnable: Runnable? = null
    private val hintHandler = Handler(mainLooper)
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("uri", Uri::class.java)
                ?: intent.getStringExtra("uri")?.let(Uri::parse)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>("uri")
                ?: intent.getStringExtra("uri")?.let(Uri::parse)
        } ?: run {
            Toast.makeText(this, "No media file was supplied to the player.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val mime = PhormiFileOpener.resolveMimeType(this, uri, intent.getStringExtra("mime"))
        val title = intent.getStringExtra("title").orEmpty()
            .ifBlank { PhormiFileOpener.displayName(this, uri) }
        isVideo = mime.startsWith("video/")

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4500
            setControllerAnimationEnabled(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            setKeepContentOnPlayerReset(true)
            setShowRewindButton(true)
            setShowFastForwardButton(true)
            setShowShuffleButton(true)
            setShowSubtitleButton(true)
            setTimeBarScrubbingEnabled(true)
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        if (mime.startsWith("image/")) {
            val image = android.widget.ImageView(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setImageURI(uri)
                contentDescription = title
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            setContentView(root)
            return
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0x66000000)
        }
        topBar.addView(button("‹", "Back") { finish() }, LinearLayout.LayoutParams(48, 56))

        topBar.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8, 0, 8, 0)
        }, LinearLayout.LayoutParams(0, 56, 1f))

        speedButton = button("1×", "Playback speed") { showSpeedMenu(it) }
        resizeButton = button("⛶", "Resize video") { toggleResize() }
        pipButton = button("▣", "Picture in picture") { enterPictureInPicture() }
        topBar.addView(speedButton, LinearLayout.LayoutParams(52, 56))
        topBar.addView(resizeButton, LinearLayout.LayoutParams(52, 56))
        if (isVideo) topBar.addView(pipButton, LinearLayout.LayoutParams(52, 56))
        topBar.addView(button("⋮", "More player options") { showMoreMenu(it, uri, mime) },
            LinearLayout.LayoutParams(48, 56))
        root.addView(topBar, FrameLayout.LayoutParams(-1, 64).apply { gravity = Gravity.TOP })

        lockButton = button("🔓", "Lock player controls") { setLocked(!locked) }
        root.addView(lockButton, FrameLayout.LayoutParams(56, 56).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 72
            rightMargin = 8
        })

        brightnessHint = makeHint()
        volumeHint = makeHint()
        seekHint = makeHint()
        root.addView(brightnessHint, FrameLayout.LayoutParams(190, 52).apply {
            gravity = Gravity.CENTER or Gravity.START
            leftMargin = 18
        })
        root.addView(volumeHint, FrameLayout.LayoutParams(190, 52).apply {
            gravity = Gravity.CENTER or Gravity.END
            rightMargin = 18
        })
        root.addView(seekHint, FrameLayout.LayoutParams(230, 52).apply { gravity = Gravity.CENTER })

        setContentView(root)
        setBrightnessFromWindow()
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
        runCatching {
            configurePlayer(uri, mime)
            configureGestures()
            updatePictureInPictureParams()
        }.onFailure { error ->
            Toast.makeText(this, "Phormi could not start the media player: ${error.message ?: "startup error"}", Toast.LENGTH_LONG).show()
            runCatching { PhormiFileOpener.openExternal(this, uri, mime) }
            finish()
        }
    }

    private fun button(text: String, description: String, action: (View) -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener(action)
        }

    private fun configurePlayer(uri: Uri, mime: String) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setSeekBackIncrementMs(10_000)
        exo.setSeekForwardIncrementMs(10_000)
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (isVideo) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        exo.setHandleAudioBecomingNoisy(true)
        val item = MediaItem.Builder().setUri(uri).apply {
            if (mime.isNotBlank() && mime != "application/octet-stream") setMimeType(mime)
        }.build()
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    updatePictureInPictureParams()
                    playerView.showController()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePictureInPictureParams()
            }
            override fun onPlayerError(error: PlaybackException) {
                retryFromAccessibleCopy(uri, mime, error)
            }
        })
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        mediaSession = runCatching { MediaSession.Builder(this, exo).build() }.getOrNull()
    }

    private fun retryFromAccessibleCopy(uri: Uri, mime: String, error: PlaybackException? = null) {
        if (cacheFallbackAttempted) {
            runOnUiThread {
                // The internal player has already had a direct-URI attempt and a readable
                // local-copy attempt. Do not strand the user on a black screen: let Android
                // choose an installed player that may support this codec/container.
                val opened = PhormiFileOpener.openExternal(this, uri, mime)
                if (!opened) {
                    val detail = error?.errorCodeName?.takeIf { it.isNotBlank() } ?: "unsupported or unreadable media"
                    Toast.makeText(this, "Phormi could not play this file ($detail).", Toast.LENGTH_LONG).show()
                }
                if (opened) finish()
            }
            return
        }
        cacheFallbackAttempted = true
        Toast.makeText(this, "Preparing the media for playback…", Toast.LENGTH_SHORT).show()
        Thread {
            val cached = runCatching {
                val name = PhormiFileOpener.displayName(this, uri, "media")
                val ext = name.substringAfterLast('.', "bin").take(12)
                val target = File.createTempFile("phormi_media_", ".$ext", cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Media URI could not be opened")
                Uri.fromFile(target)
            }.getOrNull()
            runOnUiThread {
                if (cached != null) {
                    player?.apply {
                        stop()
                        clearMediaItems()
                        setMediaItem(MediaItem.fromUri(cached))
                        prepare()
                        playWhenReady = true
                    }
                } else {
                    val opened = PhormiFileOpener.openExternal(this, uri, mime)
                    if (!opened) Toast.makeText(this, "Phormi could not read this media file.", Toast.LENGTH_LONG).show()
                    if (opened) finish()
                }
            }
        }.start()
    }

    private fun configureGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                gestureStartX = e.x
                gestureStartPosition = player?.currentPosition ?: 0L
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    Toast.makeText(this@PhormiMediaViewerActivity, "Player locked", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                player?.let {
                    if (e.x < root.width / 2f) {
                        it.seekBack()
                        showHint(brightnessHint, "−10 seconds")
                    } else {
                        it.seekForward()
                        showHint(volumeHint, "+10 seconds")
                    }
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (locked || !isVideo) return
                val p = player ?: return
                val speed = if (p.playbackParameters.speed == 1f) 1.5f else 1f
                p.setPlaybackSpeed(speed)
                speedButton.text = "${speed}×"
                showHint(seekHint, if (speed == 1f) "Normal speed" else "1.5× quick play")
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || e1 == null) return true
                val dx = e2.x - gestureStartX
                if (isVideo && abs(dx) > abs(distanceY) && abs(dx) > 20f) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        val fraction = (dx / max(1f, root.width.toFloat())) * 0.75f
                        val target = (gestureStartPosition + (duration.toDouble() * fraction).toLong()).coerceIn(0L, duration)
                        player?.seekTo(target)
                        val delta = (target - gestureStartPosition) / 1000L
                        showHint(seekHint, if (delta >= 0) "Seek +${delta}s" else "Seek −${-delta}s")
                    }
                    return true
                }

                val delta = -distanceY / max(1f, root.height.toFloat())
                if (e1.x < root.width / 2f) {
                    setBrightness((baseBrightness + delta).coerceIn(0.02f, 1f))
                    showHint(brightnessHint, "Brightness ${(currentBrightness() * 100).toInt()}%")
                } else {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val next = (current + (delta * maxVolume * 1.8f).toInt()).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    showHint(volumeHint, "Volume ${((next * 100f) / maxVolume.coerceAtLeast(1)).toInt()}%")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
    }

    private fun showSpeedMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        speeds.forEachIndexed { index, speed -> popup.menu.add(Menu.NONE, index, index, "${speed}×") }
        popup.setOnMenuItemClickListener {
            val speed = speeds[it.itemId]
            player?.setPlaybackSpeed(speed)
            speedButton.text = "${speed}×"
            showHint(seekHint, "Playback ${speed}×")
            true
        }
        popup.show()
    }

    private fun toggleResize() {
        if (!isVideo) return
        val zoomed = playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        playerView.setResizeMode(
            if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_FIT
            else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        resizeButton.text = if (zoomed) "⛶" else "↔"
        showHint(seekHint, if (zoomed) "Fit video" else "Fill video")
        updatePictureInPictureParams()
    }

    private fun showMoreMenu(anchor: View, uri: Uri, mime: String) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Audio track")
        popup.menu.add("Subtitle track")
        popup.menu.add("Sleep timer")
        popup.menu.add("Jump to time")
        popup.menu.add("Repeat")
        popup.menu.add("Shuffle")
        popup.menu.add("Open with another app")
        popup.menu.add("Share file")
        popup.menu.add("Player information")
        popup.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Audio track" -> showTrackSelection(androidx.media3.common.C.TRACK_TYPE_AUDIO, "Audio tracks")
                "Subtitle track" -> showTrackSelection(androidx.media3.common.C.TRACK_TYPE_TEXT, "Subtitle tracks")
                "Sleep timer" -> showSleepTimer(anchor)
                "Jump to time" -> showJumpToTime()
                "Repeat" -> showRepeatMenu(anchor)
                "Shuffle" -> {
                    player?.let { p ->
                        p.shuffleModeEnabled = !p.shuffleModeEnabled
                        Toast.makeText(this, if (p.shuffleModeEnabled) "Shuffle on" else "Shuffle off", Toast.LENGTH_SHORT).show()
                    }
                }
                "Open with another app" -> PhormiFileOpener.openExternal(this, uri, mime)
                "Share file" -> {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime.ifBlank { "*/*" }
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(send, "Share media"))
                    true
                }
                else -> {
                    Toast.makeText(
                        this,
                        "${PhormiFileOpener.displayName(this, uri)} · $mime",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
            }
        }
        popup.show()
    }

    private fun showTrackSelection(trackType: Int, title: String) {
        val p = player ?: return
        runCatching {
            val hasTracks = p.currentTracks.groups.any { it.type == trackType }
            if (!hasTracks) {
                Toast.makeText(this, "No ${title.lowercase()} are available for this file.", Toast.LENGTH_SHORT).show()
                return
            }
            TrackSelectionDialogBuilder(this, title, p, trackType).build().show()
        }.onFailure {
            Toast.makeText(this, "Track selection is unavailable for this file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSleepTimer(anchor: View) {
        val popup = PopupMenu(this, anchor)
        listOf("Off", "15 minutes", "30 minutes", "60 minutes").forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener {
            sleepRunnable?.let { task -> hintHandler.removeCallbacks(task) }
            sleepRunnable = null
            when (it.title.toString()) {
                "15 minutes" -> scheduleSleep(15)
                "30 minutes" -> scheduleSleep(30)
                "60 minutes" -> scheduleSleep(60)
                else -> Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    private fun scheduleSleep(minutes: Int) {
        val task = Runnable {
            player?.pause()
            Toast.makeText(this, "Sleep timer ended", Toast.LENGTH_SHORT).show()
        }
        sleepRunnable = task
        hintHandler.postDelayed(task, minutes * 60_000L)
        Toast.makeText(this, "Sleep timer: $minutes minutes", Toast.LENGTH_SHORT).show()
    }

    private fun showJumpToTime() {
        val input = EditText(this).apply {
            hint = "Seconds or HH:MM:SS"
            inputType = InputType.TYPE_CLASS_DATETIME
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Jump to time")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go") { _, _ ->
                val value = input.text.toString().trim()
                val seconds = parseTimeSeconds(value)
                if (seconds == null) {
                    Toast.makeText(this, "Use seconds or HH:MM:SS.", Toast.LENGTH_SHORT).show()
                } else {
                    player?.seekTo(seconds * 1000L)
                }
            }
            .show()
    }

    private fun parseTimeSeconds(value: String): Long? {
        if (value.isBlank()) return null
        if (value.all { it.isDigit() }) return value.toLongOrNull()?.coerceAtLeast(0L)
        val parts = value.split(":")
        if (parts.size !in 2..3 || parts.any { !it.all(Char::isDigit) }) return null
        return runCatching {
            when (parts.size) {
                2 -> parts[0].toLong() * 60L + parts[1].toLong()
                else -> parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong()
            }
        }.getOrNull()?.coerceAtLeast(0L)
    }

    private fun showRepeatMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Off")
        popup.menu.add("Repeat one")
        popup.menu.add("Repeat all")
        popup.setOnMenuItemClickListener {
            player?.repeatMode = when (it.title.toString()) {
                "Repeat one" -> androidx.media3.common.Player.REPEAT_MODE_ONE
                "Repeat all" -> androidx.media3.common.Player.REPEAT_MODE_ALL
                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            Toast.makeText(this, "Repeat: ${it.title}", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockButton.text = if (locked) "🔒" else "🔓"
        lockButton.contentDescription = if (locked) "Unlock player controls" else "Lock player controls"
        playerView.useController = !locked
        topBar.visibility = if (locked) View.GONE else View.VISIBLE
        if (locked) {
            playerView.hideController()
            brightnessHint.visibility = View.GONE
            volumeHint.visibility = View.GONE
            seekHint.visibility = View.GONE
        }
    }

    private fun setBrightnessFromWindow() {
        val current = window.attributes.screenBrightness
        baseBrightness = if (current in 0.02f..1f) current else runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.02f, 1f)
    }
    private fun currentBrightness(): Float = window.attributes.screenBrightness.takeIf { it > 0f } ?: baseBrightness
    private fun setBrightness(value: Float) {
        baseBrightness = value
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun makeHint() = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0x99000000.toInt())
        visibility = View.GONE
    }

    private fun showHint(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
        hintHandler.removeCallbacksAndMessages(view)
        hintHandler.postAtTime({ view.visibility = View.GONE }, view,
            android.os.SystemClock.uptimeMillis() + 900L)
    }

    private fun enterPictureInPicture() {
        if (!isVideo || Build.VERSION.SDK_INT < 26 ||
            !packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            Toast.makeText(this, "Picture-in-picture is not supported here.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { enterPictureInPictureMode(buildPictureInPictureParams(false)) }
            .onFailure {
                Toast.makeText(this, "Picture-in-picture could not be started.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < 26 || !::playerView.isInitialized || !isVideo) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams(player?.isPlaying == true)) }
    }

    private fun buildPictureInPictureParams(autoEnter: Boolean): android.app.PictureInPictureParams {
        val bounds = Rect()
        playerView.getGlobalVisibleRect(bounds)
        val video = player?.videoSize
        val width = video?.width?.takeIf { it > 0 } ?: 16
        val height = video?.height?.takeIf { it > 0 } ?: 9
        val safe = (width.toDouble() / height.toDouble()).coerceIn(1.0 / 2.39, 2.39)
        val numerator = (safe * 1000).toInt().coerceAtLeast(1)
        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(Rational(numerator, 1000))
            .setSourceRectHint(bounds)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && isVideo && player?.isPlaying == true && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(buildPictureInPictureParams(false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::lockButton.isInitialized) return
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        lockButton.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        brightnessHint.visibility = View.GONE
        volumeHint.visibility = View.GONE
        seekHint.visibility = View.GONE
        playerView.useController = if (isInPictureInPictureMode) true else !locked
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::playerView.isInitialized) {
            playerView.requestLayout()
            updatePictureInPictureParams()
        }
    }

    override fun onDestroy() {
        sleepRunnable?.let { hintHandler.removeCallbacks(it) }
        sleepRunnable = null
        mediaSession?.release()
        mediaSession = null
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
