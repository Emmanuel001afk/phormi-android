from pathlib import Path
import sys
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]; SRC=ROOT/"app/src/main/java/com/uong/phormi"; MANIFEST=ROOT/"app/src/main/AndroidManifest.xml"; MENU=ROOT/"app/src/main/res/layout/activity_menu.xml"; errors=[]
required=["MainActivity.kt","MenuActivity.kt","HistoryActivity.kt","BookmarksActivity.kt","FavoritesActivity.kt","PhormiFavorites.kt","PhormiVisitTracker.kt","PhormiQuickAccessRenderer.kt","DownloadsActivity.kt","PhormiDownloadSupport.kt","PhormiLocalDownloadStore.kt","PhormiBlobDownloadBridge.kt","PhormiSitePermissionStore.kt","PhormiSecurityCenterActivity.kt","PhormiSiteLockManager.kt","BrowserLockManager.kt","GhostActivity.kt","PhormiEnvironmentManager.kt","TabGroupManager.kt","TabsOverviewActivity.kt","TabGroupsActivity.kt","PhormiKeyboardService.kt","PhormiKeyboardSettingsActivity.kt","PhormiKeyboardPreferences.kt","PhormiKeyboardLexicon.kt","PhormiKeyboardVoiceActivity.kt","PhormiKeyboardMediaActivity.kt","PhormiNavigationLens.kt","PhormiObjectAnchorStore.kt","PhormiMediaViewerActivity.kt","PhormiNotificationCenter.kt","HelpActivity.kt","AccountsActivity.kt"]
for n in required:
    if not(SRC/n).is_file(): errors.append(f"missing required source: {n}")
try: ET.parse(MANIFEST)
except Exception as e: errors.append(f"manifest XML invalid: {e}")
else:
    m=MANIFEST.read_text()
    for a in [".MainActivity",".HelpActivity",".PhormiSecurityCenterActivity",".PhormiKeyboardSettingsActivity",".PhormiKeyboardVoiceActivity",".PhormiKeyboardMediaActivity",".PhormiMediaViewerActivity",".TabGroupsActivity",".TabsOverviewActivity",".DownloadsActivity",".GhostActivity",".FavoritesActivity",".AccountsActivity"]:
        if f'android:name="{a}"' not in m: errors.append(f"manifest missing activity: {a}")
    if 'android:name=".GhostActivity"' in m and 'android:process=":ghost"' not in m: errors.append("GhostActivity is not isolated")
    if 'android:name=".PhormiKeyboardService"' not in m: errors.append("keyboard service missing")
def req(f,markers):
    t=(SRC/f).read_text()
    for x in markers:
        if x not in t: errors.append(f"{f} missing marker: {x}")
