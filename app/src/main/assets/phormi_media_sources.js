(() => {
  const found = new Map();
  const mediaExtension = /\.(mp4|webm|mkv|mov|mp3|m4a|ogg|oga|wav|flac|m3u8|mpd)(?:[?#]|$)/i;
  function add(raw, mime, label, protectedMedia) {
    if (!raw || found.size >= 80) return;
    try {
      const url = new URL(raw, document.baseURI).href;
      if (!/^(https?:|blob:)/i.test(url)) return;
      const previous = found.get(url);
      found.set(url, {url, mime: mime || previous?.mime || '', label: label || previous?.label || 'Page media',
        protectedMedia: !!protectedMedia || !!previous?.protectedMedia});
    } catch (_) {}
  }
  document.querySelectorAll('video,audio').forEach(media => {
    add(media.currentSrc || media.src, media.getAttribute('type'), media.getAttribute('aria-label') || media.tagName.toLowerCase(), !!media.mediaKeys);
    media.querySelectorAll('source').forEach(source => add(source.src, source.type, media.tagName.toLowerCase(), !!media.mediaKeys));
  });
  document.querySelectorAll('a[href]').forEach(link => {
    if (mediaExtension.test(link.href)) add(link.href, link.type, (link.innerText || 'Media link').trim().slice(0, 80), false);
  });
  try {
    performance.getEntriesByType('resource').forEach(entry => {
      if (mediaExtension.test(entry.name)) add(entry.name, '', 'Detected media request', false);
    });
  } catch (_) {}
  return JSON.stringify({sources: Array.from(found.values()), title: document.title});
})();
