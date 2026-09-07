from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
MENU = ROOT / "app/src/main/res/layout/activity_menu.xml"
errors = []

required = [
    "MainActivity.kt","MenuActivity.kt","HistoryActivity.kt","BookmarksActivity.kt","FavoritesActivity.kt","PhormiFavorites.kt",
    "PhormiVisitTracker.kt","PhormiQuickAccessRenderer.kt","DownloadsActivity.kt","PhormiDownloadSupport.kt","PhormiLocalDownloadStore.kt",
    "PhormiBlobDownloadBridge.kt","PhormiSitePermissionStore.kt","PhormiSecurityCenterActivity.kt","PhormiSiteLockManager.kt","BrowserLockManager.kt",
    "GhostActivity.kt","PhormiEnvironmentManager.kt","TabGroupManager.kt","TabsOverviewActivity.kt","TabGroupsActivity.kt","PhormiKeyboardService.kt",
    "PhormiKeyboardSettingsActivity.kt","PhormiKeyboardPreferences.kt","PhormiKeyboardLexicon.kt","PhormiKeyboardVoiceActivity.kt","PhormiKeyboardMediaActivity.kt",
    "PhormiNavigationLens.kt","PhormiObjectAnchorStore.kt","PhormiMediaViewerActivity.kt","PhormiNotificationCenter.kt","HelpActivity.kt","AccountsActivity.kt"
]
for name in required:
    if not (SRC / name).is_file(): errors.append(f"missing required source: {name}")

try:
    ET.parse(MANIFEST)
except Exception as e:
    errors.append(f"manifest XML invalid: {e}")
else:
    manifest = MANIFEST.read_text()
    for activity in [".MainActivity",".HelpActivity",".PhormiSecurityCenterActivity",".PhormiKeyboardSettingsActivity",".PhormiKeyboardVoiceActivity",".PhormiKeyboardMediaActivity",".PhormiMediaViewerActivity",".TabGroupsActivity",".TabsOverviewActivity",".DownloadsActivity",".GhostActivity",".FavoritesActivity",".AccountsActivity"]:
        if f'android:name="{activity}"' not in manifest: errors.append(f"manifest missing activity: {activity}")
    if 'android:name=".GhostActivity"' in manifest and 'android:process=":ghost"' not in manifest: errors.append("GhostActivity is not isolated")
    if 'android:name=".PhormiKeyboardService"' not in manifest: errors.append("keyboard service missing")

def require(path, markers):
    text = (SRC / path).read_text()
    for marker in markers:
        if marker not in text: errors.append(f"{path} missing marker: {marker}")

