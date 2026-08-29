# Phormi Unified Search Architecture

Phormi Search is an aggregation layer inside the browser. It is **not** a fallback chain and it is not the Central Hub.

## Behaviour

1. A query is sent to the configured search sources concurrently.
2. Each source contributes its own results independently.
3. Results are normalized to a common shape: title, URL, snippet, source, and source rank.
4. Duplicate URLs are merged rather than shown repeatedly.
5. A combined relevance score is calculated from independent source evidence.
6. Sources that fail for a particular query do not prevent other sources from contributing.
7. The user can still open an individual source directly from the results.

## Current browser sources

Google, Bing, DuckDuckGo, Brave, Yahoo, Ecosia, Mojeek, Startpage, Qwant, Yandex, and Swisscows are represented in the Android implementation. YouTube is available as a destination/search mode, but is not treated as a general web-index contributor.

## Separation rule

Phormi Search belongs to the **browser**. It is separate from the Central Hub/Mayobuild system. No Central Hub build logic is placed into the browser because of unified search.

## Cross-platform rule

The aggregation contract is platform-neutral. Android, Windows, Linux, macOS, and iOS/iPadOS implementations may provide different network/container code while keeping the same result model and ranking behaviour.
