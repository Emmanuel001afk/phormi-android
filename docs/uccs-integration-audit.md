# UCCS integration audit — Phormi

**Audit status:** source-inspected; documentation only  
**Audited base:** `main` at `3eda85f1b4d9e468b0cafac5aa4bb4bf05567468`  
**UCCS target:** AEO Universal Cross-Platform Coordination System, draft 0.1  
**Important:** Phormi remains an independent browser application. This audit does not make it a UCCS authority or change runtime behavior.

## 1. Existing Phormi integration observed

- `PhormiHubStore.kt` stores a Central Hub caller API key using an Android Keystore AES-GCM key and encrypted SharedPreferences payload.
- The Android manifest declares network and application-specific permissions and disables Android backup for the application.
- The inspected Hub store is specifically a credential-storage helper for calling Central Hub. It is not a general UCCS membership identity, peer-discovery client, capability manifest or authorization engine.
- No general UCCS adapter or signed AEO membership proof was established by the inspected files.

The existing Central Hub credential must not be reinterpreted as an AEO membership credential. The existing encrypted storage pattern may be reusable for future locally held UCCS secrets, subject to a separate review of key lifecycle and credential format.

## 2. UCCS compatibility mapping

| UCCS concern | Existing Phormi support | Audit result |
|---|---|---|
| Independent app identity | Phormi is a separate Android app/package | Keep separate |
| AEO membership assertion | Not found in inspected files | Missing |
| Publisher trust proof | Not found in inspected files | Missing |
| Device identity for UCCS | Not found in inspected files | Missing in inspected scope |
| Credential protection | Android Keystore AES-GCM for Central Hub caller key | Reusable security pattern, not UCCS identity |
| Capability manifest | Not found in inspected files | Missing |
| Peer discovery | Not found in inspected files | Missing |
| External capability invocation | Not found in inspected files | Missing in inspected scope |
| Consent / authorization | Application permissions exist; no UCCS peer-grant model found | Must be designed per capability |
| Central Hub integration | Encrypted Hub caller key storage | Existing bilateral integration only |

## 3. Required Phormi integration approach

1. Implement UCCS as an optional adapter within Phormi; do not change Phormi's identity, browser data model, storage boundaries or release lifecycle.
2. Create a distinct platform identity and verify signed AEO membership using the common UCCS trust model.
3. Publish only explicitly selected Phormi capabilities. Do not expose browser history, tabs, bookmarks, cookies, downloads, passwords or private browsing data as general capabilities.
4. Any capability involving browsing, navigation, page content, file access or user data must have narrowly defined scopes and clear user consent.
5. Keep the Central Hub API key separate from UCCS publisher keys, membership proofs, device identity and peer grants.
6. Store any UCCS private keys or tokens in Android Keystore-backed storage with explicit rotation, revocation and recovery behavior.
7. Do not require Central Hub or a hosted registry for Phormi's ordinary browser operation.

## 4. Security and compatibility gates

Phormi must not be marked UCCS-conformant until tests demonstrate:

- Cryptographic validation of membership and publisher identity.
- Separation of Hub credentials from UCCS credentials.
- Explicit allowlisting of externally advertised capabilities.
- Least-privilege scopes and user consent for sensitive browser operations.
- Authenticated, expiring, revocable and replay-resistant requests.
- No remote access to browsing history, cookies, credentials or local files without a separately authorized operation.
- Registry outage does not impair normal browser use.
- Independent app data and database boundaries remain intact.

## 5. Scope and limitations

This audit inspected:

- `app/src/main/java/com/uong/phormi/PhormiHubStore.kt`
- `app/src/main/AndroidManifest.xml`

It did not audit every browser feature, network client, storage implementation, database, or test. No runtime files were changed.
