import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_apk_signature import fingerprints_from_output, normalize_fingerprint, verify_output


class VerifyApkSignatureTest(unittest.TestCase):
    def test_reads_and_normalizes_sha256_certificate_fingerprints(self):
        fingerprint = "a" * 64
        formatted = ":".join(fingerprint[offset : offset + 2] for offset in range(0, 64, 2))
        output = f"Signer #1 certificate SHA-256 digest: {formatted.upper()}\n"
        self.assertEqual([fingerprint], fingerprints_from_output(output))
        self.assertEqual("aabbcc", normalize_fingerprint("AA:BB:CC"))

    def test_requires_a_signer_certificate(self):
        with self.assertRaisesRegex(ValueError, "did not report"):
            verify_output("Verified using v2 scheme (APK Signature Scheme v2): true")

    def test_can_pin_the_signer_fingerprint(self):
        fingerprint = "a" * 64
        output = f"Signer #1 certificate SHA-256 digest: {fingerprint}"
        self.assertEqual([fingerprint], verify_output(output, fingerprint))
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify_output(output, "b" * 64)

    def test_expected_fingerprint_must_be_valid_sha256_hex(self):
        output = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        with self.assertRaisesRegex(ValueError, "64 hexadecimal digits"):
            verify_output(output, "abc")
        with self.assertRaisesRegex(ValueError, "64 hexadecimal digits"):
            verify_output(output, "a" * 63 + "z")


if __name__ == "__main__":
    unittest.main()
