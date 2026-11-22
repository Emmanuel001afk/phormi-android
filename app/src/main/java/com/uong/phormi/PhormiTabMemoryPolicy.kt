package com.uong.phormi

/**
 * Policy is independent of WebView so its safety boundaries can be tested deterministically.
 *
 * Memory budget is a safety net, not a tab closer. The reported symptom was a tab that
 * stayed listed but came back blank, so the budget never *rearranges* which tab the
 * user loses first: a page used inside the grace window is simply not a candidate.
 *
 * Memory pressure is a different situation. `candidates` has to return something then,
 * or the policy would refuse to free anything on the one call that matters, so callers
 * that are under real pressure pass `honourGrace = false`.
 */
object PhormiTabMemoryPolicy {
    data class Entry(val id: Int, val live: Boolean, val lastUsed: Long, val protected: Boolean)

    /** Ten minutes. Long enough to read a page, short enough to still free memory. */
    const val DEFAULT_GRACE_MILLIS = 10 * 60 * 1000L

    /**
     * @param now current time; ignored when [graceMillis] is 0
     * @param graceMillis pages used more recently than this are not candidates
     * @param honourGrace set false under real memory pressure, where freeing memory
     *   matters more than keeping a page warm
     */
    fun candidates(
        entries: List<Entry>,
        budget: Int,
        now: Long = 0L,
        graceMillis: Long = 0L,
        honourGrace: Boolean = true
    ): List<Int> {
        val excess = (entries.count { it.live } - budget.coerceAtLeast(1)).coerceAtLeast(0)
        if (excess == 0) return emptyList()
        val mutable = entries.filter { it.live && !it.protected }
        val outsideGrace =
            if (graceMillis > 0L && honourGrace) mutable.filter { it.lastUsed < now - graceMillis }
            else mutable
        return outsideGrace.sortedBy { it.lastUsed }.map { it.id }
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
