package com.uong.phormi

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Lightweight viewer for common downloaded image, video and audio files. */
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    private var video: VideoView? = null

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
            contentDescription = "Close viewer"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(70, 70))

        when {
            mime.startsWith("image/") -> {
                val image = ImageView(this).apply {
                    setImageURI(uri)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Downloaded image"
                }
                root.addView(image, FrameLayout.LayoutParams(-1, -1))
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
                root.addView(playerView, FrameLayout.LayoutParams(-1, -1).apply {
                    gravity = Gravity.CENTER
                })
                // A normal tap controls playback; PiP is entered when the user leaves
                // the app, matching Android's expected media/PiP interaction.
            }
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
        super.onDestroy()
    }

    private object ToastMessage {
        fun show(activity: AppCompatActivity, message: String) {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
