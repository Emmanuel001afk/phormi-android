# Phormi AEO membership adapter

Phormi now has a native Kotlin verifier for AEO Ed25519 membership credentials and UCCS `uccs/1` registration envelopes.

## Independence and trust boundary

Phormi remains an independent browser with its own identity, storage, runtime, permissions, and release cycle. This adapter does not replace the existing Central Hub API-key storage or Phormi's current browser/security flows. Membership proves AEO ecosystem registration only; it is not device/session authentication, capability authorization, or user consent.

The caller supplies the trusted issuer public keys and revocation checker. Verification is local; this code does not contact Central Hub or write to any database.

## Android support

The built-in JCA Ed25519 path is enabled only on Android API 33+. Phormi's minimum API is 26, so API 26–32 fail closed with no cryptographic downgrade. A maintained, independently reviewed provider is required before enabling older devices.

## Production requirements not yet met

This adapter does not yet verify root-signed trust bundles or persist monotonic trust-bundle versions. AEO root custody, key rotation/recovery, issuer/revocation distribution, and actual platform registration are still pending. No production private key or credential is included. Membership must never be used as a capability grant.

JVM tests cover valid credentials, tampered signatures, unknown issuer keys, revocation, wrong audience, API-level fail-closed behavior, and registration identity binding.


## Root-signed trust bundles

The adapter verifies AEO root-signed trust bundles against pinned Ed25519 root public keys, validates issuer-key records and credential revocation IDs, and enforces expiry and a caller-supplied minimum bundle version. The caller must persist the highest accepted version and pass it as `minimumVersion`; this verifier does not persist state. Root-key rotation/recovery and live issuer/revocation distribution remain production prerequisites. Android API levels below 33 fail closed because the platform Ed25519 provider is unavailable; no algorithm downgrade is used.
