package com.uong.phormi

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.graphics.Rect
import android.util.Rational
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.max

/** Built-in Phormi player for downloaded/local media. */
@UnstableApi
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var lockButton: TextView
    private lateinit var brightnessHint: TextView
    private lateinit var volumeHint: TextView
    private lateinit var rotateButton: TextView
    private var locked = false
    private var autoRotate = true
    private var baseBrightness = 0.5f
    private val hintHandler = Handler(mainLooper)
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Let the built-in viewer follow the device orientation automatically, like a normal
        // browser/media player. The lock button below locks player controls, not orientation.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        val title = intent.getStringExtra("title").orEmpty().ifBlank { PhormiFileOpener.displayName(this, uri) }

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        val top = TextView(this).apply {
            text = "‹  $title"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(18, 18, 12, 18)
            setBackgroundColor(0x66000000)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { finish() }
            contentDescription = "Back"
        }
        root.addView(top, FrameLayout.LayoutParams(-1, 64).apply { gravity = android.view.Gravity.TOP })

        // Images use the same built-in viewer entry point but do not go through ExoPlayer.
        // This restores Phormi's native image display while keeping video/audio controls intact.
        if (mime.startsWith("image/")) {
            playerView.visibility = View.GONE
            val image = android.widget.ImageView(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setImageURI(uri)
                contentDescription = title
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 64 })
            setContentView(root)
            return
        }

        lockButton = TextView(this).apply {
            text = "🔓"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            contentDescription = "Lock player controls"
            setOnClickListener { setLocked(!locked) }
        }
        root.addView(lockButton, FrameLayout.LayoutParams(56, 56).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 8
            rightMargin = 8
        })

        rotateButton = TextView(this).apply {
            text = "↻"
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x66000000)
            contentDescription = "Auto rotate on"
            setOnClickListener { toggleAutoRotate() }
        }
        root.addView(rotateButton, FrameLayout.LayoutParams(56, 56).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 72
            rightMargin = 8
        })

        brightnessHint = makeHint()
        volumeHint = makeHint()
        root.addView(brightnessHint, FrameLayout.LayoutParams(180, 52).apply {
            gravity = android.view.Gravity.CENTER or android.view.Gravity.START
            leftMargin = 18
        })
        root.addView(volumeHint, FrameLayout.LayoutParams(180, 52).apply {
            gravity = android.view.Gravity.CENTER or android.view.Gravity.END
            rightMargin = 18
        })

        setContentView(root)
        setBrightnessFromWindow()
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureInPictureParams() }
        configurePlayer(uri, mime)
        configureGestures()
        updatePictureInPictureParams()
    }

    private fun configurePlayer(uri: Uri, mime: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setSeekBackIncrementMs(10_000)
            exo.setSeekForwardIncrementMs(10_000)
            val builder = MediaItem.Builder().setUri(uri)
            if (mime.isNotBlank()) builder.setMimeType(mime)
            exo.setMediaItem(builder.build())
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        updatePictureInPictureParams()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // The built-in player is always tried first. If it cannot decode the
                    // downloaded media, immediately hand the same URI to Android installed
                    // players instead of leaving the user at a dead player screen.
                    android.widget.Toast.makeText(
                        this@PhormiMediaViewerActivity,
                        "Phormi player could not open this media. Choosing another player…",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    val openedExternally = PhormiFileOpener.openExternal(this@PhormiMediaViewerActivity, uri, mime)
                    if (!openedExternally) finish()
                }
            })
        }
    }

    private fun configureGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) { showHint(if (e.x < root.width / 2f) brightnessHint else volumeHint, "Player locked"); return true }
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val p = player ?: return true
                if (e.x < root.width / 2f) { p.seekBack(); showHint(brightnessHint, "−10 seconds") }
                else { p.seekForward(); showHint(volumeHint, "+10 seconds") }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || e1 == null) return true
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

    private fun setLocked(value: Boolean) {
        locked = value
        lockButton.text = if (locked) "🔒" else "🔓"
        lockButton.contentDescription = if (locked) "Unlock player controls" else "Lock player controls"
        playerView.useController = !locked
        if (locked) {
            playerView.hideController()
            brightnessHint.visibility = View.GONE
            volumeHint.visibility = View.GONE
        }
    }


    private fun toggleAutoRotate() {
        autoRotate = !autoRotate
        if (autoRotate) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            rotateButton.text = "↻"
            rotateButton.contentDescription = "Auto rotate on"
        } else {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            rotateButton.text = "⛶"
            rotateButton.contentDescription = "Auto rotate off"
        }
        showHint(volumeHint, if (autoRotate) "Auto rotate on" else "Auto rotate off")
    }

    private fun makeHint() = TextView(this).apply {
        gravity = android.view.Gravity.CENTER
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0x99000000.toInt())
        visibility = View.GONE
    }

    private fun showHint(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
        hintHandler.removeCallbacksAndMessages(view)
        hintHandler.postAtTime({ view.visibility = View.GONE }, view, android.os.SystemClock.uptimeMillis() + 800L)
    }

    private fun setBrightnessFromWindow() {
        val current = window.attributes.screenBrightness
        baseBrightness = if (current in 0.02f..1f) current else 0.5f
    }

    private fun currentBrightness(): Float = window.attributes.screenBrightness.takeIf { it > 0f } ?: baseBrightness
    private fun setBrightness(value: Float) {
        baseBrightness = value
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun updatePictureInPictureParams() {
        if (android.os.Build.VERSION.SDK_INT < 26 || !::playerView.isInitialized) return
        val bounds = Rect()
        playerView.getGlobalVisibleRect(bounds)
        val video = player?.videoSize
        val width = video?.width?.takeIf { it > 0 } ?: 16
        val height = video?.height?.takeIf { it > 0 } ?: 9
        val ratio = Rational(width, height)
        runCatching {
            val builder = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setSourceRectHint(bounds)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                builder.setAutoEnterEnabled(player?.isPlaying == true)
                builder.setSeamlessResizeEnabled(true)
            }
            setPictureInPictureParams(builder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ uses setAutoEnterEnabled for a smoother gesture-to-PiP transition.
        // Keep the explicit callback for Android 8–11.
        if (android.os.Build.VERSION.SDK_INT in 26..30 && player?.isPlaying == true && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::lockButton.isInitialized) return
        lockButton.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        rotateButton.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        brightnessHint.visibility = View.GONE
        volumeHint.visibility = View.GONE
        if (isInPictureInPictureMode) {
            playerView.useController = true
            playerView.showController()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playerView.requestLayout()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
