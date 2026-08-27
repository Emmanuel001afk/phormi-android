package com.uong.phormi

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Phormi wrapper: a native Android shell around the EMA web app
 * (https://phormi.lovable.app). This activity itself contains no
 * browser-automation engine — it only displays the site and grants
 * it the device permissions (camera/mic) it may ask for. All AI /
 * Playwright / scheduling logic lives on the website side.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val HOME_URL = "https://phormi.lovable.app"
        private const val REQ_MEDIA_PERMISSIONS = 1001
        private const val REQ_FILE_CHOOSER = 1002
        private const val REQ_STARTUP_PERMISSIONS = 1003

        // Everything this private app is allowed to ask for up front,
        // so the site never hits a silent wall later mid-task.
        private val STARTUP_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
    }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: android.webkit.GeolocationPermissions.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the screen on while the app is in the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        configureWebView()
        requestStartupPermissions()

        swipeRefresh.setOnRefreshListener { webView.reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    private fun requestStartupPermissions() {
        val notYetGranted = STARTUP_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notYetGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, notYetGranted.toTypedArray(), REQ_STARTUP_PERMISSIONS
            )
        }
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setGeolocationEnabled(true)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString + " PhormiApp/1.0"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Grant camera/mic access the site asks for (needed for
            // any future voice-mode / getUserMedia features).
            override fun onPermissionRequest(request: PermissionRequest) {
                pendingPermissionRequest = request
                val needed = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) needed.add(Manifest.permission.CAMERA)
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) needed.add(Manifest.permission.RECORD_AUDIO)

                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, needed.toTypedArray(), REQ_MEDIA_PERMISSIONS
                    )
                }
            }

            // Site asking for the device's location (e.g. local search/results).
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_MEDIA_PERMISSIONS
                    )
                }
            }

            // Lets the site's file input (e.g. uploading the .gguf model)
            // open the phone's normal file picker.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                return try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // Let normal downloads (e.g. exported files) hand off to the
        // system Download Manager instead of failing silently.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA_PERMISSIONS) {
            val request = pendingPermissionRequest ?: return
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) request.grant(request.resources) else request.deny()
            pendingPermissionRequest = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
