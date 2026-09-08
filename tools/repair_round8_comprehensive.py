from pathlib import Path
import re
R=Path(__file__).resolve().parents[1]; S=R/'app/src/main/java/com/uong/phormi'
def ed(rel, fn):
 p=R/rel; t=p.read_text(); p.write_text(fn(t))
def add(t,a,b): return t if b in t else t.replace(a,b,1)

def f(t):
 t=add(t,'    private const val POP="key_popup"\n','    private const val POP="key_popup"\n    private const val FLOAT="floating"\n    private const val SPLIT="split"\n    private const val SCALE="scale"\n')
 t=add(t,'    fun keyPopup(context:Context)=prefs(context).getBoolean(POP,true)\n','    fun keyPopup(context:Context)=prefs(context).getBoolean(POP,true)\n    fun floating(context:Context)=prefs(context).getBoolean(FLOAT,false)\n    fun split(context:Context)=prefs(context).getBoolean(SPLIT,false)\n    fun scale(context:Context)=prefs(context).getInt(SCALE,100)\n')
 return add(t,'    const val KEY_POPUP=POP\n','    const val KEY_POPUP=POP\n    const val KEY_FLOATING=FLOAT\n    const val KEY_SPLIT=SPLIT\n    const val KEY_SCALE=SCALE\n')
ed('app/src/main/java/com/uong/phormi/PhormiKeyboardPreferences.kt',f)

def f(t):
 a='        option(root, "Keyboard incognito", "Do not add copied text to Phormi clipboard history.", PhormiKeyboardPreferences.incognito(this), PhormiKeyboardPreferences.KEY_INCOGNITO)\n'
 b=a+'        option(root, "Floating keyboard", "Use a smaller centered keyboard surface instead of full width.", PhormiKeyboardPreferences.floating(this), PhormiKeyboardPreferences.KEY_FLOATING)\n        option(root, "Split keyboard", "Separate the left and right key clusters for wider screens or comfortable thumbs.", PhormiKeyboardPreferences.split(this), PhormiKeyboardPreferences.KEY_SPLIT)\n        choice(root, "Keyboard size", listOf("80", "90", "100", "110", "120"), PhormiKeyboardPreferences.scale(this).toString(), PhormiKeyboardPreferences.KEY_SCALE)\n'
 t=add(t,a,b)
 old='                        PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity, key, values[which])\n                        dialog.dismiss()'
 new='                        if (key == PhormiKeyboardPreferences.KEY_SCALE) getSharedPreferences("phormi_keyboard", MODE_PRIVATE).edit().putInt(key, values[which].toInt()).apply() else PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity, key, values[which])\n                        dialog.dismiss()'
 return add(t,old,new)
ed('app/src/main/java/com/uong/phormi/PhormiKeyboardSettingsActivity.kt',f)

ed('app/src/main/java/com/uong/phormi/PhormiKeyboardMediaActivity.kt',lambda t:t.replace('PhormiKeyboardService.commitPickedContent(this, uri)','''if (mode == "sticker") PhormiKeyboardStickerStore.import(this, uri, "imported")?.let { PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, it)) } else PhormiKeyboardService.commitPickedContent(this, uri)'''))

def f(t):
 t=add(t,'        const val ACTION_TAB_GROUPS = "tab_groups"\n','        const val ACTION_TAB_GROUPS = "tab_groups"\n        const val ACTION_PAGE_CAPSULE = "page_capsule"\n')
 return add(t,'        wire(R.id.menu_save_pdf) { finishWith("save_pdf") }\n','        wire(R.id.menu_save_pdf) { finishWith("save_pdf") }\n        wire(R.id.menu_page_capsule) { finishWith(ACTION_PAGE_CAPSULE) }\n')
