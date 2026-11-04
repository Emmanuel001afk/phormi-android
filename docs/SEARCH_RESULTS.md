# Phormi Search results

Phormi Search is a normal, polished results page backed by the existing multi-provider
aggregation pipeline. It is not a raw dump of engine telemetry.

## What the user sees

- A compact Phormi header with an editable search field, clear button and submit button.
- A readable result list: site name and short breadcrumb, title, and a cleaned snippet.
- Source details tucked into a per-result menu instead of cryptic badges.
- A quiet result count, loading skeletons, and an interim “Updating results…” state.
- A distinct empty state for “no matches” versus “providers could not be reached”.
- “More results” pagination for long lists, with all results still present without JavaScript.
- Collapsed footer sections for “About these results” and “Try another search engine”.
- An optional AI overview that keeps its heading and disclaimer when the answer is injected.
- Light/dark colors from the same semantic palette as the rest of the app.

## What stayed the same

- The first-use consent gate still explains that the query is sent to multiple providers.
- The same 11 providers are queried independently; one failure does not cancel the others.
- Provider fetches keep the 6s connect / 8s read timeouts and 2 MB response cap.
- Each tab owns its search generation; late results cannot overwrite another tab or query.
- The document is still rendered at loading, first nonempty interim results, and final pass.
- Cached refresh, tab restoration, and local `phormi.local/search` / `phormi.local/refresh`
  navigation remain in the existing WebView flow.
- Optional AI synthesis still uses the top merged results as evidence.

## Implementation

- `PhormiSearchProviders.kt`: shared provider fetch/browse URLs.
- `PhormiSearchUrls.kt`: safe web URL filtering, canonical dedup identity, breadcrumbs.
- `PhormiSearchText.kt`: whitespace/snippet cleanup and navigation-title filtering.
- `PhormiSearchRanking.kt`: deterministic merge, agreement/query scoring, domain diversity.
- `PhormiSearchRenderer.kt`: pure Kotlin renderer; escapes provider-controlled text and
  performs single-pass template substitution.
- `app/src/main/assets/search/results.html|css|js`: packaged results document, theme tokens,
  progressive enhancement, result menus, pagination, and scroll preservation.
- `MainActivity.kt`: consent, fan-out, parsing, rendering, AI injection, and WebView routing.

## Safety

- Only `http:` and `https:` result URLs without user-info or whitespace are rendered.
- Query, title, snippet, source and AI text are escaped before insertion.
- The template does not load remote fonts, scripts, styles, or images.
- The script avoids `eval` and `innerHTML`; AI answers are injected with `textContent`.
- Provider navigation pages are filtered without blocking unrelated docs on brand domains.

## Validation

- `python3 tools/audit_search.py`
- `python3 tools/audit_project.py`
- `python3 tools/audit_app_ui.py`
- `node --check app/src/main/assets/search/results.js`
- `gradle testDebugUnitTest`
- `gradle lintDebug`
- Device/emulator pass: light/dark, loading/interim/final, empty/error, long lists,
  result menus, in-page search refinement, refresh, tab switching, and AI overview.

The sandbox used for this change does not include Java/Gradle/Android SDK or an emulator,
so compilation, unit tests, lint, and visual behavior are validated in CI rather than locally.
