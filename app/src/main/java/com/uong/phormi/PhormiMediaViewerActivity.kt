package com.uong.phormi

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Media viewer with automatic rotation plus gesture brightness and volume controls. */
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var video: VideoView? = null
    private var autoRotate = true
    private var originalOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var originalBrightness = -1f
    private val indicatorHandler = Handler(Looper.getMainLooper())
    private var indicatorRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalOrientation = requestedOrientation
        originalBrightness = window.attributes.screenBrightness
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080C14.toInt()) }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(18, 10, 18, 10)
            contentDescription = "Close viewer"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(70.dp(), 70.dp()).apply { gravity = Gravity.TOP or Gravity.START })

        val auto = TextView(this).apply {
            text = "↻ AUTO"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0x66000000)
            contentDescription = "Toggle automatic rotation"
            setOnClickListener {
                autoRotate = !autoRotate
                requestedOrientation = if (autoRotate) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                text = if (autoRotate) "↻ AUTO" else "↻ LOCK"
            }
        }
        root.addView(auto, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 54.dp()).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 8.dp(); rightMargin = 8.dp() })

        when {
            mime.startsWith("image/") -> {
                val image = ImageView(this).apply {
                    setImageURI(uri)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Downloaded image"
                }
                root.addView(image, FrameLayout.LayoutParams(-1, -1))
            }
            mime.startsWith("video/") -> setupVideo(root, uri)
            mime.startsWith("audio/") -> {
                val label = TextView(this).apply {
                    text = "Audio playback"
                    textSize = 20f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    setOnClickListener { startAudio(uri) }
                }
                root.addView(label, FrameLayout.LayoutParams(-1, -1))
                startAudio(uri)
            }
            else -> finish()
        }
        setContentView(root)
    }

    private fun setupVideo(root: FrameLayout, uri: Uri) {
        video = VideoView(this).apply {
            setVideoURI(uri)
            setMediaController(MediaController(this@PhormiMediaViewerActivity))
            setOnPreparedListener { media ->
                media.isLooping = false
                enterVideoMode()
                start()
            }
            setOnErrorListener { _, _, _ ->
                ToastMessage.show(this@PhormiMediaViewerActivity, "Video could not be played")
                true
            }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })

        val brightnessZone = sideGestureZone(true)
        val volumeZone = sideGestureZone(false)
        root.addView(brightnessZone, FrameLayout.LayoutParams(0, -1).apply {
            gravity = Gravity.START
            width = (resources.displayMetrics.widthPixels * 0.30f).toInt()
            topMargin = 70.dp()
        })
        root.addView(volumeZone, FrameLayout.LayoutParams(0, -1).apply {
            gravity = Gravity.END
            width = (resources.displayMetrics.widthPixels * 0.30f).toInt()
            topMargin = 70.dp()
        })

        val hint = TextView(this).apply {
            text = "Left swipe: brightness    Right swipe: volume"
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(18, 8, 18, 8)
            setBackgroundColor(0x66000000)
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 42.dp()).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 12.dp()
        })
        hint.postDelayed({ hint.visibility = View.GONE }, 3000L)
    }

    private fun sideGestureZone(brightness: Boolean): View = object : View(this) {
        private var startY = 0f
        private var lastY = 0f
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    if (kotlin.math.abs(dy) > 1f) {
                        if (brightness) adjustBrightness(-dy / height.coerceAtLeast(1).toFloat())
                        else adjustVolume(-dy / height.coerceAtLeast(1).toFloat())
                    }
                    lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
    }

    private fun enterVideoMode() {
        if (autoRotate) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun adjustBrightness(delta: Float) {
        val current = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
        val next = (current + delta).coerceIn(0.02f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = next }
        showIndicator("☀ ${((next * 100).toInt())}%")
    }

    private fun adjustVolume(delta: Float) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + delta * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        showIndicator("🔊 ${((next * 100f) / max).toInt()}%")
    }

    private fun showIndicator(text: String) {
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val existing = root.findViewWithTag<TextView>("phormi_media_indicator")
        val indicator = existing ?: TextView(this).apply {
            tag = "phormi_media_indicator"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(28, 18, 28, 18)
            setBackgroundColor(0xCC111827.toInt())
            root.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
        indicator.text = text
        indicator.visibility = View.VISIBLE
        indicatorRunnable?.let(indicatorHandler::removeCallbacks)
        val hide = Runnable { indicator.visibility = View.GONE }
        indicatorRunnable = hide
        indicatorHandler.postDelayed(hide, 850L)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val v = video
        if (Build.VERSION.SDK_INT >= 26 && v?.isPlaying == true && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        video?.visibility = View.VISIBLE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        video?.requestLayout()
    }

    private fun startAudio(uri: Uri) {
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@PhormiMediaViewerActivity, uri)
                setOnCompletionListener { releasePlayer() }
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    ToastMessage.show(this@PhormiMediaViewerActivity, "Audio could not be played")
                    true
                }
                prepareAsync()
            }
        }.getOrElse {
            ToastMessage.show(this, "Audio could not be played")
            null
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) video?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        indicatorRunnable?.let(indicatorHandler::removeCallbacks)
        runCatching { requestedOrientation = originalOrientation }
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        releasePlayer()
        video?.stopPlayback()
        video = null
        super.onDestroy()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private object ToastMessage {
        fun show(activity: AppCompatActivity, message: String) {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
