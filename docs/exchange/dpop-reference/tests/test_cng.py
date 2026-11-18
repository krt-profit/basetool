# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests of the Windows CNG backend; skipped on other systems and, for the TPM, without one."""

from __future__ import annotations

import unittest
import uuid

from dpop_reference.base import DpopKeyError
from dpop_reference.cng import PLATFORM_PROVIDER, SOFTWARE_PROVIDER, CngKey

from tests.support import BackendChecks


def _test_name() -> str:
    """Returns a key name no other key uses."""
    return f"dpop-reference-test-{uuid.uuid4()}"


class _PersistedKeyChecks(BackendChecks):
    """Checks of a persisted CNG key; mixed into a test case that sets ``provider``."""

    provider: str

    def setUp(self) -> None:
        """Creates a key under a fresh name and schedules its deletion."""
        self.name = _test_name()
        self.key = CngKey.create(self.name, self.provider)
        self.addCleanup(self._delete)

    def _delete(self) -> None:
        """Deletes the test key, whatever the test left open."""
        self.key.close()
        key = CngKey.open(self.name, self.provider)
        if key is not None:
            key.delete()

    def test_key_lives_in_the_requested_provider(self) -> None:
        """The key reports the provider it was created in."""
        self.assertEqual(self.provider, self.key.provider)

    def test_private_key_cannot_be_exported(self) -> None:
        """The provider refuses to hand out the private key."""
        self.assertFalse(self.key._exports_private_key())

    def test_reopening_by_name_keeps_the_identity(self) -> None:
        """Opening the persisted key again gives the same thumbprint, and it still signs."""
        thumbprint = self.key.thumbprint()
        self.key.close()
        with CngKey.open(self.name, self.provider) as again:
            self.assertEqual(thumbprint, again.thumbprint())
            self.assertTrue(again.verify(b"data", again.sign(b"data")))
        with CngKey.open_or_create(self.name, self.provider) as existing:
            self.assertEqual(thumbprint, existing.thumbprint())

    def test_an_existing_name_is_never_overwritten(self) -> None:
        """Creating a key under a taken name fails."""
        with self.assertRaises(DpopKeyError):
            CngKey.create(self.name, self.provider)

    def test_delete_removes_the_key(self) -> None:
        """After deletion the name opens nothing."""
        self.key.delete()
        self.assertIsNone(CngKey.open(self.name, self.provider))


@unittest.skipUnless(CngKey.is_available(), "Windows CNG is available on Windows only")
class SoftwareProviderTest(_PersistedKeyChecks, unittest.TestCase):
    """A key in the Microsoft Software Key Storage Provider."""

    provider = SOFTWARE_PROVIDER

    def test_export_policy_is_zero(self) -> None:
        """The key is created with an export policy that allows no export."""
        self.assertEqual(0, self.key.export_policy())


@unittest.skipUnless(
    CngKey.is_available() and CngKey.platform_provider_available(),
    "no TPM-backed Platform Crypto Provider on this machine",
)
class PlatformProviderTest(_PersistedKeyChecks, unittest.TestCase):
    """A key in the TPM through the Microsoft Platform Crypto Provider."""

    provider = PLATFORM_PROVIDER


@unittest.skipUnless(CngKey.is_available(), "Windows CNG is available on Windows only")
class OpenTest(unittest.TestCase):
    """Looking up keys that do not exist."""

    def test_a_missing_key_opens_as_none(self) -> None:
        """No provider holds a fresh name."""
        self.assertIsNone(CngKey.open(_test_name()))
        self.assertIsNone(CngKey.open(_test_name(), SOFTWARE_PROVIDER))


if __name__ == "__main__":
    unittest.main()