ed('app/src/main/java/com/uong/phormi/MenuActivity.kt',f)
ed('app/src/main/res/layout/activity_menu.xml',lambda t:add(t,'        <TextView android:id="@+id/menu_share"','        <TextView android:id="@+id/menu_page_capsule" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:gravity="center_vertical" android:text="Page Capsule" android:textColor="#E2E8F0" android:textSize="16sp" android:paddingStart="12dp" android:paddingEnd="12dp" android:background="#1E293B" android:clickable="true" android:focusable="true" />\n        <TextView android:id="@+id/menu_share"'))
ed('app/src/main/res/xml/file_paths.xml',lambda t:add(t,'    <cache-path name="cache" path="." />','    <cache-path name="cache" path="." />\n    <files-path name="stickers" path="phormi_stickers/" />'))
ed('app/src/main/AndroidManifest.xml',lambda t:add(t,'        <activity android:name=".PhormiKeyboardMediaActivity" android:exported="false" android:theme="@android:style/Theme.Material.Light.NoActionBar" />','''        <activity android:name=".PhormiKeyboardMediaActivity" android:exported="false" android:theme="@android:style/Theme.Material.Light.NoActionBar" />
        <activity android:name=".PhormiKeyboardStickerActivity" android:exported="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><intent-filter><action android:name="android.intent.action.SEND" /><category android:name="android.intent.category.DEFAULT" /><data android:mimeType="image/*" /></intent-filter></activity>'''))

def service(t):
 t=t.replace('import android.view.inputmethod.InputContentInfo\n','import android.view.inputmethod.InputContentInfo\nimport android.view.Window\nimport android.view.WindowManager\n')
 t=add(t,'        fun commitPickedContent(context: Context, uri: Uri): Boolean = instance?.commitContent(uri) ?: false\n','        fun commitPickedContent(context: Context, uri: Uri): Boolean = instance?.commitContent(uri) ?: false\n        fun useSticker(context: Context, uri: Uri): Boolean = instance?.commitContent(uri) ?: false\n')
 t=t.replace('private enum class Panel { KEYBOARD, CLIPBOARD, EMOJI }','private enum class Panel { KEYBOARD, CLIPBOARD, EMOJI, STICKERS }')
 t=t.replace('        Panel.EMOJI -> buildEmoji()\n','        Panel.EMOJI -> buildEmoji()\n        Panel.STICKERS -> buildStickers()\n')
 t=add(t,'        key(bar, "Sticker", action = { openMedia("sticker") })\n','        key(bar, "Sticker", action = { panel = Panel.STICKERS; setInputView(render()) })\n        key(bar, "AI", action = { startActivity(Intent(this, PhormiKeyboardStickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })\n')
 if 'private fun buildStickers()' not in t:
  m='    private fun buildClipboard(): View {\n'
  b='''    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly); if (isFullscreen) return
        val one=PhormiKeyboardPreferences.oneHanded(this); val floating=PhormiKeyboardPreferences.floating(this); val max=getMaxWidth()
        val width=if(floating||one!="Off"||PhormiKeyboardPreferences.split(this))(max*if(floating)0.82f else 0.92f).toInt() else WindowManager.LayoutParams.MATCH_PARENT
        win.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT); win.setGravity(when(one){"Left"->Gravity.BOTTOM or Gravity.START;"Right"->Gravity.BOTTOM or Gravity.END;else->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL})
    }

    private fun buildStickers(): View {
        val root=baseRoot(); val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        key(header,"← Keyboard"){panel=Panel.KEYBOARD;setInputView(render())}; key(header,"Import"){openMedia("sticker")}; key(header,"AI"){startActivity(Intent(this,PhormiKeyboardStickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        root.addView(header,LinearLayout.LayoutParams(-1,48)); val scroll=ScrollView(this); val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; val files=PhormiKeyboardStickerStore.list(this)
        if(files.isEmpty()) grid.addView(TextView(this).apply{text="No saved stickers yet. Import one or create one with AI.";setPadding(16,24,16,24)})
        files.chunked(4).forEach{g->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};g.forEach{file->val b=android.widget.ImageButton(this).apply{setImageURI(Uri.fromFile(file));contentDescription=file.name;setOnClickListener{commitContent(PhormiKeyboardStickerStore.contentUri(this@PhormiKeyboardService,file))};setOnLongClickListener{PhormiKeyboardStickerStore.delete(this@PhormiKeyboardService,file);setInputView(render());true}};row.addView(b,LinearLayout.LayoutParams(0,82,1f))};grid.addView(row)}
        scroll.addView(grid);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root
    }

'''
  t=t.replace(m,b+m,1)
 t=t.replace('row.addView(this, LinearLayout.LayoutParams(0, 50, weight).apply { setMargins(2, 2, 2, 2) })','row.addView(this, LinearLayout.LayoutParams(0,(50*PhormiKeyboardPreferences.scale(this@PhormiKeyboardService).coerceIn(80,120)/100f).toInt().coerceAtLeast(40),weight).apply{setMargins(2,2,2,2)})')
 old='when(PhormiKeyboardPreferences.layout(this)){"AZERTY"->listOf("azertyuiop","qsdfghjklm","wxcvbn");"QWERTZ"->listOf("qwertzuiop","asdfghjkl","yxcvbnm");"DVORAK"->listOf("\',.pyfgcrl","aoeuidhtns",";qjkxbmwvz");else->listOf("qwertyuiop","asdfghjkl","zxcvbnm")}.forEach { root.addView(charRow(it)) }'
 new='when(PhormiKeyboardPreferences.layout(this)){"AZERTY"->listOf("azertyuiop","qsdfghjklm","wxcvbn");"QWERTZ"->listOf("qwertzuiop","asdfghjkl","yxcvbnm");"DVORAK"->listOf("\',.pyfgcrl","aoeuidhtns",";qjkxbmwvz");else->listOf("qwertyuiop","asdfghjkl","zxcvbnm")}.forEach { root.addView(charRow(if(PhormiKeyboardPreferences.split(this)) it.take((it.length+1)/2)+"     "+it.drop((it.length+1)/2) else it)) }'
 t=t.replace(old,new)
 return t
