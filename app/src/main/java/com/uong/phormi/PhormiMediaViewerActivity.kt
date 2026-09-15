package com.uong.phormi

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

/** Viewer for downloaded media with playback, volume, brightness and rotation controls. */
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var video: VideoView? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var brightness = 0.75f
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalOrientation = requestedOrientation
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        brightness = window.attributes.screenBrightness.takeIf { it > 0f } ?: 0.75f

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 12, 20))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        toolbar.addView(control("‹", "Close viewer") { finish() }, LinearLayout.LayoutParams(52, 52))
        toolbar.addView(TextView(this).apply {
            text = when {
                mime.startsWith("video/") -> "Video"
                mime.startsWith("audio/") -> "Audio"
                mime.startsWith("image/") -> "Image"
                else -> "Media"
            }
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, 52, 1f))
        toolbar.addView(control("↻", "Auto-rotate") { toggleRotation() }, LinearLayout.LayoutParams(52, 52))
        toolbar.addView(control("☀−", "Dim screen") { adjustBrightness(-0.1f) }, LinearLayout.LayoutParams(52, 52))
        toolbar.addView(control("☀+", "Brighten screen") { adjustBrightness(0.1f) }, LinearLayout.LayoutParams(52, 52))
        toolbar.addView(control("−", "Lower volume") { adjustVolume(-1) }, LinearLayout.LayoutParams(52, 52))
        toolbar.addView(control("+", "Raise volume") { adjustVolume(1) }, LinearLayout.LayoutParams(52, 52))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, 64))

        val content = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        when {
            mime.startsWith("image/") -> {
                val image = ImageView(this).apply {
                    setImageURI(uri)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Downloaded image"
                }
                content.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            mime.startsWith("video/") -> {
                val playerView = VideoView(this)
                video = playerView
                playerView.setVideoURI(uri)
                playerView.setMediaController(MediaController(this))
                playerView.setOnPreparedListener { media ->
                    media.isLooping = false
                    playerView.start()
                }
                playerView.setOnErrorListener { _, _, _ ->
                    ToastMessage.show(this, "Video could not be played")
                    true
                }
                content.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, -1).apply {
                    gravity = Gravity.CENTER
                })
            }
            mime.startsWith("audio/") -> {
                val label = TextView(this).apply {
                    text = "Audio playback\n\nUse − / + above for volume"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setOnClickListener { startAudio(uri) }
                }
                content.addView(label, android.widget.FrameLayout.LayoutParams(-1, -1))
                startAudio(uri)
            }
            else -> finish()
        }
    }

    private fun control(text: String, description: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = description
        background = GradientDrawable().apply {
            setColor(Color.rgb(30, 41, 59))
            cornerRadius = 12f
        }
        setOnClickListener { action() }
        setPadding(2, 2, 2, 2)
    }

    private fun toggleRotation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        ToastMessage.show(this, if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR) "Auto-rotate on" else "Auto-rotate off")
    }

    private fun adjustBrightness(delta: Float) {
        brightness = (brightness + delta).coerceIn(0.1f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    private fun adjustVolume(delta: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
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

    private fun startAudio(uri: Uri) {
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
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
        releasePlayer()
        video?.stopPlayback()
        video = null
        requestedOrientation = originalOrientation
        super.onDestroy()
    }

    private object ToastMessage {
        fun show(activity: AppCompatActivity, message: String) {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
