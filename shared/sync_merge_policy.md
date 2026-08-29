# Phormi Sync Merge Policy

Phormi uses authenticated, opt-in synchronization. A device must never treat a
sync payload as proof of identity; account authentication belongs to the future
sync service.

## State that can sync

- Open tabs and their titles/URLs
- Active tab
- Bookmarks and Quick Access (once represented by the shared model)
- Search provider selection
- Theme mode and daily accent setting
- Wallpaper reference (not necessarily the image bytes)
- Browser preferences

## State that stays local by default

- Website cookies
- Website passwords
- Website authentication/session tokens
- Downloaded files
- Private/incognito browsing state
- Browser-lock secrets and device credentials

## Conflict handling

1. Each device has a random installation `deviceId`.
2. Each mutable record should eventually carry a stable ID and `updatedAt`.
3. Tabs merge by stable tab ID. If the same tab was independently changed,
   the newest authenticated update wins.
4. Bookmarks merge by stable bookmark ID.
5. Preferences use last-write-wins with server-normalized timestamps.
6. Deletions must eventually use tombstones rather than silently disappearing,
   otherwise an offline device could resurrect deleted data.

## Important boundary

This document does not implement a cloud server. It defines the data boundary
so Android, Windows, macOS, Linux and future iOS implementations can share the
same sync format when a backend is connected.
