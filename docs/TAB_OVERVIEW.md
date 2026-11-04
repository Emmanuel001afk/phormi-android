# Tab overview and browser chrome

## Overview structure

The overview is a sectioned workspace rather than a flat list of repeated rows:

1. **Current tab** — the active destination, highlighted with an accent border.
2. **Ghost tabs** — private tabs when present in the supplied snapshot.
3. **Named groups** — in the order maintained by the group manager; empty groups stay in Groups.
4. **Other tabs** — ungrouped websites.
5. **New tabs** — destinations that have not been started.

Each tab appears once. Cards show a repaired title, address, current/environment status, and explicit Group/Close/More actions. List, Grid and horizontal Cards layouts all preserve sections. Search matches title, address, host, group and environment. **Select all** applies only to the current search results, so hidden tabs are not closed accidentally.

Groups can be created and assigned without leaving the overview. A grouped tab can also be removed from its group.

## URL/title regression

A new WebView can report `about:blank` before the requested website commits. The browser now records the requested destination before `loadUrl`, keeps the last committed navigation authoritative over that temporary blank value, and repairs stale `New Tab` titles from the host. Opening the overview flushes the snapshot immediately instead of waiting for the normal debounce. Returning Home explicitly resets the destination, so Home is still a real blank tab.

## Chrome

- One top layer, **48dp** high, with 48dp touch targets.
- **Home is visible on phones and tablets whenever a website is open.** It is hidden only while already on Home.
- The optional bottom layer remains **48dp** for accessible touch targets, but uses flat buttons instead of large rings. It is off by default; enable it in Browser settings → Controls.
- Android's status/navigation bars are system UI and are not included in those app-toolbar heights.

## Validation

```bash
python3 tools/audit_tabs.py
python3 tools/audit_project.py
gradle testDebugUnitTest
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.uong.phormi.TabOverviewSmokeTest
```

Device checks cover ordered sections, stale-title repair, filtered bulk selection, layout persistence, immediate URL snapshots, phone Home visibility and compact toolbar height.
