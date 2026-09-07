package com.uong.phormi

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/** Separate private browsing task. Ghost tabs/counter/storage never enter normal MainActivity tabs/history. */
class GhostActivity : AppCompatActivity() {
    private data class GhostTab(val id: Int, val webView: WebView, val chip: TextView)
    private val tabs = mutableListOf<GhostTab>()
    private var nextId = 1
    private var activeId = -1
    private lateinit var host: FrameLayout
    private lateinit var strip: LinearLayout
    private lateinit var url: EditText
    private lateinit var count: TextView
    private val prefs by lazy { getSharedPreferences("phormi_ghost_tabs", MODE_PRIVATE) }
    private val profile = "Ghost"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildUi()
        restoreTabs()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 12, 20)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4,4,4,4); setBackgroundColor(Color.rgb(15,23,42)) }
        fun button(text: String) = TextView(this).apply { this.text=text; gravity=Gravity.CENTER; setTextColor(Color.WHITE); textSize=16f; setPadding(8,0,8,0); isClickable=true }
        val back=button("‹"); val forward=button("›"); val reload=button("↻"); val plus=button("+"); val close=button("×")
        url=EditText(this).apply { hint="Ghost search or address"; setSingleLine(); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(148,163,184)); background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.rgb(23,32,51));cornerRadius=24f} }
        top.addView(back); top.addView(url,LinearLayout.LayoutParams(0,44,1f)); top.addView(forward); top.addView(reload); count=button("0"); top.addView(count); top.addView(plus); top.addView(close)
        strip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(4,2,4,2);setBackgroundColor(Color.rgb(11,18,32))}
        host=FrameLayout(this)
        root.addView(top,LinearLayout.LayoutParams(-1,52)); root.addView(strip,LinearLayout.LayoutParams(-1,42)); root.addView(host,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        back.setOnClickListener{active()?.let{if(it.canGoBack())it.goBack()}}; forward.setOnClickListener{active()?.let{if(it.canGoForward())it.goForward()}}; reload.setOnClickListener{active()?.reload()}; plus.setOnClickListener{createTab("about:blank")}; close.setOnClickListener{finishAndClear()}
        url.setOnEditorActionListener{_,action,event->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO || action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE || (event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_DOWN)){navigate();true}else false}
    }

    private fun createTab(initial:String){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) runCatching{ProfileStore.getInstance().getOrCreateProfile(profile)}
        val id=nextId++; val w=WebView(this)
        if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) runCatching{WebViewCompat.setProfile(w,profile)}
        w.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;setSupportMultipleWindows(true);mediaPlaybackRequiresUserGesture=false;cacheMode=WebSettings.LOAD_DEFAULT}
        w.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,u:String?){if(id==activeId)url.setText(u.orEmpty());persist()}}
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w,true)
        val chip=TextView(this).apply{text="Ghost $id";setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(12,0,12,0);setOnClickListener{switchTo(id)}}
        val tab=GhostTab(id,w,chip);tabs+=tab;strip.addView(chip,LinearLayout.LayoutParams(0,40,1f));host.addView(w,FrameLayout.LayoutParams(-1,-1));switchTo(id)
        if(initial!="about:blank")w.loadUrl(initial)
    }
    private fun switchTo(id:Int){activeId=id;tabs.forEach{it.webView.visibility=if(it.id==id)View.VISIBLE else View.GONE;it.chip.alpha=if(it.id==id) 1f else .55f};count.text=tabs.size.toString();active()?.url?.let{url.setText(if(it=="about:blank")"" else it)};persist()}
    private fun active():WebView?=tabs.firstOrNull{it.id==activeId}?.webView
    private fun navigate(){val raw=url.text.toString().trim();if(raw.isBlank())return;active()?.loadUrl(if(raw.startsWith("http://")||raw.startsWith("https://"))raw else if(raw.contains(".")&&!raw.contains(" "))"https://$raw" else "https://www.google.com/search?q="+java.net.URLEncoder.encode(raw,"UTF-8"))}
    private fun persist(){val a=JSONArray();tabs.forEach{a.put(it.webView.url?.takeIf{u->u.isNotBlank()}?:"about:blank")};prefs.edit().putString("urls",a.toString()).apply()}
    private fun restoreTabs(){val a=runCatching{JSONArray(prefs.getString("urls","[]"))}.getOrElse{JSONArray()};if(a.length()==0){createTab("about:blank");return};for(i in 0 until a.length())createTab(a.optString(i,"about:blank"));switchTo(tabs.first().id)}
    private fun finishAndClear(){tabs.forEach{it.webView.stopLoading();it.webView.clearHistory();it.webView.clearCache(true);it.webView.destroy()};tabs.clear();strip.removeAllViews();prefs.edit().clear().apply();if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))runCatching{ProfileStore.getInstance().deleteProfile(profile)};finish()}
    override fun onBackPressed(){if(active()?.canGoBack()==true)active()?.goBack()else finishAndClear()}
    override fun onDestroy(){tabs.forEach{runCatching{it.webView.destroy()}};super.onDestroy()}
}
