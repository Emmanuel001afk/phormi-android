# Tab overview, layouts and groups

## Three genuinely different layouts

The overview no longer inflates one card for every mode:

- **List** — compact 64dp rows: monogram, one-line title, short host, inline group/close actions. Built for scanning many tabs quickly.
- **Grid** — dense tiles: larger monogram, two-line title, host/status, 2 columns on phones, 3 on small tablets, 4 on wide screens. Falls back to one column for large text.
- **Cards** — horizontal carousel: wider cards with a group color band, full address, status and explicit Group/Close/More actions. Built for focused review.

All three preserve sections, search, selection and group state. Side margins are 12dp; list gaps are 6dp and grid/card gaps are 8–10dp. The overview header is 52dp, search 44dp, layout row 40dp and footer 56dp, so more of the screen is actual tabs.

## Sections

1. **Current tab** — the active destination, highlighted with an accent border.
2. **Ghost tabs** — private tabs when present.
3. **Named groups** — in group-manager order, with color, collapse, open and manage controls.
4. **Other tabs** — ungrouped websites.
5. **New tabs** — destinations that have not been started.

Each tab appears once. Search matches title, address, host, group and environment. **Select all** applies only to visible results; collapsed group members are not selected accidentally.

## Grouping system

Groups are persisted by tab ID and support:

- name, task note and one of six colors;
- collapse/expand from the overview section header;
- group filter chips in the overview;
- assign one tab or bulk-assign selected tabs;
- remove one or many tabs from a group;
- add/remove members from a checkbox picker in the group manager;
- open the first live tab in a group;
- close all live tabs in a group;
- reorder groups up/down;
- delete a group without closing its tabs;
- automatic pruning of closed tab IDs.

The group manager shows color, live tab count, note and host previews. Empty groups remain available for adding tabs. Closed tabs are pruned when the browser closes tabs and when the overview/group manager reloads a non-empty snapshot.

## URL/title regression

A new WebView can report `about:blank` before the requested website commits. The browser records the requested destination before `loadUrl`, keeps the last committed navigation authoritative over that temporary blank value, and repairs stale `New Tab` titles from the host. Opening the overview flushes the snapshot immediately. Returning Home explicitly resets the destination, so Home is still a real blank tab.

## Browser chrome

- One top layer, **48dp** high, with 48dp touch targets.
- **Home is visible on phones and tablets whenever a website is open.** It is hidden only while already on Home.
- The optional bottom layer remains **48dp** and is off by default.
- Android status/navigation bars are system UI and are not included in app-toolbar heights.

## Validation

```bash
python3 tools/audit_tabs.py
python3 tools/audit_project.py
gradle testDebugUnitTest
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.uong.phormi.TabOverviewSmokeTest
```

Device checks cover ordered sections, stale-title repair, filtered bulk selection, distinct layout persistence, immediate URL snapshots, phone Home visibility and compact toolbar height.
