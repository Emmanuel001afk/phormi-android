# Browser + keyboard repair notes

## Navigation, durable tabs and expiry

- Home is an explicit browser surface, independent of a previous WebView's loading state. Late start/finish/title callbacks cannot dismiss it or replace text being edited. Hidden pages cannot open popup windows, JavaScript dialogs or external apps over Home; popups require a user gesture.
- Versioned sessions retain each tab's stable identity, durable address, title, profile and timestamps, with bounded WebView history and a last-known-good backup. Restore constructs the complete session before saving anything. A transient `about:blank` cannot replace a known address.
- Native Phormi search results have a durable query address and a bounded, atomically written private HTML cache. They do not rely on WebView serializing generated HTML, and a late search cannot replace a subsequently opened page. Refresh reruns the query. Search does not silently trigger an AI request merely because a provider key is saved.
- Retention closes **individual idle tabs**, according to their own last-used time. It does not clear every tab because the session is old, and does not leave blank placeholders. Current/split/AI-in-use tabs are protected. Named-profile cleanup runs after restoration, with restored profiles protected.
- Renderer recovery preserves the tab/address; repeated crashes pause that page for explicit reload instead of repeatedly clearing/reloading the entire session.
- This cannot reconstruct URLs already erased by an older build. Existing history or an intact older session backup may be the only remaining source. WebView history is best-effort; third-party login/session behavior also depends on that site's cookies and profile support.

## AI that can work on the selected browser task

- Browser automation uses the selected tab's real DOM/control identifiers, not Android accessibility coordinates aimed at the app's chrome. Accessibility is not required for this native path.
- The planner has bounded context/output, strict action parsing, a 20-step limit, repetition checks, cancellation and observable status. Queue execution waits until the activity has actually reached `RESUMED`; checking inside `onResume()` was too early in Android's lifecycle.
- Before/after network waits and confirmations, the runner verifies foreground state, tab/view identity, navigation generation, browser/site locks and non-private eligibility. Leaving the browser cancels the active run. Work is not automatically resumed after interruption.
- Sensitive fields and their values are excluded. Submission/destructive controls require native confirmation. No page text is executed as a selector or injected as HTML. There is no guarantee of automating canvas editors, inaccessible cross-origin frames, CAPTCHA, every website or every language.
- Direct/custom provider transport, discovery, request/response parsing and the Central Hub route are explicit. Direct HTTP calls are cancellable; responses are bounded. Credentialed live provider behavior still needs testing with the user's own configured service. A successful tunnel registration is not a successful AI completion.
- Main provider secrets use the existing Android Keystore store. Request headers, task text, external pending inserts and personal learning stores are excluded from Android backup. The optional Pollinations **publishable** key remains in app-private keyboard preferences (masked in its UI); it is not claimed to be Keystore-encrypted.

### Typing help

Open **Write** in an ordinary text field, choose Grammar/Rewrite/Continue/Translate, review the returned draft, then explicitly insert/replace it. Selected text is preferred; otherwise only a bounded nearby paragraph is captured. Results cannot be applied to a different editor, changed selection or changed text. AI is unavailable in password/PIN/private editors. There is no request for every keystroke and no automatic send.

## Keyboard behavior

- Same-process editor context reads run on a background worker behind a bounded, revision-checked cache; blocking remote reads on the shared browser/IME UI looper can otherwise time out or ANR. Edits remain ordered and snapshots cannot overwrite newer typing/cursor context.
- Direct native input handling replaces reflected typing hooks and process-wide polling. The browser and IME share the process without global view/cursor mutation.
- Unicode word boundaries, offline starter lexicons, optional Android spell checking, per-language learned words, prediction caching and conservative correction. Repeated deliberate words are learned after roughly three uses/retentions. Backspace immediately after a correction restores the original spelling and does not immediately re-correct it. Reset clears both saved and cached learning.
- Personal learning/clipboard capture/AI are suppressed in private fields and keyboard incognito mode; Ghost explicitly sets the no-personalized-learning flag in its address editor and webpage input connections. No glide typing or Gboard-size dictionary is claimed.
- Phone/PIN/number/date rows have real action/delete keys, locale decimal/sign handling and meaningful Send/Search/Next/Done behavior. Multiline editors retain newline behavior. Eligible candidate rows keep a stable height while typing; letter/number rows fill their available area and docked keys reserve system navigation/caption-bar insets. All panels use the same bounded viewport; short landscape, split-screen, floating and large-text behavior still need physical-device QA.
- Advertised system subtypes are limited to layouts that are actually implemented (Latin variants plus Arabic, Hebrew, Greek, Russian and Ukrainian). Unsupported CJK/Indic/Thai/Vietnamese composition is not advertised as finished. Additional accented letters are available by long-press; complex-script composition/glide remain future work.

### Voice and emoji

