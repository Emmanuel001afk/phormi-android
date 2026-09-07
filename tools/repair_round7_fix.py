from pathlib import Path

root=Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/com/uong/phormi'

p=src/'PhormiSitePermissionStore.kt'
t=p.read_text()
t=t.replace('private fun o(u:String)=runCatching{val x=URI(u); "${x.scheme.lowercase()}://${x.host.lowercase()}${if(x.port>0)":${x.port}"else""}"}.getOrDefault("")', 'private fun o(u:String)=runCatching{val x=URI(u); val port=if(x.port>0) ":${x.port}" else ""; "${x.scheme.lowercase()}://${x.host.lowercase()}$port"}.getOrDefault("")')
p.write_text(t)

p=src/'PhormiKeyboardService.kt'
t=p.read_text()
t=t.replace('PhormiKeyboardPreferences.theme(this)', 'PhormiKeyboardPreferences.theme(this@PhormiKeyboardService)')
t=t.replace('PhormiKeyboardPreferences.oneHanded(this)', 'PhormiKeyboardPreferences.oneHanded(this@PhormiKeyboardService)')
p.write_text(t)

print('Round 7 compile fixes applied')
