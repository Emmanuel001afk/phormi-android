package com.uong.phormi

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
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

/** Separate private-browsing task with session-only tabs and normal browser controls. */
class GhostActivity : AppCompatActivity() {
    private data class GhostTab(val id: Int, val webView: WebView, val chip: TextView)
    private val tabs = mutableListOf<GhostTab>()
    private var nextId = 1
    private var activeId = -1
    private lateinit var host: FrameLayout
    private lateinit var strip: LinearLayout
    private lateinit var url: EditText
    private lateinit var count: TextView
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR; if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) runCatching { WebView.setDataDirectorySuffix("phormi_ghost") }; buildUi(); val savedUrls=savedInstanceState?.getStringArrayList("ghost_urls").orEmpty(); val savedActive=savedInstanceState?.getInt("ghost_active",0)?:0; if(savedUrls.isNotEmpty()){savedUrls.forEach{createTab(it.ifBlank{"about:blank"})};tabs.getOrNull(savedActive.coerceIn(0,tabs.lastIndex))?.let{switchTo(it.id)}}else createTab(intent?.dataString?.takeIf{it.startsWith("http://")||it.startsWith("https://")}?:"about:blank") }
    private fun buildUi(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};fun b(t:String)=TextView(this).apply{text=t;gravity=Gravity.CENTER;setTextColor(Color.WHITE);textSize=18f};val back=b("‹");val forward=b("›");val reload=b("→");val plus=b("+");val close=b("×");url=EditText(this).apply{hint="Ghost search or address";setSingleLine(true);setTextColor(Color.WHITE);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO};count=b("0");toolbar.addView(back,LinearLayout.LayoutParams(42,44));toolbar.addView(url,LinearLayout.LayoutParams(0,44,1f));toolbar.addView(forward,LinearLayout.LayoutParams(42,44));toolbar.addView(reload,LinearLayout.LayoutParams(42,44));toolbar.addView(count,LinearLayout.LayoutParams(44,44));toolbar.addView(plus,LinearLayout.LayoutParams(42,44));toolbar.addView(close,LinearLayout.LayoutParams(42,44));strip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};host=FrameLayout(this);root.addView(toolbar,LinearLayout.LayoutParams(-1,56));root.addView(strip,LinearLayout.LayoutParams(-1,44));root.addView(host,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);back.setOnClickListener{active()?.let{if(it.canGoBack())it.goBack()}};forward.setOnClickListener{active()?.let{if(it.canGoForward())it.goForward()}};reload.setOnClickListener{active()?.reload()};plus.setOnClickListener{createTab("about:blank")};close.setOnClickListener{finishAndClear()};url.setOnEditorActionListener{_,action,event->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO||action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE||(event?.keyCode==KeyEvent.KEYCODE_ENTER&&event.action==KeyEvent.ACTION_DOWN)){navigate();true}else false}}
    private fun createTab(initial:String){val id=nextId++;val w=WebView(this);w.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;if(android.os.Build.VERSION.SDK_INT>=26)safeBrowsingEnabled=true};w.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,pageUrl:String?){if(id==activeId)url.setText(pageUrl.orEmpty().takeIf{it!="about:blank"}.orEmpty());super.onPageFinished(view,pageUrl)}};CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(w,false);val chip=TextView(this).apply{text="Ghost $id";setTextColor(Color.WHITE);gravity=Gravity.CENTER;setOnClickListener{switchTo(id)}};tabs+=GhostTab(id,w,chip);strip.addView(chip,LinearLayout.LayoutParams(0,36,1f));host.addView(w,FrameLayout.LayoutParams(-1,-1));switchTo(id);if(initial!="about:blank")w.loadUrl(initial)}
    private fun switchTo(id:Int){activeId=id;tabs.forEach{it.webView.visibility=if(it.id==id)View.VISIBLE else View.GONE;it.chip.alpha=if(it.id==id)1f else .55f};count.text=tabs.size.toString();active()?.url?.let{url.setText(if(it=="about:blank")""else it)}}
    private fun active():WebView?=tabs.firstOrNull{it.id==activeId}?.webView
    private fun navigate(){val raw=url.text.toString().trim();if(raw.isBlank())return;val target=when{raw.startsWith("http://")||raw.startsWith("https://")->raw;raw.contains(".")&&!raw.contains(" ")->"https://$raw";else->"https://www.google.com/search?q="+java.net.URLEncoder.encode(raw,"UTF-8")};active()?.loadUrl(target)}
    override fun onSaveInstanceState(outState:Bundle){outState.putStringArrayList("ghost_urls",ArrayList(tabs.map{it.webView.url?:"about:blank"}));outState.putInt("ghost_active",tabs.indexOfFirst{it.id==activeId}.coerceAtLeast(0));super.onSaveInstanceState(outState)}
    private fun finishAndClear(){tabs.forEach{it.webView.stopLoading();it.webView.clearHistory();it.webView.clearCache(true);it.webView.destroy()};tabs.clear();strip.removeAllViews();finish()}
    @Suppress("DEPRECATION") @SuppressLint("MissingSuperCall") override fun onBackPressed(){if(active()?.canGoBack()==true)active()?.goBack()else finishAndClear()}
    override fun onDestroy(){tabs.forEach{runCatching{it.webView.destroy()}};tabs.clear();super.onDestroy()}
}
