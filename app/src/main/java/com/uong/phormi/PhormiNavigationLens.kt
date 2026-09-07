package com.uong.phormi

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** DOM inspection/focus layer. It works for navigable and non-navigable page objects. */
object PhormiNavigationLens {
    data class WebObject(
        val kind: String,
        val label: String,
        val locator: String,
        val href: String = ""
    )

    private val script = """
      (() => {
        const cssPath = (el) => {
          if (!el || el.nodeType !== 1) return '';
          if (el.id) return '#' + CSS.escape(el.id);
          const parts = [];
          let cur = el;
          while (cur && cur.nodeType === 1 && parts.length < 9) {
            let part = cur.tagName.toLowerCase();
            const stable = cur.getAttribute('data-testid') || cur.getAttribute('name') || cur.getAttribute('aria-label');
            if (stable) part += '[' + (cur.getAttribute('data-testid') ? 'data-testid' : cur.getAttribute('name') ? 'name' : 'aria-label') + '=\"' + CSS.escape(stable) + '\"]';
            else {
              const same = cur.parentElement ? [...cur.parentElement.children].filter(x => x.tagName === cur.tagName) : [];
              if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
            }
            parts.unshift(part);
            cur = cur.parentElement;
          }
          return parts.join(' > ');
        };
        const out = [];
        const seen = new Set();
        const push = (kind, el, label, href='') => {
          if (!el || seen.has(el)) return;
          const text = (label || '').replace(/\s+/g,' ').trim().slice(0,160);
          if (!text && !['image','video','audio'].includes(kind)) return;
          const locator = cssPath(el);
          if (!locator) return;
          seen.add(el);
          out.push({kind, label: text || '(' + kind + ')', locator, href: href || ''});
        };
        document.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach(e => push('heading', e, e.innerText));
        document.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"],input[type="checkbox"],input[type="radio"],select').forEach(e => push('control', e, e.innerText || e.value || e.getAttribute('aria-label') || e.getAttribute('name')));
        document.querySelectorAll('a[href]').forEach(e => push('link', e, e.innerText || e.getAttribute('aria-label') || e.title, e.href));
        document.querySelectorAll('img').forEach(e => push('image', e, e.alt || e.title, e.src));
        document.querySelectorAll('video').forEach(e => push('video', e, e.getAttribute('aria-label') || e.title || e.currentSrc || e.src));
        document.querySelectorAll('audio').forEach(e => push('audio', e, e.getAttribute('aria-label') || e.title || e.currentSrc || e.src));
        document.querySelectorAll('article,main,section,[role="article"],[role="main"],[role="region"]').forEach(e => push('section', e, e.getAttribute('aria-label') || e.querySelector('h1,h2,h3,h4')?.innerText || e.innerText));
        document.querySelectorAll('p,li,blockquote,pre,code').forEach(e => push('text', e, e.innerText));
        return JSON.stringify(out.slice(0,220));
      })()
    """.trimIndent()

    fun inspect(webView: WebView, callback: (List<WebObject>) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            val result = mutableListOf<WebObject>()
            runCatching {
                val json = JSONArray(org.json.JSONTokener(raw).nextValue().toString())
                for (i in 0 until json.length()) {
                    val o = json.optJSONObject(i) ?: continue
                    result += WebObject(o.optString("kind"), o.optString("label"), o.optString("locator"), o.optString("href"))
                }
            }
            callback(result)
        }
    }

    fun focus(webView: WebView, locator: String, callback: ((Boolean) -> Unit)? = null) {
        val selector = JSONObject.quote(locator)
        val js = """
          (()=>{
            const e=document.querySelector($selector);
            if(!e)return false;
            e.scrollIntoView({behavior:'smooth',block:'center',inline:'nearest'});
            const old=e.style.outline;
            e.style.outline='3px solid #ef4444';
            e.style.outlineOffset='3px';
            setTimeout(()=>{e.style.outline=old;e.style.outlineOffset='';},1800);
            return true;
          })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw -> callback?.invoke(raw == "true") }
    }
}
