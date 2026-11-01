# Reopened feature work — not merge-ready

PR #11 remains **draft**. The original application baseline is
`d2436ad06f6e2ef4240d9bf08c667a6273a8d92b`; the earlier repair milestone was
`d0f87b5`. The previous 15 JVM / 6 emulator passes do not validate these new changes.

## Implemented in this follow-up, validation in progress

### Real tab suspension

- A dormant tab holds metadata and a bounded navigation snapshot, **not a WebView**.
- Restoring a large session materializes only the active page; split/AI pages wake on demand.
- Eligible background pages are probed oldest-first, detached and destroyed above a
  configurable 2–8 live-page budget (default 3 or 6 based on Android's memory class).
  Android memory-pressure callbacks request a tighter budget.
- Active/split/AI pages, device-access grants, pending file pickers and fullscreen pages
  are protected. Boolean-only JavaScript probes conservatively protect loading pages,
  HTML audio/video playback, edited controls, editable areas, frames and custom/shadow
  components. No form values are returned to the Android process.
- Reopening recreates the configured browsing environment and navigation stack, with
  a scroll restoration attempt. A missing/oversize snapshot prevents suspension of a
  nonblank page. Snapshot storage remains local.
- This is **not Chrome-equivalent memory management**: WebView does not expose Chrome's
  complete renderer/session lifecycle or all activity signals. Web Audio, arbitrary
  JavaScript state, and dynamic page restoration need further real-site/device testing.
  A navigation snapshot is not a frozen DOM or a guarantee of preserving all page state.
  Protected tabs can exceed the budget. Renderer-process RSS has not been benchmarked.

### Offline glide

- Touch-path capture, visible trace, dictionary geometry matching, endpoint filtering,
  resampling, dynamic-time-warping reranking and frequency priors.
- Recognition runs off the UI thread. Results are bounded, confidence-filtered and
  shown as composing alternatives; Backspace can undo the composing gesture word.
- Pending work is invalidated on edits, cursor movement, field/language transitions
  and input dismissal. Password, URI and private/no-learning fields do not glide.
- Uses the existing licensed dictionaries: **English, French, Spanish, Portuguese,
  German, Italian, Indonesian, Turkish**. Not a claim of Gboard recognition quality.
- Long-press accents and ordinary taps retain their existing behavior. Glide has an
  on-device toggle. There is no cloud recognition or collection of gesture traces.

### Korean composition

- Standard two-set Hangul layout, shifted consonants/vowels, Unicode syllable assembly,
  compound vowels/finals and splitting a final consonant before a following vowel.
- An actual InputConnection composing region, not separate committed jamo buttons.
  Backspace replays the key buffer to reverse one composition step; separators and
  cursor/field transitions finish composition.
- Language selection now supports an explicit app preference or Android subtype.
- This is modern Hangul entry, **not Hanja conversion or a statistical Korean language model**.

## Still required before considering the user's broader request complete

1. Chinese Pinyin conversion and Japanese kana/kanji conversion, with appropriately
   licensed substantial lexicons, segmentation, candidate selection and editor tests.
2. Real Indic/Thai composition/input support and language-quality validation. These
   languages remain unadvertised rather than shipping character-button placeholders.
3. Broader glide accuracy, cancellation/privacy/editor-compatibility and device tests.
4. Navigation restoration and actual memory measurements across representative websites,
   low-RAM devices, media, Web Audio, unsaved editors, split view and AI tasks.
5. Resolve whether cross-device sync is still wanted. The user said the browser should
   need only device storage. **No external backend was provisioned. No account or cloud
   transport exists. Local serialization is not authenticated cloud sync.** If cloud sync
   remains required, its service/account/security design must be agreed explicitly.

“All general languages” is the requested goal, not a verified support claim. Native
speaker review and broader test coverage are still needed. Do not merge, release, or
label this PR complete on the strength of source audits or narrow smoke tests alone.

## Validation record

Pending CI for the new implementation. See the branch's Build Phormi APK and Android
interaction smoke test workflows; update this section with exact tested commits/results.
