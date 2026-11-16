# Phormi AEO membership adapter

Phormi now has a native Kotlin verifier for AEO Ed25519 membership credentials, UCCS `uccs/1` registration envelopes, and root-signed trust bundles.

## Independence and trust boundary

Phormi remains an independent browser with its own identity, storage, runtime, permissions, and release cycle. This adapter does not replace existing Central Hub API-key storage or Phormi's current browser/security flows. Membership is not device/session authentication, capability authorization, or user consent.

## Android support

Built-in JCA Ed25519 is enabled only on Android API 33+. API 26–32 fail closed with no cryptographic downgrade. A separately reviewed maintained provider is required before enabling older devices.

## Trust bundles

The adapter verifies root-signed trust bundles against pinned Ed25519 root public keys, validates issuer-key records and credential revocation IDs, and enforces expiry and a caller-supplied minimum version. Phormi must persist the highest accepted version in Phormi-owned secure storage and pass it as `minimumVersion`. The verifier performs no network or database access. Root custody, rotation/recovery, live issuer/revocation distribution, and initial platform registration remain production prerequisites.

## Validation

JVM tests cover membership and registration verification, trust-bundle signature and rollback checks, and API-level fail-closed behavior. CI and device checks must pass before merge.
