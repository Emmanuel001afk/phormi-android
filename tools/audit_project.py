from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]; SRC=ROOT/"app/src/main/java/com/uong/phormi"; MANIFEST=ROOT/"app/src/main/AndroidManifest.xml"; MENU=ROOT/"app/src/main/res/layout/activity_menu.xml"; errors=[]
required_files=["MainActivity.kt","MenuActivity.kt","HistoryActivity.kt","BookmarksActivity.kt","FavoritesActivity.kt","PhormiFavorites.kt","DownloadsActivity.kt","PhormiDownloadSupport.kt","PhormiSecurityCenterActivity.kt","PhormiSiteLockManager.kt","BrowserLockManager.kt","GhostActivity.kt","PhormiEnvironmentManager.kt","TabGroupManager.kt","TabsOverviewActivity.kt","TabGroupsActivity.kt","PhormiKeyboardService.kt","PhormiKeyboardSettingsActivity.kt","PhormiKeyboardPreferences.kt","PhormiKeyboardLexicon.kt","PhormiKeyboardVoiceActivity.kt","PhormiKeyboardMediaActivity.kt","PhormiNavigationLens.kt","PhormiObjectAnchorStore.kt","PhormiMediaViewerActivity.kt","PhormiNotificationCenter.kt","HelpActivity.kt"]
for name in required_files:
    if not (SRC/name).is_file(): errors.append(f"missing required source: {name}")
try: ET.parse(MANIFEST)
except Exception as exc: errors.append(f"manifest XML invalid: {exc}")
else:
    m=MANIFEST.read_text()
    for a in [".MainActivity",".HelpActivity",".PhormiSecurityCenterActivity",".PhormiKeyboardSettingsActivity",".PhormiKeyboardVoiceActivity",".PhormiKeyboardMediaActivity",".PhormiMediaViewerActivity",".TabGroupsActivity",".TabsOverviewActivity",".DownloadsActivity",".GhostActivity",".FavoritesActivity"]:
        if f'android:name="{a}"' not in m: errors.append(f"manifest missing activity registration: {a}")
    if 'android:name=".GhostActivity"' in m and 'android:process=":ghost"' not in m: errors.append("GhostActivity is not isolated in the :ghost process")
    if 'android:name=".PhormiKeyboardService"' not in m: errors.append("manifest missing keyboard service")
def require(path,needles):
    text=(SRC/path).read_text()
    for n in needles:
        if n not in text: errors.append(f"{path} missing operational marker: {n}")
require("MainActivity.kt",["Tab(","canGoBack()","canGoForward()",".reload()","onShowFileChooser","onGeolocationPermissionsShowPrompt","onCreateWindow","onReceivedSslError","onSafeBrowsingHit","onRenderProcessGone","saveTabsImmediate(true)","findAllAsync","shareCurrentPage","toggleDesktopMode","setSplitMode","openSamePageSplit","PhormiSiteLockManager.isLocked","PhormiDefaultBrowserController.request","pruneExpiredTabs","retentionCheckRunnable","startRetentionScheduler","site.tag.isBlank()","selectTabId","removeBrowserLockOverlay","PhormiFavorites.contains","PhormiFavorites.toggle"])
require("MenuActivity.kt",["wire(R.id.menu_new_tab)","wire(R.id.menu_ghost)","wire(R.id.menu_tabs)","wire(R.id.menu_groups)","wire(R.id.menu_downloads)","wire(R.id.menu_bookmarks)","wire(R.id.menu_history)","wire(R.id.menu_vpn)","wire(R.id.menu_ai)","wire(R.id.menu_keyboard)","PhormiKeyboardSettingsActivity","wire(R.id.menu_default_browser)","wire(R.id.menu_browser_lock)","wire(R.id.menu_security)","wire(R.id.menu_site_lock)","wire(R.id.menu_notifications)","wire(R.id.menu_find)","wire(R.id.menu_share)","wire(R.id.menu_navigation_lens)","wire(R.id.menu_object_anchors)","wire(R.id.menu_same_page_split)","wire(R.id.menu_desktop_mode)","FavoritesActivity","wire(R.id.menu_keep_screen_on)","wire(R.id.menu_help)","wire(R.id.menu_tab_retention)","wire(R.id.menu_pull_to_refresh)","wire(R.id.menu_split_screen)","wire(R.id.menu_theme)","wire(R.id.menu_settings)","wire(R.id.menu_close)","REQ_TABS"])
require("DownloadsActivity.kt",["STATUS_RUNNING","progress(","COLUMN_BYTES_DOWNLOADED_SO_FAR","PhormiFileOpener.open","cancelDownload"])
require("GhostActivity.kt",["WebView.setDataDirectorySuffix","FLAG_SECURE","finishAndClear","restoreTabs"])
require("PhormiKeyboardService.kt",["override fun onStartInput","override fun onUpdateSelection","CompletionInfo","InputConnection","InputContentInfo","TYPE_CLASS_NUMBER","TYPE_CLASS_PHONE","deleteSurroundingText","commitContent","contentDescription"])
require("PhormiNotificationCenter.kt",["NotificationChannel","NotificationCompat","postDownloadEvent"])
require("HelpActivity.kt",["Normal browser","Tab groups + Split Screen","Identity environments","Ghost mode","Downloads & files","Security & privacy","Keyboard","Navigation Lens + Object Anchors","Keep Screen On"])
require("TabGroupManager.kt",["assignTab","groupForTab","tabIds"]); require("TabGroupsActivity.kt",["readCurrentTabs","select_tab_id","Task note"]); require("BrowserLockManager.kt",["BiometricPrompt","DEVICE_CREDENTIAL","canUseDeviceAuthentication"]); require("PhormiFavorites.kt",["contains","toggle","getAll"])
try:
    mt=MENU.read_text()
    for mid in ["menu_new_tab","menu_ghost","menu_tabs","menu_groups","menu_downloads","menu_bookmarks","menu_history","menu_vpn","menu_ai","menu_keyboard","menu_default_browser","menu_security","menu_site_lock","menu_notifications","menu_find","menu_share","menu_navigation_lens","menu_object_anchors","menu_same_page_split","menu_desktop_mode","menu_favorite","menu_keep_screen_on","menu_help","menu_browser_lock","menu_pull_to_refresh","menu_split_screen","menu_tab_retention","menu_theme","menu_settings","menu_close"]:
        if f'@+id/{mid}' not in mt: errors.append(f"menu XML missing id: {mid}")
except Exception as exc: errors.append(f"menu XML read failed: {exc}")
for path in SRC.glob("*.kt"):
    low=path.read_text().lower()
    for marker in ("todo: implement","throw unsupportedoperationexception","coming soon"):
        if marker in low: errors.append(f"placeholder marker in {path.name}: {marker}")
for path in [SRC/"MainActivity.kt",SRC/"MenuActivity.kt"]:
    if "REFERENCE_WINDOW" in path.read_text() or "Reference Window" in path.read_text(): errors.append(f"redundant Reference Window surfaced in {path.name}")
if errors:
    print("PHORMI FOUNDATION AUDIT: FAIL"); [print(" -",e) for e in errors]; sys.exit(1)
print("PHORMI FOUNDATION AUDIT: PASS"); print(f"Checked {len(required_files)} required source files plus menu wiring and operational browser/keyboard markers.")
