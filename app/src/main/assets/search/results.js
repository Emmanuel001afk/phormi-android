(function () {
  'use strict';

  var form = document.getElementById('search-form');
  var input = document.getElementById('search-input');
  var clear = document.getElementById('clear-search');

  function syncClear() {
    if (clear && input) clear.hidden = input.value.length === 0;
  }

  if (input) {
    input.addEventListener('input', syncClear);
    syncClear();
  }

  if (clear && input) {
    clear.addEventListener('click', function () {
      input.value = '';
      syncClear();
      input.focus();
    });
  }

  // Route refinement through the same local navigation contract as the omnibox.
  // This also keeps GET form submissions working when WebView skips the override.
  if (form && input) {
    form.addEventListener('submit', function (event) {
      var query = input.value.trim();
      if (!query) {
        event.preventDefault();
        input.focus();
        return;
      }
      event.preventDefault();
      window.location.href = 'https://phormi.local/search?q=' + encodeURIComponent(query);
    });
  }

  // Progressive enhancement: without JavaScript every result remains visible.
  var results = Array.prototype.slice.call(document.querySelectorAll('#results .result'));
  var more = document.getElementById('more-results');
  if (more && results.length) {
    var pageSize = parseInt(more.getAttribute('data-page-size'), 10) || 10;
    var visible = Math.min(pageSize, results.length);
    results.forEach(function (item, index) { item.hidden = index >= visible; });
    more.hidden = visible >= results.length;
    more.addEventListener('click', function () {
      var next = Math.min(visible + pageSize, results.length);
      for (var i = visible; i < next; i++) results[i].hidden = false;
      visible = next;
      more.hidden = visible >= results.length;
      if (!more.hidden) more.focus();
    });
  }

  // Keep at most one result menu open and dismiss it with an outside tap or Escape.
  var menus = Array.prototype.slice.call(document.querySelectorAll('.result-menu'));
  menus.forEach(function (menu) {
    menu.addEventListener('toggle', function () {
      if (!menu.open) return;
      menus.forEach(function (other) { if (other !== menu) other.open = false; });
    });
  });
  document.addEventListener('click', function (event) {
    menus.forEach(function (menu) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') menus.forEach(function (menu) { menu.open = false; });
  });

  // Preserve the reader's place when the interim document is replaced by the final one.
  var query = input ? input.value : '';
  var key = 'phormi-search-scroll:' + query;
  try {
    if (document.body.classList.contains('is-ready')) {
      var saved = window.sessionStorage.getItem(key);
      if (saved !== null) window.scrollTo(0, parseInt(saved, 10) || 0);
      window.sessionStorage.removeItem(key);
    }
    window.addEventListener('pagehide', function () {
      if (document.querySelector('#results .result')) {
        window.sessionStorage.setItem(key, String(window.scrollY || window.pageYOffset || 0));
      }
    });
  } catch (ignored) {
    // Storage can be unavailable in restricted WebView profiles; scrolling still works.
  }
})();
