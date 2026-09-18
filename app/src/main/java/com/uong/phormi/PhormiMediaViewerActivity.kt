package com.uong.phormi

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
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
@OptIn(UnstableApi::class)
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var lockButton: TextView
    private lateinit var brightnessHint: TextView
    private lateinit var volumeHint: TextView
    private var locked = false
    private var landscape = false
    private var baseBrightness = 0.5f
    private val hintHandler = Handler(mainLooper)
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        val title = intent.getStringExtra("title").orEmpty().ifBlank { PhormiFileOpener.displayName(this, uri) }

        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4500
            showBuffering = PlayerView.SHOW_BUFFERING_WHEN_PLAYING
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
        configurePlayer(uri, mime)
        configureGestures()
    }

    private fun configurePlayer(uri: Uri, mime: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setSeekBackIncrement(10_000)
            exo.setSeekForwardIncrement(10_000)
            val builder = MediaItem.Builder().setUri(uri)
            if (mime.isNotBlank()) builder.setMimeType(mime)
            exo.setMediaItem(builder.build())
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.widget.Toast.makeText(this@PhormiMediaViewerActivity,
                        "Phormi could not play this file: ${error.errorCodeName}",
                        android.widget.Toast.LENGTH_LONG).show()
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
        if (locked) { playerView.hideController(); brightnessHint.visibility = View.GONE; volumeHint.visibility = View.GONE }
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

    override fun onBackPressed() {
        if (landscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            landscape = false
            return
        }
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
