import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const script = fs.readFileSync(new URL('../../app/src/main/assets/phormi_media_sources.js', import.meta.url), 'utf8');
function scan({media = [], links = [], resources = []} = {}) {
  const document = {baseURI: 'https://site.example/watch', title: 'Source page',
    querySelectorAll(selector) { return selector === 'video,audio' ? media : links; }};
  return JSON.parse(vm.runInNewContext(script, {document, URL, performance: {getEntriesByType() {return resources;}}}));
}
test('actual video sources and observed HLS requests are discoverable without segment spam', () => {
  const video = {currentSrc: 'blob:https://site.example/id', tagName: 'VIDEO', getAttribute() {return null;},
    querySelectorAll() {return [{src: '/video.mp4?token=keep', type: 'video/mp4'}];}};
  const result = scan({media: [video], resources: [{name: 'https://cdn.example/playlist.m3u8?auth=keep'}, {name: 'https://cdn.example/segment.m4s'}, {name: 'https://cdn.example/app.js'}]});
  assert.deepEqual(result.sources.map(s => s.url), ['blob:https://site.example/id', 'https://site.example/video.mp4?token=keep', 'https://cdn.example/playlist.m3u8?auth=keep']);
});
test('a protected player stays marked as protected through duplicate discoveries', () => {
  const video = {currentSrc: 'https://cdn.example/protected.mp4', tagName: 'VIDEO', mediaKeys: {}, getAttribute() {return null;}, querySelectorAll() {return [];}};
  const result = scan({media: [video], resources: [{name: video.currentSrc}]});
  assert.equal(result.sources.length, 1);
  assert.equal(result.sources[0].protectedMedia, true);
});
test('non-web and executable sources are not offered as downloadable media', () => {
  const links = [{href: 'javascript:alert(1).mp4', innerText: 'bad'}, {href: 'file:///secret.mp4', innerText: 'bad'}, {href: '/movie.webm', innerText: 'Movie'}];
  assert.deepEqual(scan({links}).sources.map(s => s.url), ['https://site.example/movie.webm']);
});
