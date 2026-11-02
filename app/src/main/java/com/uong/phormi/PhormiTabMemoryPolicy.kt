package com.uong.phormi

/** Policy is independent of WebView so its safety boundaries can be tested deterministically. */
object PhormiTabMemoryPolicy {
    data class Entry(val id: Int, val live: Boolean, val lastUsed: Long, val protected: Boolean)
    fun candidates(entries: List<Entry>, budget: Int): List<Int> {
        val excess = (entries.count { it.live } - budget.coerceAtLeast(1)).coerceAtLeast(0)
        return if (excess == 0) emptyList() else entries.filter { it.live && !it.protected }.sortedBy { it.lastUsed }.map { it.id }
    }
    // No page text, form values or credentials leave the renderer. Only a boolean is returned.
    const val CAN_DISCARD_SCRIPT = """
        (function(){
          if(document.readyState!=='complete')return false;
          if(window.__phormiDirty)return false;
          // Cross-origin frames, editable/shadow components and embedded players cannot be safely inspected.
          if(document.querySelector('iframe,frame,object,embed,[contenteditable]:not([contenteditable="false"])'))return false;
          if(Array.from(document.querySelectorAll('*')).some(function(e){return e.shadowRoot||e.tagName.indexOf('-')>=0;}))return false;
          if(Array.from(document.querySelectorAll('audio,video')).some(function(e){return !e.paused&&!e.ended;}))return false;
          if(Array.from(document.querySelectorAll('input,textarea,select')).some(function(e){
            if(e.type==='file')return e.files&&e.files.length>0;
            if(e.type==='checkbox'||e.type==='radio')return e.checked!==e.defaultChecked;
            if(e.tagName==='SELECT')return Array.from(e.options).some(function(o){return o.selected!==o.defaultSelected;});
            return e.value!==e.defaultValue;
          }))return false;
          return true;
        })()
    """
    const val INSTALL_DIRTY_TRACKER = """
        (function(){if(window.__phormiDirtyTracker)return;window.__phormiDirtyTracker=true;
          document.addEventListener('input',function(){window.__phormiDirty=true;},true);
          document.addEventListener('change',function(){window.__phormiDirty=true;},true);
        })()
    """
}
