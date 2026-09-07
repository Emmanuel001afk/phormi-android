from pathlib import Path

root = Path(__file__).resolve().parents[1]

# MainActivity: missing Android dialog import and stale help/controller calls.
p = root / "app/src/main/java/com/uong/phormi/MainActivity.kt"
s = p.read_text()
if "import android.app.AlertDialog" not in s:
    s = s.replace("import android.app.Activity\n", "import android.app.Activity\nimport android.app.AlertDialog\n", 1)
s = s.replace("MenuActivity.ACTION_HELP -> showPhormiHelp()", 'MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()')
s = s.replace("PhormiKeyboardController(this).showKeyboardPicker()", "PhormiKeyboardController.showKeyboardPicker(this)")
p.write_text(s)

# InputMethodService on this project's Android SDK stubs: use the explicit getter.
p = root / "app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt"
s = p.read_text().replace("if (panel == Panel.KEYBOARD && inputView != null)", "if (panel == Panel.KEYBOARD && getInputView() != null)")
p.write_text(s)

print("Applied compile repairs")
