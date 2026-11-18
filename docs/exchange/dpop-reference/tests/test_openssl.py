# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests of the OpenSSL 3 backend; skipped where no OpenSSL 3 libcrypto can be loaded."""

from __future__ import annotations

import os
import stat
import tempfile
import unittest
from pathlib import Path

from dpop_reference.base import DpopKeyError
from dpop_reference.jose import jwk_thumbprint
from dpop_reference.openssl import OpenSslKey

from tests.support import BackendChecks


@unittest.skipUnless(OpenSslKey.is_available(), "no OpenSSL 3 libcrypto on this machine")
class OpenSslKeyTest(BackendChecks, unittest.TestCase):
    """The shared backend checks against a key generated in memory."""

    def setUp(self) -> None:
        """Generates a key."""
        self.key = OpenSslKey.generate()
        self.addCleanup(self.key.close)

    def test_thumbprint_matches_the_jwk(self) -> None:
        """The thumbprint is the RFC 7638 thumbprint of the reported JWK."""
        self.assertEqual(jwk_thumbprint(self.key.public_jwk()), self.key.thumbprint())

    def test_two_keys_differ(self) -> None:
        """Every generated key is new."""
        with OpenSslKey.generate() as other:
            self.assertNotEqual(self.key.thumbprint(), other.thumbprint())

    def test_closed_key_refuses_to_sign(self) -> None:
        """A closed key raises instead of crashing the process."""
        key = OpenSslKey.generate()
        key.close()
        with self.assertRaises(DpopKeyError):
            key.sign(b"x")


@unittest.skipUnless(OpenSslKey.is_available(), "no OpenSSL 3 libcrypto on this machine")
class OpenSslKeyFileTest(unittest.TestCase):
    """Storing the key in a PEM file."""

    def setUp(self) -> None:
        """Creates a scratch directory."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "keys" / "dpop.pem"

    def test_load_or_create_keeps_the_identity(self) -> None:
        """The first call creates the file, the second loads the same key."""
        with OpenSslKey.load_or_create(self.path) as first:
            thumbprint = first.thumbprint()
        self.assertTrue(self.path.is_file())
        with OpenSslKey.load_or_create(self.path) as second:
            self.assertEqual(thumbprint, second.thumbprint())
            self.assertTrue(second.verify(b"data", second.sign(b"data")))

    def test_an_existing_file_is_never_overwritten(self) -> None:
        """Saving onto an existing file fails and leaves it unchanged."""
        with OpenSslKey.load_or_create(self.path):
            pass
        before = self.path.read_bytes()
        with OpenSslKey.generate() as other, self.assertRaises(DpopKeyError):
            other.save(self.path)
        self.assertEqual(before, self.path.read_bytes())

    def test_garbage_is_refused(self) -> None:
        """A file that holds no private key is an error."""
        self.path.parent.mkdir(parents=True)
        self.path.write_bytes(b"-----BEGIN NOTHING-----\n-----END NOTHING-----\n")
        if os.name == "posix":
            self.path.chmod(0o600)
        with self.assertRaises(DpopKeyError):
            OpenSslKey.load(self.path)

    @unittest.skipUnless(os.name == "posix", "file modes are POSIX")
    def test_file_mode_is_0600(self) -> None:
        """The key file is readable and writable by its owner alone."""
        with OpenSslKey.load_or_create(self.path):
            pass
        self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))

    @unittest.skipUnless(os.name == "posix", "file modes are POSIX")
    def test_a_group_readable_file_is_refused(self) -> None:
        """A key file others can read is not loaded."""
        with OpenSslKey.load_or_create(self.path):
            pass
        self.path.chmod(0o640)
        with self.assertRaises(DpopKeyError):
            OpenSslKey.load(self.path)


if __name__ == "__main__":
    unittest.main()
