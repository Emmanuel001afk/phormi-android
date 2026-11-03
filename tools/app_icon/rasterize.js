#!/usr/bin/env node
/**
 * Rasterize the Phormi icon SVG sources into every PNG the app needs.
 *
 *   python3 tools/app_icon/generate_icon.py .      # writes the SVG sources
 *   node    tools/app_icon/rasterize.js .           # writes the PNG rasters
 *
 * PNGs produced:
 *   app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        (legacy square)
 *   app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  (legacy round)
 *   docs/branding/phormi_icon.png       1024px store / docs art
 *   docs/branding/phormi_icon_512.png   512px  store listing size
 *   docs/branding/adaptive_preview.png  432px  composited adaptive-icon preview
 *
 * Requires: npm install --no-save @resvg/resvg-js
 */
const fs = require('fs');
const path = require('path');

const { Resvg } = require('@resvg/resvg-js');

const repo = path.resolve(process.argv[2] || path.join(__dirname, '../..'));
const res = path.join(repo, 'app/src/main/res');
const brand = path.join(repo, 'docs/branding');

const read = (name) => fs.readFileSync(path.join(brand, name), 'utf8');
const png = (svg, width) =>
  new Resvg(svg, { fitTo: { mode: 'width', value: width } }).render().asPng();

/** Inline two SVG documents into one so the adaptive layers can be previewed together. */
function compositeBackgroundAndForeground() {
  const grab = (src) => ({
    defs: (src.match(/<defs>([\s\S]*?)<\/defs>/) || [, ''])[1],
    body: src
      .replace(/^[\s\S]*?<svg[^>]*>/, '')
      .replace(/<\/svg>\s*$/, '')
      .replace(/<defs>[\s\S]*?<\/defs>/, ''),
  });
  const bg = grab(read('adaptive_background.svg'));
  const fg = grab(read('adaptive_foreground.svg'));
  return (
    '<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">' +
    `<defs>${bg.defs}${fg.defs}</defs>${bg.body}${fg.body}</svg>`
  );
}

const square = read('legacy_square.svg');
const round = read('legacy_round.svg');

// density bucket -> launcher size in px (48dp baseline)
const buckets = [
  ['mdpi', 48],
  ['hdpi', 72],
  ['xhdpi', 96],
  ['xxhdpi', 144],
  ['xxxhdpi', 192],
];

for (const [density, px] of buckets) {
  const dir = path.join(res, `mipmap-${density}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), png(square, px));
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), png(round, px));
  console.log(`mipmap-${density}: ic_launcher.png / ic_launcher_round.png (${px}px)`);
}

fs.mkdirSync(brand, { recursive: true });
fs.writeFileSync(path.join(brand, 'phormi_icon.png'), png(read('phormi_icon.svg'), 1024));
fs.writeFileSync(path.join(brand, 'phormi_icon_512.png'), png(read('phormi_icon.svg'), 512));
fs.writeFileSync(path.join(brand, 'app_logo.png'), png(read('phormi_mark.svg'), 432));
fs.writeFileSync(path.join(brand, 'adaptive_preview.png'), png(compositeBackgroundAndForeground(), 432));
console.log('docs/branding: phormi_icon.png, phormi_icon_512.png, app_logo.png, adaptive_preview.png');
