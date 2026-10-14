/* Phormi-owned DOM adapter. Page text is evidence, never assistant instructions. */
(() => {
  if (window.__phormiAssistant && window.__phormiAssistant.version === 2) return;
  let sequence = 0;
  const ids = new WeakMap();
  const elements = new Map();
  const sensitive = /password|passcode|\bpin\b|\botp\b|one.?time|verification.?code|security.?code|\bcvv\b|\bcvc\b|card.?number|credit.?card|recovery|secret|token/i;
  const label = e => String(e.getAttribute('aria-label') || e.getAttribute('placeholder') || e.getAttribute('title') || e.innerText || e.getAttribute('name') || '').trim().slice(0, 140);
  const privateField = e => e.tagName === 'INPUT' && /^(password|hidden|file)$/i.test(e.type) ||
    sensitive.test([e.type, e.autocomplete, e.id, e.name, label(e)].join(' '));
  const visible = e => !!e && e.isConnected && !e.hidden && !e.disabled &&
    e.getAttribute('aria-hidden') !== 'true' && e.getClientRects().length > 0 &&
    getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
  const editable = e => !e.readOnly && (e.isContentEditable || e.tagName === 'TEXTAREA' ||
    e.tagName === 'INPUT' && /^(text|search|email|url|tel|number)?$/i.test(e.type));
  const idFor = e => {
    let id = ids.get(e);
    if (!id) { id = 'e' + (++sequence); ids.set(e, id); }
    elements.set(id, e);
    return id;
  };
  const get = id => {
    const e = elements.get(String(id || ''));
    return visible(e) && !privateField(e) ? e : null;
  };
  const risky = e => (e.tagName === 'BUTTON' && (e.type || 'submit') === 'submit') ||
    (e.tagName === 'INPUT' && e.type === 'submit') ||
    /\b(send|post|publish|submit|buy|purchase|pay|order|delete|remove|transfer|confirm|subscribe|sign.?in|log.?in)\b/i.test(label(e));
  const snapshot = () => {
    elements.clear();
    const controls = Array.from(document.querySelectorAll('input,textarea,button,a[href],[role="button"],[contenteditable="true"],[role="textbox"]'))
      .filter(e => visible(e) && !privateField(e)).slice(0, 70)
      .map(e => ({elementId: idFor(e), tag: e.tagName.toLowerCase(), label: label(e),
        editable: editable(e), focused: e === document.activeElement, confirmation: risky(e)}));
    return {title: document.title.slice(0, 180), url: location.href, ready: document.readyState,
      text: (document.body && document.body.innerText || '').slice(0, 4800), controls};
  };
  const inspect = action => {
    const e = get(action.elementId);
    if (!e) return {ok: false, reason: 'Control disappeared, is hidden, or is sensitive. Inspect the page again.'};
    if (action.action === 'type' && !editable(e)) return {ok: false, reason: 'That control is not editable.'};
    return {ok: true, requiresConfirmation: action.action === 'click' && risky(e), label: label(e)};
  };
  const execute = action => {
    if (action.action === 'press_enter') {
      const e = document.activeElement;
      if (!visible(e) || privateField(e) || !editable(e)) return {ok: false, reason: 'No safe editor is focused.'};
      if (e.form && typeof e.form.requestSubmit === 'function') e.form.requestSubmit();
      else {
        e.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', bubbles: true, cancelable: true}));
        e.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', bubbles: true, cancelable: true}));
      }
      return {ok: true};
    }
    const checked = inspect(action);
    if (!checked.ok) return checked;
    const e = get(action.elementId);
    if (action.action === 'click') { e.scrollIntoView({block: 'center'}); e.click(); return {ok: true}; }
    if (action.action === 'type') {
      e.focus();
      const text = String(action.text || '').slice(0, 12000);
      if (e.isContentEditable) {
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(e);
        selection.removeAllRanges(); selection.addRange(range);
        // execCommand preserves rich-editor undo/input handling where supported.
        if (!document.execCommand('insertText', false, text)) e.textContent = text;
      } else {
        const prototype = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        const setter = Object.getOwnPropertyDescriptor(prototype, 'value');
        if (setter && setter.set) setter.set.call(e, text); else e.value = text;
      }
      e.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: text}));
      e.dispatchEvent(new Event('change', {bubbles: true}));
      return {ok: true};
    }
    return {ok: false, reason: 'Unsupported DOM action.'};
  };
  window.__phormiAssistant = {version: 2, snapshot, inspect, execute};
})();