require("MainActivity.kt", [
    "canGoBack()","canGoForward()",".reload()","onShowFileChooser","onGeolocationPermissionsShowPrompt","onCreateWindow",
    "onReceivedSslError","onSafeBrowsingHit","onRenderProcessGone","saveTabsImmediate(true)","findAllAsync","shareCurrentPage","toggleDesktopMode",
    "setSplitMode","openSamePageSplit","PhormiSiteLockManager.isLocked","PhormiDefaultBrowserController.request","pruneExpiredTabs",
    "retentionCheckRunnable","startRetentionScheduler","PhormiFavorites.contains","PhormiFavorites.toggle","ProcessLifecycleOwner",
    "PhormiVisitTracker.record","PhormiQuickAccessRenderer.render","safeBrowsingEnabled = true","saveCurrentPageAsPdf","PhormiSitePermissionStore","PhormiLocalDownloadStore"
])
require("MenuActivity.kt", ["menu_new_tab","menu_ghost","menu_tabs","menu_groups","menu_downloads","menu_keyboard","menu_browser_lock","menu_security","menu_site_lock","menu_find","menu_share","menu_desktop_mode","menu_favorite","menu_theme","menu_settings","menu_save_pdf"])
require("DownloadsActivity.kt", ["STATUS_RUNNING","formatProgress(","COLUMN_BYTES_DOWNLOADED_SO_FAR","COLUMN_REASON","PhormiFileOpener.open","cancelDownload","PhormiLocalDownloadStore.list"])
require("PhormiBlobDownloadBridge.kt", ["@JavascriptInterface","begin(token: String)","write(token: String","finish(token: String)","MediaStore.Downloads"])
require("PhormiSitePermissionStore.kt", ["fun get(context: Context","fun set(context: Context","fun clear(context: Context"])
require("PhormiLocalDownloadStore.kt", ["fun add(context: Context","fun list(context: Context","fun remove(context: Context"])
require("PhormiDownloadSupport.kt", ["contentDispositionFileName","filename","User-Agent","Referer","Cookie","Video"])
require("GhostActivity.kt", ["WebView.setDataDirectorySuffix","FLAG_SECURE","finishAndClear","onSaveInstanceState","SCREEN_ORIENTATION_SENSOR","MIXED_CONTENT_NEVER_ALLOW"])
require("PhormiKeyboardService.kt", ["override fun onStartInput","override fun onUpdateSelection","CompletionInfo","InputConnection","InputContentInfo","TYPE_CLASS_NUMBER","TYPE_CLASS_PHONE","deleteSurroundingText","commitContent","PhormiKeyboardPreferences.layout","PhormiKeyboardPreferences.oneHanded","PhormiKeyboardPreferences.keyPopup","PhormiKeyboardPreferences.incognito"])
require("PhormiKeyboardSettingsActivity.kt", ["Enable Phormi Keyboard","Choose Phormi Keyboard","enabledInputMethodList","currentInputMethodInfo"])
require("PhormiKeyboardPreferences.kt", ["KEY_LAYOUT","KEY_THEME","KEY_ONE_HANDED","KEY_INCOGNITO","KEY_POPUP"])
require("PhormiNotificationCenter.kt", ["NotificationChannel","NotificationCompat","postDownloadEvent"])
require("TabGroupManager.kt", ["assignTab","groupForTab","tabIds"])
require("TabGroupsActivity.kt", ["readCurrentTabs","select_tab_id","Task note"])
require("TabsOverviewActivity.kt", ["showGroupChooser","Assign to group","Group"])
require("BrowserLockManager.kt", ["BiometricPrompt","DEVICE_CREDENTIAL","canUseDeviceAuthentication"])
require("PhormiFavorites.kt", ["contains","toggle","getAll"])
require("PhormiVisitTracker.kt", ["THRESHOLD","visits >= THRESHOLD"])
require("PhormiQuickAccessRenderer.kt", ["PhormiFavorites.contains","PhormiVisitTracker.top","take(10)"])
require("AccountsActivity.kt", ["CustomTabsIntent","launchUrl","authentication surface"])

try:
    menu = MENU.read_text()
    for mid in ["menu_new_tab","menu_ghost","menu_tabs","menu_groups","menu_downloads","menu_bookmarks","menu_history","menu_vpn","menu_ai","menu_keyboard","menu_default_browser","menu_security","menu_site_lock","menu_notifications","menu_find","menu_share","menu_navigation_lens","menu_object_anchors","menu_same_page_split","menu_desktop_mode","menu_favorite","menu_keep_screen_on","menu_help","menu_browser_lock","menu_pull_to_refresh","menu_split_screen","menu_tab_retention","menu_theme","menu_settings","menu_save_pdf","menu_close"]:
        if f"@+id/{mid}" not in menu: errors.append(f"menu XML missing id: {mid}")
except Exception as e:
    errors.append(f"menu XML read failed: {e}")

for path in SRC.glob("*.kt"):
    text = path.read_text().lower()
    for marker in ("todo: implement", "throw unsupportedoperationexception", "coming soon"):
        if marker in text: errors.append(f"placeholder marker in {path.name}: {marker}")

if errors:
    print("PHORMI FOUNDATION AUDIT: FAIL")
    for error in errors: print(" -", error)
    sys.exit(1)
print("PHORMI FOUNDATION AUDIT: PASS")
print(f"Checked {len(required)} required sources plus browser, download, permission, menu and keyboard foundations.")
