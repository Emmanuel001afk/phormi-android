from pathlib import Path

root = Path(__file__).resolve().parents[1]

# MainActivity: deterministic source compatibility repairs.
p = root / "app/src/main/java/com/uong/phormi/MainActivity.kt"
s = p.read_text()
if "import android.app.AlertDialog" not in s:
    s = s.replace("import android.app.Activity\n", "import android.app.Activity\nimport android.app.AlertDialog\n", 1)
s = s.replace("MenuActivity.ACTION_HELP -> showPhormiHelp()", 'MenuActivity.ACTION_HELP -> AlertDialog.Builder(this).setTitle("Phormi Help").setMessage("Use the address bar to search or open a site. Tabs, Ghost mode, split view, downloads, keyboard tools, privacy, and browser settings are available from the menu.").setPositiveButton("OK", null).show()')
s = s.replace("PhormiKeyboardController(this).showKeyboardPicker()", "PhormiKeyboardController.showKeyboardPicker(this)")
p.write_text(s)

# InputMethodService compatibility plus keyboard foundations.
p = root / "app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt"
s = p.read_text().replace("if (panel == Panel.KEYBOARD && inputView != null)", "if (panel == Panel.KEYBOARD)")
s = s.replace("shift = false\n        capsLock = false", "shift = PhormiKeyboardPreferences.autoCaps(this) && shouldAutoCapitalize(attribute)\n        capsLock = false", 1)
old = 'if (completions.isEmpty()) return\n        val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }'
new = '''val localPrefix = currentWordPrefix()\n        val localSuggestions = if (PhormiKeyboardPreferences.suggestions(this) && completions.isEmpty()) PhormiKeyboardLexicon.suggestions(localPrefix) else emptyList()\n        if (completions.isEmpty() && localSuggestions.isEmpty()) return\n        val row = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }'''
s = s.replace(old, new, 1)
old = '''        completions.forEach { completion ->\n            val text = completion.text?.toString().orEmpty()\n            if (text.isNotBlank()) key(inner, text) { commitText(text) }\n        }'''
new = '''        if (completions.isNotEmpty()) {\n            completions.forEach { completion ->\n                val text = completion.text?.toString().orEmpty()\n                if (text.isNotBlank()) key(inner, text) { commitText(text) }\n            }\n        } else {\n            localSuggestions.forEach { text -> key(inner, text) { replaceCurrentWord(text) } }\n        }'''
s = s.replace(old, new, 1)
s = s.replace('setOnClickListener { action() }', 'setOnClickListener { if (PhormiKeyboardPreferences.haptic(this)) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); if (PhormiKeyboardPreferences.sound(this)) playSoundEffect(android.view.SoundEffectConstants.CLICK); action() }', 1)
s = s.replace('''            if (before.endsWith("  ")) {\n                ic.deleteSurroundingText(2, 0)\n                ic.commitText(". ", 1)\n            } else {\n                ic.commitText(" ", 1)\n            }''', '''            if (PhormiKeyboardPreferences.autocorrect(this)) {\n                val word = before.substringAfterLast(Regex("\\\\s"), before).trim()\n                val correction = PhormiKeyboardLexicon.correctWord(word)\n                if (correction != null && word.isNotBlank()) {\n                    ic.deleteSurroundingText(word.length, 0)\n                    ic.commitText(correction, 1)\n                }\n            }\n            if (before.endsWith("  ")) {\n                ic.deleteSurroundingText(2, 0)\n                ic.commitText(". ", 1)\n            } else {\n                ic.commitText(" ", 1)\n            }''', 1)
marker = '    private fun commitText(text: String) {'
helpers = '''    private fun shouldAutoCapitalize(info: EditorInfo?): Boolean {\n        val type = info?.inputType ?: InputType.TYPE_CLASS_TEXT\n        val variation = type and InputType.TYPE_MASK_VARIATION\n        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false\n        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI) return false\n        return true\n    }\n\n    private fun currentWordPrefix(): String {\n        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()\n        return before.substringAfterLast(Regex("\\\\s"), before).trim()\n    }\n\n    private fun replaceCurrentWord(replacement: String) {\n        val ic = currentInputConnection ?: return\n        val prefix = currentWordPrefix()\n        if (prefix.isBlank()) { commitText(replacement); return }\n        runCatching { ic.deleteSurroundingText(prefix.length, 0); ic.commitText(replacement, 1) }\n    }\n\n'''
s = s.replace(marker, helpers + marker, 1)
p.write_text(s)

print("Applied browser, keyboard compatibility, and offline keyboard foundation repairs")
