from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/uong/phormi'

p=src/'PhormiKeyboardSettingsActivity.kt'
t=p.read_text()
insert='''        option(root, "Key sounds", "Play a short key sound where the device allows it.", PhormiKeyboardPreferences.sound(this), PhormiKeyboardPreferences.KEY_SOUND)
'''
extra='''        choice(root, "Keyboard layout", listOf("QWERTY", "AZERTY", "QWERTZ", "DVORAK"), PhormiKeyboardPreferences.layout(this), PhormiKeyboardPreferences.KEY_LAYOUT)
        choice(root, "Keyboard theme", listOf("System", "Dark", "Light"), PhormiKeyboardPreferences.theme(this), PhormiKeyboardPreferences.KEY_THEME)
        choice(root, "One-handed mode", listOf("Off", "Left", "Right"), PhormiKeyboardPreferences.oneHanded(this), PhormiKeyboardPreferences.KEY_ONE_HANDED)
        option(root, "Alternate-character popups", "Long-press supported letters for common accented characters.", PhormiKeyboardPreferences.keyPopup(this), PhormiKeyboardPreferences.KEY_POPUP)
        option(root, "Keyboard incognito", "Do not add copied text to Phormi clipboard history.", PhormiKeyboardPreferences.incognito(this), PhormiKeyboardPreferences.KEY_INCOGNITO)
'''
if 'Keyboard layout' not in t:t=t.replace(insert,insert+extra,1)
helper='''    private fun choice(root: LinearLayout, title: String, values: List<String>, current: String, key: String) {
        root.addView(Button(this).apply {
            text = "$title: $current"
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@PhormiKeyboardSettingsActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(values.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                        PhormiKeyboardPreferences.set(this@PhormiKeyboardSettingsActivity, key, values[which])
                        dialog.dismiss()
                        render()
                    }.show()
            }
        })
    }

'''
if 'private fun choice(' not in t:t=t.replace('    private fun option(',helper+'    private fun option(',1)
p.write_text(t)

p=src/'PhormiKeyboardService.kt'
t=p.read_text()
# Add normal long-press alternate characters to the character-key factory.
needle='''        row.addView(this, LinearLayout.LayoutParams(0, 50, weight).apply { setMargins(2, 2, 2, 2) })
'''
repl='''        if (PhormiKeyboardPreferences.keyPopup(this@PhormiKeyboardService) && label.length == 1 && label[0].isLetter()) {
            setOnLongClickListener {
                val alternatives = when (label.lowercase()) {
                    "a" -> "áàâäãå"; "e" -> "éèêëē"; "i" -> "íìîïī"; "o" -> "óòôöõ"; "u" -> "úùûüū"
                    "n" -> "ñńň"; "c" -> "çćč"; "s" -> "śšß"; "z" -> "źžż"; else -> ""
                }
                if (alternatives.isBlank()) return@setOnLongClickListener false
                androidx.appcompat.app.AlertDialog.Builder(this@PhormiKeyboardService)
                    .setTitle(label).setItems(alternatives.map { it.toString() }.toTypedArray()) { _, which -> currentInputConnection?.commitText(alternatives[which].toString(), 1) }.show()
                true
            }
        }
        row.addView(this, LinearLayout.LayoutParams(0, 50, weight).apply { setMargins(2, 2, 2, 2) })
'''
if 'keyPopup(this@PhormiKeyboardService)' not in t:t=t.replace(needle,repl,1)
p.write_text(t)
print('Round 7 keyboard UI repair applied')