ed('app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt',service)

def main(t):
 t=add(t,'    private var pendingPermissionRequest: PermissionRequest? = null\n','    private var pendingPermissionRequest: PermissionRequest? = null\n    private var pendingMediaOrigin = ""\n    private var pendingMediaAllowed: Array<String> = emptyArray()\n')
 t=add(t,'    private fun configureWebView(webView: WebView) {\n','''    private fun desktopUserAgent(): String {
        val base=WebSettings.getDefaultUserAgent(this); val version=Regex("Chrome/([0-9.]+)").find(base)?.groupValues?.getOrNull(1) ?: "140.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    private fun configureWebView(webView: WebView) {
''')
 t=re.sub(r'userAgentString = if \(desktop\) "Mozilla/5\.0 \(X11; Linux x86_64\).*?else WebSettings\.getDefaultUserAgent\(this@MainActivity\)','userAgentString = if (desktop) desktopUserAgent() else WebSettings.getDefaultUserAgent(this@MainActivity)',t,count=1)
 t=re.sub(r'''settings\.userAgentString = if \(desktop\) \{.*?\n\s*\} else WebSettings\.getDefaultUserAgent\(this\)\n\s*settings\.useWideViewPort = desktop\n\s*settings\.loadWithOverviewMode = desktop\n\s*webView\.setInitialScale\(if \(desktop\) 100 else 0\)''','''settings.userAgentString = if (desktop) desktopUserAgent() else WebSettings.getDefaultUserAgent(this)
        settings.useWideViewPort = true; settings.loadWithOverviewMode = true; webView.setInitialScale(0)''',t,count=1,flags=re.S)
 pat=r'''                val allowed = request\.resources\.filter \{.*?\n            override fun onGeolocationPermissionsShowPrompt'''
 repl='''                val allowed=request.resources.filter{it==PermissionRequest.RESOURCE_AUDIO_CAPTURE||it==PermissionRequest.RESOURCE_VIDEO_CAPTURE}.toTypedArray()
                if(allowed.isEmpty()){request.deny();return}; val origin=request.origin?.toString().orEmpty()
                if((PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed&&PhormiSitePermissionStore.get(this@MainActivity,origin,"camera")==false)||(PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed&&PhormiSitePermissionStore.get(this@MainActivity,origin,"microphone")==false)){request.deny();return}
                pendingPermissionRequest?.deny();pendingPermissionRequest=request;pendingMediaOrigin=origin;pendingMediaAllowed=allowed
                val needed=mutableListOf<String>();if(PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)needed+=Manifest.permission.CAMERA;if(PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed+=Manifest.permission.RECORD_AUDIO
                if(needed.isEmpty())showPendingMediaDecision(request,origin,allowed)else{pendingPermissionPermissions=needed.toTypedArray();ActivityCompat.requestPermissions(this@MainActivity,pendingPermissionPermissions,REQ_MEDIA_PERMISSIONS)}
            }

            override fun onGeolocationPermissionsShowPrompt'''
 t=re.sub(pat,repl,t,count=1,flags=re.S)
 marker='    override fun onRequestPermissionsResult(\n'
 helper='''    private fun showPendingMediaDecision(request: PermissionRequest, origin: String, allowed: Array<String>) {
        val r=mutableListOf<String>();if(PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed)r+="camera";if(PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed)r+="microphone"
        AlertDialog.Builder(this).setTitle("Allow ${r.joinToString(" + ")}?").setMessage("$origin wants access to your device media.").setNegativeButton("Block"){_,_->r.forEach{PhormiSitePermissionStore.set(this,origin,it,false)};request.deny()}.setPositiveButton("Allow"){_,_->r.forEach{PhormiSitePermissionStore.set(this,origin,it,true)};request.grant(allowed)}.show()
    }

'''
 t=add(t,marker,helper+marker)
 t=t.replace('''                    if (granted) request.grant(request.resources) else request.deny()
                }
                pendingPermissionRequest = null
                pendingPermissionPermissions = emptyArray()''','''                    if (granted) { val o=pendingMediaOrigin; val a=pendingMediaAllowed.ifEmpty{request.resources}; if(o.isNotBlank()){if(PermissionRequest.RESOURCE_VIDEO_CAPTURE in a)PhormiSitePermissionStore.set(this,o,"camera",true);if(PermissionRequest.RESOURCE_AUDIO_CAPTURE in a)PhormiSitePermissionStore.set(this,o,"microphone",true)};showPendingMediaDecision(request,o,a) } else request.deny()
                }
                pendingPermissionRequest = null; pendingPermissionPermissions = emptyArray(); pendingMediaOrigin=""; pendingMediaAllowed=emptyArray()''')
 t=add(t,'                "save_pdf" -> saveCurrentPageAsPdf()\n','                "save_pdf" -> saveCurrentPageAsPdf()\n                MenuActivity.ACTION_PAGE_CAPSULE -> handlePageCapsule()\n')
 marker='    private fun saveCurrentPageAsPdf()'
 cap='''    private fun handlePageCapsule(){val v=activeWebView()?:return;val u=v.url.orEmpty();if(!u.startsWith("http"))return;val s=getSharedPreferences("phormi_page_capsule",MODE_PRIVATE);val saved=s.getString("url","").orEmpty();if(saved.isBlank()){s.edit().putString("url",u).putString("title",v.title.orEmpty()).putInt("scroll",v.scrollY).apply();Toast.makeText(this,"Page Capsule saved",Toast.LENGTH_LONG).show();return};AlertDialog.Builder(this).setTitle("Page Capsule").setMessage("Saved: ${s.getString("title",saved)}").setNegativeButton("Replace"){_,_->s.edit().putString("url",u).putString("title",v.title.orEmpty()).putInt("scroll",v.scrollY).apply()}.setNeutralButton("Clear"){_,_->s.edit().clear().apply()}.setPositiveButton("Open"){_,_->createNewTab(saved);activeWebView()?.postDelayed({activeWebView()?.scrollTo(0,s.getInt("scroll",0))},900)}.show()}

'''
 t=add(t,marker,cap+marker);return t
