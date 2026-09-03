package com.uong.phormi

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** DOM inspection layer for Navigation Lens. It never replaces the page WebView. */
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
          while (cur && cur.nodeType === 1 && parts.length < 7) {
            let part = cur.tagName.toLowerCase();
            const same = cur.parentElement ? [...cur.parentElement.children].filter(x => x.tagName === cur.tagName) : [];
            if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
            parts.unshift(part);
            cur = cur.parentElement;
          }
          return parts.join(' > ');
        };
        const out = [];
        const push = (kind, el, label, href='') => {
          const text = (label || '').replace(/\s+/g,' ').trim().slice(0,160);
          if (!text && kind !== 'image') return;
          const locator = cssPath(el);
          if (!locator) return;
          out.push({kind, label: text || '(image)', locator, href: href || ''});
        };
        document.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach(e => push('heading', e, e.innerText));
        document.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"]').forEach(e => push('button', e, e.innerText || e.value || e.getAttribute('aria-label')));
        document.querySelectorAll('a[href]').forEach(e => push('link', e, e.innerText || e.getAttribute('aria-label'), e.href));
        document.querySelectorAll('img').forEach(e => push('image', e, e.alt || e.title || 'image', e.src));
        return JSON.stringify(out.slice(0,160));
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
        webView.evaluateJavascript("(()=>{const e=document.querySelector($selector);if(!e)return false;e.scrollIntoView({behavior:'smooth',block:'center'});e.style.outline='3px solid #ef4444';setTimeout(()=>e.style.outline='',1800);return true})()") { raw ->
            callback?.invoke(raw == "true")
        }
    }
}
