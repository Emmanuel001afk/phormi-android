# Browser and keyboard repair notes

> This is the earlier repair milestone. Substantive feature work has reopened; see
> [Feature completion status](FEATURE_COMPLETION_STATUS.md) for the latest scope and validation.
> The user subsequently authorized merging PR #11 with the remaining limitations
> documented; see the current feature status for integration validation.

Baseline: `d2436ad06f6e2ef4240d9bf08c667a6273a8d92b` (original application).
The audit-only checkpoint is `3473c62`; it has the same application code as the baseline.
All work stays on `arena/d4887026-phormi-android`. Nothing has been merged into main or published to the store.

## Changes you will notice

### Browser
- Larger address/navigation touch targets and a readable address field. On phones, less-frequent controls live in the menu rather than competing with the address. Home remains available in the menu.
- Local address suggestions from saved pages and history; no remote keystroke autocomplete.
- A loading progress line, a stop/reload control and site-information entry point.
- Tab list/grid/carousel layouts use consistent dimensions and have a working Group button. Narrow screens and large fonts fall back to a single column.
- Bookmarks and Favorites lead to one Saved pages library. Existing favorites are retained. Hiding a home shortcut is explicitly separate from deleting a saved page.
- Browser surfaces use shared light/dark colors; system bars/cutouts/IME insets are handled on the principal screens.
- New installations default to Google search. Existing provider choices are retained. Phormi multi-engine search asks for explicit consent before sending a query to 11 providers. Results are published once collection completes, rather than repeatedly replacing the document while it is being read.
- Restored background documents defer page loading until needed. Metadata saves retain existing WebView snapshots instead of erasing them.

### Keyboard
- A single active renderer uses real language-specific character layouts, accented long-press popups and visible shifted letter labels.
- Shift and Backspace are beside the final letter row, with a larger Space key below. Tools and Mic are in the toolbar; the AI-expression pill no longer crowds word candidates.
- Phone/date/numeric fields have complete editing/action controls. Signed/decimal input flags are respected.
- Backspace handles surrogate pairs, combining marks, skin-tone emoji, flags and joined emoji. Android ICU boundaries supplement the pure-text deletion rules.
- Space dragging moves the cursor continuously. Editor actions and automatic capitalization use the application's input contract.
- Clipboard image taps send media, not its label; Pin/Unpin is exposed and pinned entries do not expire with ordinary clips.
- Picker results require an explicit **Insert picked** tap after returning to the keyboard. There is no background polling that can insert a delayed result into another application's field. Unused pending content expires after ten minutes.
- Voice typing has visible start/listening/processing/error states, cancellation and input-session ownership. After granting microphone permission for the first time, tap Mic again from the intended field.
- Resizing rebuilds key geometry while retaining the drag overlay. Wallpapers are decoded at bounded resolution and cached rather than decoded at full resolution on every panel rebuild.
- Theme choices use two columns with readable labels. The Rose/Berry naming mismatch is removed. Settings now use a compatible AppCompat theme.

## Privacy and correctness

- Camera/microphone/location need an origin-labelled **Allow once** decision, in addition to Android permission. Unsupported/insecure/cross-origin/background requests are denied. Navigation, tab switching, cancellation and destruction invalidate pending requests; runtime results cannot grant a replacement request.
- Existing tabs receive JavaScript, cookie and mixed-content preference changes when returning from settings. Reloading resets already-executing page content.
- Ghost fails closed when separate storage cannot be established. Its no-profile fallback clears cookies/storage on a fresh session and exit; supported profiles are kept through configuration changes. The address field and web editors request no personalized learning.
- Ghost now includes uploads, media permission prompts and fullscreen handling. Downloads explicitly offer to open outside Ghost, explaining the loss of private-session isolation; private cookies are not transferred.
- Keyboard online English suggestions are **off by default**. The optional Datamuse path has a corrected URL, bounded workers/cache, retry backoff and working callbacks. Password/private fields are excluded; cached prefixes are excluded from backups.
- Reflection-based instance lookups were replaced with explicit accessors. Correctly spelled dictionary words are no longer overridden by the manual correction map.

## Audit coverage and remaining limitations

The source audit is a baseline record, not a description of the repaired version.