ed('app/src/main/java/com/uong/phormi/MainActivity.kt',main)

def ai(t):
 t=add(t,'import java.io.IOException\n','import java.io.IOException\nimport java.net.URLEncoder\n')
 old='''    suspend fun synthesizeSearchAnswer(query: String, evidence: String): String? = withContext(Dispatchers.IO) {
        val provider = listProviders().firstOrNull { it.apiKey.isNotBlank() && it.endpoint.isNotBlank() && it.model.isNotBlank() }
            ?: return@withContext null
'''
 new='''    suspend fun synthesizeSearchAnswer(query: String, evidence: String): String? = withContext(Dispatchers.IO) {
        val provider=listProviders().firstOrNull{it.apiKey.isNotBlank()&&it.endpoint.isNotBlank()&&it.model.isNotBlank()}
        if(provider==null)return@withContext runCatching{val p="You are the unified search assistant inside a browser. Answer concisely using only the supplied search evidence. Question: $query\\n\\nSearch evidence:\\n$evidence";client.newCall(Request.Builder().url("https://text.pollinations.ai/${URLEncoder.encode(p,"UTF-8")}?model=openai").get().build()).execute().use{r->if(!r.isSuccessful)null else r.body?.string()?.trim()?.takeIf{it.isNotBlank()}}}.getOrNull()
'''
 return add(t,old,new)
ed('app/src/main/java/com/uong/phormi/AiController.kt',ai)
