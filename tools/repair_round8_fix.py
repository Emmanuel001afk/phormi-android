from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/com/uong/phormi/PhormiKeyboardMediaActivity.kt'
t=p.read_text()
# Normalize the sticker branch after the broad Round 8 media replacement so the picker has one decision path.
t=t.replace('''                if (mode == "sticker") {
                    PhormiKeyboardStickerStore.import(this, uri, "imported")?.let {
                        PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, it))
                    }
                } else if (mode == "sticker") PhormiKeyboardStickerStore.import(this, uri, "imported")?.let { PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, it)) } else PhormiKeyboardService.commitPickedContent(this, uri)''','''                if (mode == "sticker") {
                    PhormiKeyboardStickerStore.import(this, uri, "imported")?.let {
                        PhormiKeyboardService.useSticker(this, PhormiKeyboardStickerStore.contentUri(this, it))
                    }
                } else PhormiKeyboardService.commitPickedContent(this, uri)''')
p.write_text(t)
print('Round 8 media picker normalization applied')