| Baseline finding | Current treatment |
|---|---|
| B1/B2: origin consent and live security toggles | Implemented; real-site cross-origin and lifecycle testing beyond the smoke suite remains advisable. |
| B3/B4: browser and tab geometry | Reworked. Emulator smoke coverage checks that tab controls are attached/reachable. |
| B5: omnibox/loading/errors | Local suggestions, safer parsing, progress/stop, site information and durable TLS explanation added. Not full Chrome omnibox/security-interstitial parity. |
| B6: multi-engine search | Explicit consent, dependable default for new installs and no repeated intermediate document replacements. HTML-provider compatibility still depends on external providers; it is not a guaranteed search API. |
| B7: Ghost | Isolation failure handling, cleanup, private editor flags, uploads and fullscreen repaired. Private downloads deliberately require a disclosed normal-browser handoff, not a promise of full private-download parity. |
| B8: accounts/sync | Accounts clearly means website accounts. The unused snapshot helper no longer silently caps at five tabs; exports use device/tab IDs and imports reset stale tab metadata. **Authenticated cloud transport, conflict merging and end-to-end sync are not implemented.** |
| B9: session/performance | Background restoration is lazy at document-load level, snapshots are preserved. **A full live-tab discard/recreation system and Chrome-scale memory benchmarking remain future work**; live form state is not discarded to pretend memory was reclaimed. |
| B10/B11: library/theme/hierarchy | One Saved pages library, explicit shortcut hiding, shared theme tokens, clearer tool labels and less crowded phone controls. This is not a complete icon-system or architecture rewrite. |
| K1: renderer/languages/glide | Duplicate renderer removed; supported character layouts and accents wired into the service. **Glide recognition and CJK/Indic composition are not implemented.** Unsupported subtypes are no longer advertised. Character entry is not a claim of equal prediction quality across all supported languages. |
| K2–K8: editing/clipboard/Unicode/locale/network/layout | Implemented. Unit and emulator regression coverage added. |
| K9–K11: resize/voice/settings/retention | Implemented; real-device floating/inset behavior, recognizer providers and accessibility still need a broader hardware matrix. |

## Validation

Local checks:
- `python3 tools/audit_project.py`
- `python3 tools/audit_repairs.py`
- `git diff --check`

GitHub branch CI builds the committed app and runs JVM unit tests. A separate Android 11 / Pixel 2 emulator job exercises editing, emoji deletion, clipboard behavior, tab controls and settings startup. Branch CI has no store-publication path.

Initial findings during validation:
- The first compiler run caught a leftover reference to the removed stack-trace bookmark filter; it was corrected.
- Subsequent JVM tests and debug/release APK builds passed.
- The first emulator run passed four smoke tests and failed the phone/date reachability check. Focus-aware editor setup and explicit diagnostics were added before rerunning; see the final validation result below.

Final validation for application commit `d0f87b5`: **PASS**.
- 15 JVM unit tests; debug and release APK assembly: [build run](https://github.com/Emmanuel001afk/phormi-android/actions/runs/41360507706).
- All 6 Android emulator smoke tests: [interaction run](https://github.com/Emmanuel001afk/phormi-android/actions/runs/41360507448). The phone/date test passes after the fixture waits for window focus before requesting the IME.
- Both local source audits and `git diff --check`: PASS.
- [Debug APK artifact](https://github.com/Emmanuel001afk/phormi-android/actions/runs/41360507706/artifacts/13104883461).

This is automated coverage on one emulator configuration, not a claim of exhaustive physical-device, screen-reader, language-quality, network-provider or Android-version testing. Android lint was not part of these successful runs.

The debug APK is a test build, not a store release. Do not uninstall an existing signed installation just to bypass a signature mismatch: uninstalling can erase local browser data.

## Reverting changes

You can request either a complete rollback or a specific behavior/layout rollback. The original source remains in Git at the baseline above; no history was rewritten and saved user data was not intentionally migrated destructively.

Useful checkpoints:
- `3473c62`: original application plus audit only.
- `3d4aa6d`: core keyboard repair checkpoint.
- `22ba102`: browser safety/navigation/layout checkpoint.
- Later commits add integration corrections, session/picker safety and tests.

For a partial rollback, restore the relevant behavior against the baseline and retain unrelated safety fixes where possible. Some later integration changes span both browser and keyboard files, so blindly reverting a single intermediate commit is not always the safest partial rollback. A full return to the original application can be made on the same fixed branch without deleting its history.
