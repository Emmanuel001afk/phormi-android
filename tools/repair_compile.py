from pathlib import Path

root = Path(__file__).resolve().parents[1]

p = root / "app/src/main/java/com/uong/phormi/MainActivity.kt"
s = p.read_text()
if "import android.app.AlertDialog" not in s:
    s = s.replace("import android.app.Activity\n", "import android.app.Activity\nimport android.app.AlertDialog\n", 1)
s = s.replace("MenuActivity.ACTION_HELP -> showPhormiHelp()", 'MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()')
s = s.replace("PhormiKeyboardController(this).showKeyboardPicker()", "PhormiKeyboardController.showKeyboardPicker(this)")
s = s.replace("javaScriptEnabled = true", "javaScriptEnabled = prefs.getBoolean(\"security_javascript\", true)", 1)
s = s.replace("mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE", "mixedContentMode = if (prefs.getBoolean(\"security_mixed_content\", false)) android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW", 1)
s = s.replace("CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)", "CookieManager.getInstance().setAcceptThirdPartyCookies(webView, prefs.getBoolean(\"security_third_party_cookies\", false))", 1)
p.write_text(s)

p = root / "app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt"
s = p.read_text()
# InputMethodService exposes getInputView(); do not replace it with a nonexistent Kotlin property.
s = s.replace("if (panel == Panel.KEYBOARD && inputView != null) setInputView(render())", "if (panel == Panel.KEYBOARD && getInputView() != null) setInputView(render())")
s = s.replace("shift = false\n        capsLock = false", "shift = PhormiKeyboardPreferences.autoCaps(this) && shouldAutoCapitalize(null)\n        capsLock = false", 1)
old = 'if (completions.isEmpty()) return\n        val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }'
new = '''val localPrefix = currentWordPrefix()\n        val localSuggestions = if (PhormiKeyboardPreferences.suggestions(this) && completions.isEmpty()) PhormiKeyboardLexicon.suggestions(localPrefix) else emptyList()\n        if (completions.isEmpty() && localSuggestions.isEmpty()) return\n        val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }'''
s = s.replace(old, new, 1)
old = '''        completions.forEach { completion ->\n            val text = completion.text?.toString().orEmpty()\n            if (text.isNotBlank()) key(inner, text) { commitText(text) }\n        }'''
new = '''        if (completions.isNotEmpty()) {\n            completions.forEach { completion ->\n                val text = completion.text?.toString().orEmpty()\n                if (text.isNotBlank()) key(inner, text) { commitText(text) }\n            }\n        } else {\n            localSuggestions.forEach { text -> key(inner, text) { replaceCurrentWord(text) } }\n        }'''
s = s.replace(old, new, 1)
s = s.replace('setOnClickListener { action() }', 'setOnClickListener { if (PhormiKeyboardPreferences.haptic(this@PhormiKeyboardService)) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); if (PhormiKeyboardPreferences.sound(this@PhormiKeyboardService)) playSoundEffect(android.view.SoundEffectConstants.CLICK); action() }', 1)
s = s.replace('''            if (before.endsWith("  ")) {\n                ic.deleteSurroundingText(2, 0)\n                ic.commitText(". ", 1)\n            } else {\n                ic.commitText(" ", 1)\n            }''', '''            if (PhormiKeyboardPreferences.autocorrect(this)) {\n                val word = Regex("\\\\S+$").find(before)?.value.orEmpty()\n                val correction = PhormiKeyboardLexicon.correctWord(word)\n                if (correction != null && word.isNotBlank()) {\n                    ic.deleteSurroundingText(word.length, 0)\n                    ic.commitText(correction, 1)\n                }\n            }\n            if (before.endsWith("  ")) {\n                ic.deleteSurroundingText(2, 0)\n                ic.commitText(". ", 1)\n            } else {\n                ic.commitText(" ", 1)\n            }''', 1)
marker = '    private fun commitText(text: String) {'
helpers = '''    private fun shouldAutoCapitalize(info: EditorInfo?): Boolean {\n        val type = info?.inputType ?: InputType.TYPE_CLASS_TEXT\n        val variation = type and InputType.TYPE_MASK_VARIATION\n        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false\n        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI) return false\n        return true\n    }\n\n    private fun currentWordPrefix(): String {\n        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()\n        return Regex("\\\\S+$").find(before)?.value.orEmpty()\n    }\n\n    private fun replaceCurrentWord(replacement: String) {\n        val ic = currentInputConnection ?: return\n        val prefix = currentWordPrefix()\n        if (prefix.isBlank()) { commitText(replacement); return }\n        runCatching { ic.deleteSurroundingText(prefix.length, 0); ic.commitText(replacement, 1) }\n    }\n\n'''
s = s.replace(marker, helpers + marker, 1)
p.write_text(s)

print("Applied browser security and keyboard foundation repairs")