- Voice uses Android speech recognition, explicit microphone permission, selected locale and editor-bound insertion. Permission return/delayed start cannot redirect transcription into another editor. Stop/error/end-of-input clean up the recognizer. A device may have no speech provider or no offline language model; this is reported, not silently treated as working dictation. Voice text may be processed by the device's configured recognition service.
- The emoji catalog is lazy and platform/glyph-filtered, using Android ICU plus supported sequences. A recycled grid avoids allocating thousands of buttons. Search uses names/aliases and country names; recents keep only explicit eligible insertions, with optional skin tones. Emoji availability depends on Android fonts/version.
- Custom-image emoji generation is explicit, one request at a time, cancellable and bounded by byte/dimension limits. With an image-service key it uses Pollinations; without one it clearly labels the simple local emoji renderer. Cloud failures are not silently replaced by a local image pretending to be AI.
- The Studio/media activities use the correct AppCompat theme. External selections return through a short-lived editor-bound queue, with a manual **Insert here** step. Images require the receiving editor to accept that MIME type; plain text editors receive a clear unsupported result, not an implicit share into another app.
- Browser website microphone/camera consent is separate from the keyboard's Android microphone permission.

## Downloads and media

- HTTP(S) downloads use a three-worker foreground service, saved progress, identity encoding, validated range/total responses, bounded restart/retry, resumable private destination state and final publication. Missing destinations, write failures and cancellation are handled without marking partial files completed. RFC 5987 Unicode filenames, plus signs, MIME inference, safe extensions and bounded names are handled.
- Cross-origin redirects cannot carry copied Cookie/Authorization headers. Observed page-request headers are bounded and origin-isolated. Signed URLs must remain unmodified and can expire; a resume after expiry may require going back to the original site.
- Page media discovery includes real video/audio/source elements and observed media requests, deduplicates results, filters schemes and ignores segment spam. It cannot discover every encrypted, iframe, JavaScript-generated or obfuscated source.
- User-selected `blob:`/`data:` downloads use a nonce-scoped native bridge and private staging, then publish to public Downloads. Active blob transfers are bounded to 512 MiB aggregate. Data links are limited to 16 MiB of encoded URI text; larger files should use HTTP/blob links. Closing the originating page stops unfinished bridge transfers. MediaSource streaming blobs and DRM are not converted/bypassed.
- API 26–28 storage permission is requested when downloading, not broadly at startup. API 29+ uses MediaStore. Completed items share the normal open/delete lifecycle.
- Authenticated direct media and HLS/DASH can be **played online** through Media3. Full offline HLS/DASH assembly and DRM downloading are **not implemented**; the source dialog says so instead of calling a playlist a downloaded video.

## Appearance

- A distinct bundled Phormi P-mark is used untinted in the browser; it is original vector artwork, not a trademark-clearance claim.
- Shared Iris/Tide/Fern/Rose/Amber accents, readable light-mode accent colors, foreground contrast, optional daily rotation and keyboard follow-browser mode.
- Six bundled offline wallpapers plus bounded custom-photo loading. Browser/IME settings have insets-aware layouts; Home scrolls and uses density-independent shortcut columns and larger touch targets. Phone browser chrome prioritizes the address/search field instead of cramming every desktop control into it.

## Validation and remaining manual matrix

`tools/audit_project.py` now runs actual structural/DOM tests; old source-substring checks are not evidence of functionality. Build/test/lint/emulator setup and artifact instructions are in `BUILD_NOTES.md`.

Robolectric regressions cover session migration/backup/retention, Home callbacks/restoration/lifecycle gating, generated search cache, Unicode typing/learning, numeric actions/panel sizing, protocol parsing and download policy/filenames. Node tests exercise the injected assistant and media discovery DOM adapters. The optional real API 35 IME smoke covers ordinary typing/correction undo, emoji insertion, writing-panel navigation, phone keys and Home-field IME visibility. Consult the actual workflow result before claiming that any run passed.

Before production rollout, check on real devices:

1. API 26–28, 29–34, 35–36; portrait/landscape, gesture/three-button navigation, split/floating IME, large fonts and low memory. Test Phormi's keyboard in Phormi and two unrelated apps.
2. Type a Home URL while restored/background pages finish; force-stop and reopen; preserve real addresses/titles/profile identities. Expire only one old tab alongside recent/protected tabs. Restore a generated search page without network access.
3. Correct/undo `teh`; deliberately repeat a nonstandard/multilingual spelling three times; restart the IME, verify learning and verify incognito/password isolation. Exercise IME Send/Search/Next/newline.
4. Voice allow/deny/unavailable provider, cancellation, app/editor switch and locale change. Emoji search/tones/recents, image-capable/plaintext editors, Studio/media return and private-field restrictions.
5. Normal/large/authenticated downloads, valid/invalid/ignored ranges, expired links, disconnect/retry, pause/resume/cancel/delete, app recreation, destination deletion and low storage. Test blob/data and confirm HLS/DASH/DRM limitation messages.
6. Live direct/custom and Central Hub providers: selected-tab actions, same-field writing preview/apply, malformed responses, offline/timeouts/cancellation, tab/navigation switches, submission confirmation and locked/private targets. This requires the user's configured endpoint; no keys are requested in chat or written to test fixtures.

No physical-device verification, full offline stream support, complex-script composition or universal provider/website compatibility should be inferred from a successful build.