req("MainActivity.kt",["canGoBack()","canGoForward()",".reload()","onShowFileChooser","onGeolocationPermissionsShowPrompt","onCreateWindow","onReceivedSslError","onSafeBrowsingHit","onRenderProcessGone","saveTabsImmediate(true)","findAllAsync","shareCurrentPage","toggleDesktopMode","setSplitMode","openSamePageSplit","PhormiSiteLockManager.isLocked","PhormiDefaultBrowserController.request","pruneExpiredTabs","retentionCheckRunnable","startRetentionScheduler","PhormiFavorites.contains","PhormiFavorites.toggle","ProcessLifecycleOwner","PhormiVisitTracker.record","PhormiQuickAccessRenderer.render","safeBrowsingEnabled = true","saveCurrentPageAsPdf","PhormiSitePermissionStore","PhormiLocalDownloadStore"])
req("MenuActivity.kt",["menu_new_tab","menu_ghost","menu_tabs","menu_groups","menu_downloads","menu_keyboard","menu_browser_lock","menu_security","menu_site_lock","menu_find","menu_share","menu_desktop_mode","menu_favorite","menu_theme","menu_settings","menu_save_pdf"])
req("DownloadsActivity.kt",["STATUS_RUNNING","formatProgress(","COLUMN_BYTES_DOWNLOADED_SO_FAR","COLUMN_REASON","PhormiFileOpener.open","cancelDownload","PhormiLocalDownloadStore.list"])
req("PhormiBlobDownloadBridge.kt",["@JavascriptInterface","fun begin(t:String)","fun write(t:String","fun finish(t:String)","MediaStore.Downloads"])
req("PhormiSitePermissionStore.kt",["fun get(c:Context","fun set(c:Context","fun clear(c:Context)"])
req("PhormiLocalDownloadStore.kt",["fun add(c:Context","fun list(c:Context","fun remove(c:Context,id:String)"])
req("PhormiDownloadSupport.kt",["contentDispositionFileName","filename","User-Agent","Referer","Cookie","Video"])
req("GhostActivity.kt",["WebView.setDataDirectorySuffix","FLAG_SECURE","finishAndClear","onSaveInstanceState","SCREEN_ORIENTATION_SENSOR","MIXED_CONTENT_NEVER_ALLOW"])
req("PhormiKeyboardService.kt",["override fun onStartInput","override fun onUpdateSelection","CompletionInfo","InputConnection","InputContentInfo","TYPE_CLASS_NUMBER","TYPE_CLASS_PHONE","deleteSurroundingText","commitContent","PhormiKeyboardPreferences.layout","PhormiKeyboardPreferences.oneHanded","PhormiKeyboardPreferences.theme","PhormiKeyboardPreferences.incognito"])
req("PhormiKeyboardSettingsActivity.kt",["Enable Phormi Keyboard","Choose Phormi Keyboard","enabledInputMethodList","currentInputMethodInfo"])
req("PhormiKeyboardPreferences.kt",["KEY_LAYOUT","KEY_THEME","KEY_ONE_HANDED","KEY_INCOGNITO","KEY_POPUP"])
req("PhormiNotificationCenter.kt",["NotificationChannel","NotificationCompat","postDownloadEvent"])
req("TabGroupManager.kt",["assignTab","groupForTab","tabIds"]); req("TabGroupsActivity.kt",["readCurrentTabs","select_tab_id","Task note"]); req("TabsOverviewActivity.kt",["showGroupChooser","Assign to group","Group"]); req("BrowserLockManager.kt",["BiometricPrompt","DEVICE_CREDENTIAL","canUseDeviceAuthentication"]); req("PhormiFavorites.kt",["contains","toggle","getAll"]); req("PhormiVisitTracker.kt",["THRESHOLD","visits >= THRESHOLD"]); req("PhormiQuickAccessRenderer.kt",["PhormiFavorites.contains","PhormiVisitTracker.top","take(10"]); req("AccountsActivity.kt",["CustomTabsIntent","launchUrl","authentication surface"])
try:
    mt=MENU.read_text()
    for mid in ["menu_new_tab","menu_ghost","menu_tabs","menu_groups","menu_downloads","menu_bookmarks","menu_history","menu_vpn","menu_ai","menu_keyboard","menu_default_browser","menu_security","menu_site_lock","menu_notifications","menu_find","menu_share","menu_navigation_lens","menu_object_anchors","menu_same_page_split","menu_desktop_mode","menu_favorite","menu_keep_screen_on","menu_help","menu_browser_lock","menu_pull_to_refresh","menu_split_screen","menu_tab_retention","menu_theme","menu_settings","menu_save_pdf","menu_close"]:
        if f"@+id/{mid}" not in mt: errors.append(f"menu XML missing id: {mid}")
except Exception as e: errors.append(f"menu XML read failed: {e}")
for p in SRC.glob("*.kt"):
    low=p.read_text().lower()
    for marker in ("todo: implement","throw unsupportedoperationexception","coming soon"):
        if marker in low: errors.append(f"placeholder marker in {p.name}: {marker}")
if errors:
    print("PHORMI FOUNDATION AUDIT: FAIL"); [print(" -",e) for e in errors]; sys.exit(1)
print("PHORMI FOUNDATION AUDIT: PASS"); print(f"Checked {len(required)} required sources plus browser, download, permission, menu and keyboard foundations.")
