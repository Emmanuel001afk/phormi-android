# Browser security controls

## Cleartext navigation

All top-level HTTP destinations pass through `PhormiNetworkPolicy` before a page request
is allowed. The address bar, in-page links, restored/deferred tabs, incoming URLs,
AI-requested navigation, and Ghost mode use the same gate. `shouldOverrideUrlLoading`
handles interactive navigation, while `shouldInterceptRequest` is the pre-socket fallback
for app-originated loads and redirect targets. Public HTTP downloads use the same explicit
consent before the transfer is queued. A public HTTP host is remembered only after the user
explicitly chooses Continue. Private-network addresses remain available; the local-network
setting only controls their label in site information.

## Browser Lock PIN

New local PINs must be 8–64 ASCII digits. Phormi stores a random-salted
PBKDF2-HMAC-SHA-256 verifier instead of an unsalted fast hash. Four failed attempts are
allowed before lockout; subsequent delays increase exponentially up to five minutes.
Older SHA-256 PIN records are upgraded to the salted format after a correct verification.
Android device authentication remains the preferred alternative and recovery method.

The verifier and backoff policy are covered by `PhormiPinSecurityTest`. The local lock is
an app-level convenience control, not a replacement for the device's screen lock or a
hardware-backed credential vault.

## Browser AI sensitive fields

DOM-based browser AI and the accessibility-driven assistant share the sensitive-field
classifier. It considers input type, autocomplete, name/id, placeholder, accessibility
label/description and Android's password metadata, including numeric-password input
classes. Sensitive controls are omitted from the AI's control list, cannot be filled by
AI, and are blocked as coordinate tap targets. Password/PIN/OTP/CVV, API/private keys,
and card/account credentials must be entered by the user. Likely 12–19 digit payment-card
numbers are redacted from AI text context, including accessibility text exposed to the
assistant.

`PhormiSensitiveFieldPolicyTest` covers OTP, CVV, numeric-password and ordinary-field
cases. This is a conservative local filter, not a guarantee that arbitrary page text or
pixels contain no sensitive data; review the page before sharing it with an AI provider.
