# Feature scope, remaining work and validation

On 2026-11-02 the user explicitly requested merging PR #11 despite the outstanding
feature limitations below. The earlier draft hold is superseded; the merged tree
must pass validation before merging. The original application baseline is
`d2436ad06f6e2ef4240d9bf08c667a6273a8d92b`; the earlier repair milestone was
`d0f87b5`. The previous 15 JVM / 6 emulator passes do not validate these new changes.

## Implemented in this follow-up, with the validation below

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
   Initial auto-capitalization also needs a focused regression: the CAP_SENTENCES
   fixture attempt still produced lowercase. The passing glide smoke deliberately uses
   a plain-text field and expects lowercase; it does not validate automatic capitalization.
4. Navigation restoration and actual memory measurements across representative websites,
   low-RAM devices, media, Web Audio, unsaved editors, split view and AI tasks.
5. Resolve whether cross-device sync is still wanted. The user said the browser should
   need only device storage. **No external backend was provisioned. No account or cloud
   transport exists. Local serialization is not authenticated cloud sync.** If cloud sync
   remains required, its service/account/security design must be agreed explicitly.

“All general languages” is the requested goal, not a verified support claim. Native
speaker review and broader test coverage are still needed. Merge authorization does not mean the outstanding feature work is complete.
Do not label these features complete on the strength of narrow smoke tests alone.

## Validation record

Application/test commit: **`fd2eeed9e529f83d9aa22d32dbb295550f37d17c`**.

- **PASS — 29 JVM tests**, including gesture geometry/native-key handling and all
  11,172 modern Hangul syllables through the composer.
- **PASS — debug and release APK builds**, source/resource audit and repair guardrails:
  https://github.com/Emmanuel001afk/phormi-android/actions/runs/41363551635
- **PASS — 12 Android emulator tests, 0 failures, 0 skipped** (API 30):
  https://github.com/Emmanuel001afk/phormi-android/actions/runs/41363551691
  Coverage includes a real swipe producing a composing word and undo, Korean syllable
  composition/backspace/cursor movement, 20-tab lazy restoration, actual WebView
  destruction/recreation, local-HTTP navigation history restoration, and edited-form
  protection. HTML media safety is simulated; it is not an end-to-end playback test.
- The earlier **`d7ece5a`** passed the CodeQL/Android-lint workflow:
  https://github.com/Emmanuel001afk/phormi-android/actions/runs/41362745128
  This is not a claim that those checks ran on the final `fd2eeed` changes.
- Debug APK artifact (test build, not a store release):
  https://github.com/Emmanuel001afk/phormi-android/actions/runs/41363551635/artifacts/13105414655
  Do not uninstall an existing installation just to bypass a signing mismatch: that
  could erase local browser data.

The Korean integration initially had a duplicate lifecycle override, fixed in
`d7ece5a`. The glide UI test initially expected capitalization inconsistent with its
observed editor behavior; the final test asserts plain-text lowercase entry. Neither
failure is hidden by disabling tests. The final 12-test run includes both new input
features and all prior smoke tests. No main-branch merge or store publication occurred.

No broad physical-device, native-language-quality, recognition-accuracy, memory/RSS,
Web Audio, or Chrome/Gboard comparison benchmark has been performed. The user has now authorized merging this limited scope after validation.

## Merge integration with main (PR #12)

`main` advanced while PR #11 was in draft, with overlapping browser/keyboard repairs.
The merge keeps PR #11's tested nullable-WebView lifecycle, gesture decoder, Hangul
composer, private-editor guards and Ghost fail-closed isolation. It retains main's
VPN/service cleanup, News/sticker entry points, origin-only favicon requests, equal
search-provider weighting, cached search refresh, find navigation, network-error UI,
fullscreen orientation behavior, disabled backup and HTTPS/loopback network policy.
Normal location permission persistence/reset is combined with PR #11's active exact-
HTTPS-origin validation; private-mode permissions are never persisted. SSL exceptions
remain blocked. Both opt-in online dictionary settings now address a single preference.
The subtype audit now checks the actual expanded layouts rather than assuming all
keyboards are Latin-only. Main's historical audit/fix documentation remains available.

Fresh build/unit/emulator validation is required for this combined tree; earlier
results above describe the pre-merge application and are not substituted for it.

The loopback TLS exceptions explicitly disable subdomain matching as required by
Android lint. The media viewer retains MODIFY_AUDIO_SETTINGS for its volume controls;
other unnecessary permissions removed by main remain removed.

PR #13 landed during the final merge checks, so its changes were also integrated:
global/per-site desktop settings (without waking dormant WebViews), context-menu
link destinations, suggestion/autocorrect fixes, and normal/Ghost bulk-tab selection.
The responsive themed tab cards and Ghost isolation/private-editor flags are retained.
Ghost title updates share the same WebChromeClient as permission/file/fullscreen
handling, rather than replacing that security-aware client. A bulk-selection result
regression test was added; the combined tree requires a new validation run.
