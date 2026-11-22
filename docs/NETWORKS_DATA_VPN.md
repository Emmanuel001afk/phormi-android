# Networks, local pages, data saver and VPN

## Opening LAN pages (the transfer-server case)

`PhormiNetworkPolicy` decides what the browser may open. It is deliberately explicit
instead of relying on a hidden framework default.

| Address | Behaviour |
| --- | --- |
| `https://…` | Always allowed. |
| `http://localhost`, `127.0.0.1`, `::1` | Allowed, marked "Not secure". |
| `http://192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`, `169.254.x.x`, `100.64–127.x.x` | Allowed, marked "Not secure". |
| `http://name.local`, `.lan`, `.home.arpa`, `.internal` | Allowed, marked "Not secure". |
| `http://singlename:8080` (no dot) | Allowed, treated as an on-network device. |
| `http://public-site.com` | **Asks once**: "Open this address over HTTP?" with Continue / Cancel. Continue remembers the host until you clear it. |
| `file:`, `javascript:`, `intent:`, empty | Refused with an explanation. |

Settings → **Networks and data** has:

- **Flag local-network HTTP pages** (default on) — labels LAN pages as a local network in
  site information. Unticking it cannot make a LAN address unusable; it only removes the
  convenience label.
- **Allow again: HTTP sites I blocked** — clears the remembered hosts.

The OS-level `network_security_config.xml` previously set `cleartextTrafficPermitted=false`
for everything, so WebView could not even start a LAN request. Cleartext is now
permitted at the platform layer and the *browser policy above* is what gates each load.
This is the same shape as Chrome: platform allows it, the browser warns and asks.

The security chip in the address bar shows `!` with "Not secure" for any HTTP page and
the site-information sheet explains the connection state.

## Data saver

WebView has no compression proxy, so Phormi does not claim one and shows no invented
percentage. Data saver is a real local subset, chosen from **Features → Data saver**
(the browser owns the setting; Browser settings only reports the current mode).

| Mode | What changes |
| --- | --- |
| **Off** | Normal browsing. `LOAD_DEFAULT`, autoplay allowed, images load. |
| **Standard** | Prefer the disk cache (`LOAD_CACHE_ELSE_NETWORK`), and media requires a tap before it starts. Images still load. |
| **Text only** | Standard + image requests are **refused before download**, plus a CSS guard so existing documents hide image boxes without collapsing the layout. |

The old `always` value written by earlier builds is still read as Text only.

### Three details that make it real

- **Images are refused at request time.** `WebSettings.blockNetworkImage` is deprecated
  and does nothing from API 30, so the previous build hid images that had already been
  downloaded — saving layout, not data. `shouldInterceptRequest` now returns a 1×1 PNG
  for image requests, so the bytes never cross the network. Main-frame documents are
  never blocked, and `data:`/`blob:` images are left alone because they cost nothing.
- **A refresh still refreshes.** `LOAD_CACHE_ELSE_NETWORK` will happily serve an expired
  copy forever. Tapping ↻ (or pulling) reloads with the cache bypassed and restores the
  saving preference on the next page start, so a stale page is never un-refreshable.
- **The mode change says what it costs.** Android cannot un-register a document-start
  script and cannot undo an image block in a document that already rendered, so
  switching mode reloads the current page. The chooser states that instead of letting
  the user discover a jump. Picking the mode already active does not reload.

The mode applies to every live tab immediately and to new tabs on creation. It never
proxies, rewrites or uploads browsing data.

## VPN

The VPN engine question has a clear technical answer:

- **OpenVPN inside the browser is not a smaller version of OpenVPN.** A VPN needs a TUN
  interface and the Android `VpnService` role, which belongs to an app, not to a
  WebView. Phormi can own that role, but that is "Phormi the VPN app", not "the browser".
- **OpenVPN is free and open source, but it still needs a server.** The app is free;
  a VPN tunnel always terminates at some server (VPN Gate in this case, or your own).
  Putting the client inside Phormi removes the extra installed app, not the server.
- Phormi therefore drives the documented `de.blinkt.openvpn` remote API
  (`OpenVpnController`), supports all three declared client packages, and validates at
  runtime whether an embedded engine actually ships in the APK
  (`PhormiVpnEngineValidator`, visible in the VPN screen).

Browser flow added for convenience: **Features → Reconnect last VPN server** opens the
VPN screen and starts the saved profile immediately (`VpnActivity.EXTRA_AUTO_CONNECT`).
Choosing a server still uses the full list screen.

## Validation

```bash
python3 tools/audit_project.py   # no plain-HTTP endpoints, cleartext policy is explicit
gradle testDebugUnitTest         # PhormiNetworkPolicyTest
```
