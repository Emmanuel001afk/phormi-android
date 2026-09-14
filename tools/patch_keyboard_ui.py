from pathlib import Path

p = Path('app/src/main/java/com/uong/phormi/PhormiKeyboardServiceV2.kt')
s = p.read_text()

# Keep the IME window full-size but pin one fixed keyboard viewport to its bottom.
s = s.replace('import android.widget.Button\n', 'import android.widget.Button\nimport android.widget.FrameLayout\n')
old = 'private fun root(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(2),dp(6),dp(2));setBackgroundColor(themeBackground());layoutParams=LinearLayout.LayoutParams(-1,scaled(baseHeight()));minimumHeight=scaled(baseHeight());applyWallpaper(this);addResizeGrip(this) }'
new = '''private fun root(): FrameLayout {
        val frame=FrameLayout(this).apply{setBackgroundColor(Color.TRANSPARENT);layoutParams=FrameLayout.LayoutParams(-1,-1)}
        val viewport=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(2),dp(6),dp(2));setBackgroundColor(themeBackground());minimumHeight=scaled(baseHeight());applyWallpaper(this)}
        frame.addView(viewport,FrameLayout.LayoutParams(-1,scaled(baseHeight()),Gravity.BOTTOM))
        addResizeGrip(viewport)
        return frame
    }
    private fun viewport(frame:FrameLayout):LinearLayout=frame.getChildAt(0) as LinearLayout'''
if old not in s:
    raise SystemExit('root pattern not found')
s = s.replace(old, new)

s = s.replace('val root=root();toolbar(root);if(page==KeyboardPage.LETTERS)predictionStrip(root);', 'val frame=root();val root=viewport(frame);toolbar(root);if(page==KeyboardPage.LETTERS)predictionStrip(root);')
s = s.replace('return root}', 'return frame}', 1)
s = s.replace('private fun buildEmoji():View{val root=root();', 'private fun buildEmoji():View{val frame=root();val root=viewport(frame);')
s = s.replace('root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}', 'root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return frame}', 1)
for name in ['buildClipboard','buildAiEmoji','buildMedia','buildTools','buildSettings']:
    s = s.replace(f'private fun {name}():View{{val root=root();', f'private fun {name}():View{{val frame=root();val root=viewport(frame);')
s = s.replace('root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return root}', 'root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));return frame}')

# Fill the same fixed viewport on every page instead of leaving a dead area.
s = s.replace('fixedScrollableRows(root,listOf("qwertyuiop","asdfghjkl","zxcvbnm"));', 'fixedScrollableRows(root,listOf("qwertyuiop","asdfghjkl","zxcvbnm"), if(email||uri)55 else 70);')
s = s.replace('fixedScrollableRows(root,listOf("1234567890","789456123","0.,"));', 'fixedScrollableRows(root,listOf("1234567890","789456123","0.,"),66);')
s = s.replace('fixedScrollableRows(root,rows)', 'fixedScrollableRows(root,rows,66)')
s = s.replace('private fun fixedScrollableRows(root:LinearLayout,rows:List<String>){', 'private fun fixedScrollableRows(root:LinearLayout,rows:List<String>,rowHeight:Int=50){')
s = s.replace('list.addView(row,LinearLayout.LayoutParams(-1,scaled(50)))', 'list.addView(row,LinearLayout.LayoutParams(-1,scaled(rowHeight)))')

# Three explicit capitalization states: lowercase, one-letter accent, red caps lock.
old_shift = 'return keyButton(if(state==0)"⇧"else"⇧A",action=action).apply{background=rounded(when(state){2->Color.rgb(220,38,38);1->accent();else->themeKey()},dp(9));setTextColor(Color.WHITE);contentDescription=when(state){2->"Caps lock";1->"One-letter capitalization";else->"Shift"}}}'
new_shift = 'return keyButton(if(state==0)"⇧"else"⇧A",action=action).apply{background=rounded(when(state){2->Color.rgb(220,38,38);1->accent();else->themeKey()},dp(9));setTextColor(Color.WHITE);contentDescription=when(state){2->"Caps lock";1->"One-letter capitalization";else->"Lowercase"}}}'
if old_shift in s:
    s = s.replace(old_shift, new_shift)

# The in-keyboard settings panel must expose the AI-emoji switch too.
needle = 'toggle("Auto-capitalization",PhormiKeyboardPreferences.autoCaps(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AUTO_CAPS,!PhormiKeyboardPreferences.autoCaps(this));setInputView(render())}'
insert = needle + ';toggle("AI Emoji — automatic contextual",PhormiKeyboardPreferences.aiEmoji(this)){PhormiKeyboardPreferences.set(this@PhormiKeyboardServiceV2,PhormiKeyboardPreferences.KEY_AI_EMOJI,!PhormiKeyboardPreferences.aiEmoji(this));setInputView(render())}'
if needle in s and 'AI Emoji — automatic contextual' not in s:
    s = s.replace(needle, insert)

p.write_text(s)
