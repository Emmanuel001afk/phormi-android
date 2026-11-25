#!/usr/bin/env python3
"""Verify an APK with apksigner and optionally pin its signer certificate SHA-256."""
from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

FINGERPRINT_LABEL = "certificate SHA-256 digest:"


def normalize_fingerprint(value: str) -> str:
    return re.sub(r"[^0-9a-f]", "", value.lower())


def fingerprints_from_output(output: str) -> list[str]:
    found: list[str] = []
    label = FINGERPRINT_LABEL.lower()
    for line in output.splitlines():
        label_index = line.lower().find(label)
        if label_index >= 0:
            digest_text = line[label_index + len(label) :]
            fingerprint = normalize_fingerprint(digest_text)
            if re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                found.append(fingerprint)
    return found


def verify_output(output: str, expected_sha256: str | None = None) -> list[str]:
    fingerprints = fingerprints_from_output(output)
    if not fingerprints:
        raise ValueError("apksigner did not report a signer certificate SHA-256 digest")
    if expected_sha256:
        valid_formats = (
            r"[0-9a-fA-F]{64}",
            r"[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){31}",
        )
        if not any(re.fullmatch(pattern, expected_sha256.strip()) for pattern in valid_formats):
            raise ValueError("expected SHA-256 fingerprint must contain 64 hexadecimal digits")
        expected = normalize_fingerprint(expected_sha256)
        if any(fingerprint != expected for fingerprint in fingerprints):
            raise ValueError(
                "APK signer certificate does not match the approved SHA-256 fingerprint: "
                + ", ".join(fingerprints)
            )
    return fingerprints


def find_apksigner(explicit: str | None = None) -> str | None:
    if explicit:
        return explicit
    in_path = shutil.which("apksigner")
    if in_path:
        return in_path
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = glob.glob(os.path.join(sdk, "build-tools", "*", "apksigner"))
        candidates.sort(
            key=lambda path: tuple(
                int(part) for part in Path(path).parent.name.split(".") if part.isdigit()
            )
        )
        if candidates:
            return candidates[-1]
    return None


def verify_apk(apk: Path, apksigner: str, expected_sha256: str | None = None) -> list[str]:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError(f"APK does not exist or is empty: {apk}")
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ValueError(f"apksigner verification failed for {apk}: {detail or 'invalid signature'}")
    return verify_output(result.stdout + "\n" + result.stderr, expected_sha256)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK file to verify")
    parser.add_argument("--apksigner", help="path to the Android apksigner executable")
    parser.add_argument(
        "--expected-sha256",
        help="approved signer certificate SHA-256 fingerprint (hex, with or without colons)",
    )
    args = parser.parse_args(argv)
    signer = find_apksigner(args.apksigner)
    if not signer:
        print("error: apksigner was not found; install Android Build Tools or pass --apksigner", file=sys.stderr)
        return 2
    try:
        fingerprints = verify_apk(args.apk, signer, args.expected_sha256)
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    for index, fingerprint in enumerate(fingerprints, 1):
        formatted = ":".join(fingerprint[offset : offset + 2] for offset in range(0, len(fingerprint), 2)).upper()
        print(f"Signer {index} certificate SHA-256: {formatted}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
