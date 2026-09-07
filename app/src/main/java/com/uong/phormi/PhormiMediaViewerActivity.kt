package com.uong.phormi

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Lightweight in-browser viewer for common downloaded image, video and audio files. */
class PhormiMediaViewerActivity : AppCompatActivity() {
    private var player: MediaPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri") ?: run { finish(); return }
        val mime = intent.getStringExtra("mime").orEmpty()
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(0xFF080C14.toInt())
        val back = TextView(this).apply { text = "‹"; textSize = 32f; setTextColor(0xFFFFFFFF.toInt()); setPadding(18, 10, 18, 10); setOnClickListener { finish() } }
        root.addView(back, android.widget.FrameLayout.LayoutParams(70, 70))
        when {
            mime.startsWith("image/") -> root.addView(ImageView(this).apply { setImageURI(uri); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Downloaded image" }, android.widget.FrameLayout.LayoutParams(-1, -1))
            mime.startsWith("video/") -> {
                val video = VideoView(this)
                video.setVideoURI(uri); video.setOnPreparedListener { it.isLooping = false; video.start() }
                root.addView(video, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            mime.startsWith("audio/") -> {
                val label = TextView(this).apply { text = "Audio playback"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER; setOnClickListener { startAudio(uri) } }
                root.addView(label, android.widget.FrameLayout.LayoutParams(-1, -1))
                startAudio(uri)
            }
            else -> finish()
        }
        setContentView(root)
    }
    private fun startAudio(uri: Uri) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(this@PhormiMediaViewerActivity, uri); prepare(); start()
        }
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
