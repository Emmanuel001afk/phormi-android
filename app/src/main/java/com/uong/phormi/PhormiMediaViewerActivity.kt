package com.uong.phormi

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Lightweight viewer for common downloaded image, video and audio files. */
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var video: VideoView? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080C14.toInt()) }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(18, 10, 18, 10)
            contentDescription = "Close media viewer"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(70, 70))

        when {
            mime.startsWith("image/") -> root.addView(ImageView(this).apply {
                setImageURI(uri)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Downloaded image"
            }, FrameLayout.LayoutParams(-1, -1))
            mime.startsWith("video/") -> {
                isVideo = true
                val playerView = VideoView(this)
                video = playerView
                playerView.setVideoURI(uri)
                playerView.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    playerView.start()
                    enterImmersive()
                }
                playerView.setOnCompletionListener { showChrome() }
                root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
            }
            mime.startsWith("audio/") -> {
                val label = TextView(this).apply {
                    text = "Audio playback"
                    textSize = 20f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = android.view.Gravity.CENTER
                    contentDescription = "Audio playback"
                    setOnClickListener { startAudio(uri) }
                }
                root.addView(label, FrameLayout.LayoutParams(-1, -1))
                startAudio(uri)
            }
            else -> { finish(); return }
        }
        setContentView(root)
    }

    private fun startAudio(uri: Uri) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(this@PhormiMediaViewerActivity, uri)
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun showChrome() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && isVideo && video?.isPlaying == true && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && isVideo) showChrome()
    }

    override fun onDestroy() {
        video?.stopPlayback()
        video = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
