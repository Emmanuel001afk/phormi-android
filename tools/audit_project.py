from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/uong/phormi"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
errors = []

required_files = [
    "MainActivity.kt", "MenuActivity.kt", "HistoryActivity.kt", "BookmarksActivity.kt",
    "DownloadsActivity.kt", "PhormiDownloadSupport.kt", "PhormiSecurityCenterActivity.kt",
    "PhormiSiteLockManager.kt", "BrowserLockManager.kt", "GhostActivity.kt",
    "PhormiEnvironmentManager.kt", "TabGroupManager.kt", "TabsOverviewActivity.kt",
    "TabGroupsActivity.kt", "PhormiKeyboardService.kt", "PhormiKeyboardSettingsActivity.kt",
    "PhormiKeyboardPreferences.kt", "PhormiKeyboardLexicon.kt", "PhormiKeyboardVoiceActivity.kt",
    "PhormiKeyboardMediaActivity.kt", "PhormiNavigationLens.kt", "PhormiObjectAnchorStore.kt",
    "PhormiMediaViewerActivity.kt", "PhormiNotificationCenter.kt", "HelpActivity.kt",
]
for name in required_files:
    if not (SRC / name).is_file():
        errors.append(f"missing required source: {name}")

try:
    ET.parse(MANIFEST)
except Exception as exc:
    errors.append(f"manifest XML invalid: {exc}")
else:
    manifest_text = MANIFEST.read_text()
    for activity in [
        ".MainActivity", ".HelpActivity", ".PhormiSecurityCenterActivity",
        ".PhormiKeyboardSettingsActivity", ".PhormiKeyboardVoiceActivity",
        ".PhormiKeyboardMediaActivity", ".PhormiMediaViewerActivity",
        ".TabGroupsActivity", ".TabsOverviewActivity", ".DownloadsActivity",
    ]:
        if f'android:name="{activity}"' not in manifest_text:
            errors.append(f"manifest missing activity registration: {activity}")
    if 'android:name=".PhormiKeyboardService"' not in manifest_text:
        errors.append("manifest missing keyboard service")


def require(path, needles):
    text = (SRC / path).read_text()
    for needle in needles:
        if needle not in text:
            errors.append(f"{path} missing operational marker: {needle}")

require("MainActivity.kt", [
    "Tab(", "canGoBack()", "canGoForward()", ".reload()",
    "onShowFileChooser", "onGeolocationPermissionsShowPrompt", "onCreateWindow",
    "onReceivedSslError", "onSafeBrowsingHit", "onRenderProcessGone",
    "saveTabsImmediate(true)", "findAllAsync", "shareCurrentPage",
    "addCurrentPageToBookmarks", "toggleDesktopMode", "setSplitMode",
    "openSamePageSplit", "PhormiSiteLockManager.isLocked", "PhormiDefaultBrowserController.request",
])

require("PhormiKeyboardService.kt", [
    "override fun onStartInput", "override fun onUpdateSelection", "CompletionInfo",
    "InputConnection", "InputContentInfo", "TYPE_CLASS_NUMBER", "TYPE_CLASS_PHONE",
    "deleteSurroundingText", "commitContent", "contentDescription",
])

require("PhormiNotificationCenter.kt", ["NotificationChannel", "NotificationCompat", "postDownloadEvent"])
require("HelpActivity.kt", [
    "Normal browser", "Tab groups + Split Screen", "Identity environments", "Ghost mode",
    "Downloads & files", "Security & privacy", "Keyboard", "Navigation Lens + Object Anchors",
    "Keep Screen On",
])

for path in SRC.glob("*.kt"):
    text = path.read_text().lower()
    for marker in ("todo: implement", "throw unsupportedoperationexception", "coming soon"):
        if marker in text:
            errors.append(f"placeholder marker in {path.name}: {marker}")

for path in [SRC / "MainActivity.kt", SRC / "MenuActivity.kt"]:
    text = path.read_text()
    if "REFERENCE_WINDOW" in text or "Reference Window" in text:
        errors.append(f"redundant Reference Window surfaced in {path.name}")

if errors:
    print("PHORMI FOUNDATION AUDIT: FAIL")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("PHORMI FOUNDATION AUDIT: PASS")
print(f"Checked {len(required_files)} required source files and core browser/keyboard integration markers.")
