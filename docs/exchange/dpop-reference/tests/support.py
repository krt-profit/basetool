# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Shared checks for the backend tests: a proof verified by the independent P-256 verifier."""

from __future__ import annotations

import json
import unittest

from dpop_reference.base import DpopKey
from dpop_reference.jose import b64url_decode
from dpop_reference.proof import build_proof

from tests import p256


def public_point(jwk: dict[str, str]) -> tuple[int, int]:
    """Returns the affine point of a P-256 public JWK."""
    return (
        int.from_bytes(b64url_decode(jwk["x"]), "big"),
        int.from_bytes(b64url_decode(jwk["y"]), "big"),
    )


def decode_proof(proof: str) -> tuple[dict, dict, bytes, bytes]:
    """Splits a compact proof into header, claims, signing input and signature."""
    header, claims, signature = proof.split(".")
    return (
        json.loads(b64url_decode(header)),
        json.loads(b64url_decode(claims)),
        f"{header}.{claims}".encode("ascii"),
        b64url_decode(signature),
    )


class BackendChecks:
    """Checks every key backend must pass; mixed into a ``unittest.TestCase`` that sets ``key``."""

    key: DpopKey

    def test_public_jwk_is_a_public_p256_key(self: unittest.TestCase) -> None:
        """The JWK holds exactly the public P-256 members and lies on the curve."""
        jwk = self.key.public_jwk()
        self.assertEqual({"kty", "crv", "x", "y"}, set(jwk))
        self.assertEqual(("EC", "P-256"), (jwk["kty"], jwk["crv"]))
        self.assertEqual(32, len(b64url_decode(jwk["x"])))
        self.assertEqual(32, len(b64url_decode(jwk["y"])))
        self.assertTrue(p256.on_curve(public_point(jwk)))

    def test_signature_is_raw_and_verifies_independently(self: unittest.TestCase) -> None:
        """A signature is 64 bytes and the pure-Python verifier and the backend both accept it."""
        data = b"eyJ0eXAiOiJkcG9wK2p3dCJ9.eyJqdGkiOiJ4In0"
        signature = self.key.sign(data)
        self.assertEqual(64, len(signature))
        self.assertTrue(p256.verify_es256(public_point(self.key.public_jwk()), data, signature))
        self.assertTrue(self.key.verify(data, signature))

    def test_tampered_signature_and_data_fail(self: unittest.TestCase) -> None:
        """A changed byte in the signature or the data is refused by both verifiers."""
        data = b"signing input"
        signature = self.key.sign(data)
        broken = bytes([signature[0] ^ 0x01]) + signature[1:]
        point = public_point(self.key.public_jwk())
        self.assertFalse(p256.verify_es256(point, data, broken))
        self.assertFalse(self.key.verify(data, broken))
        self.assertFalse(p256.verify_es256(point, data + b"!", signature))
        self.assertFalse(self.key.verify(data + b"!", signature))

    def test_proof_round_trip(self: unittest.TestCase) -> None:
        """A proof built with the backend verifies against the key in its own header."""
        proof = build_proof(
            self.key,
            "post",
            "https://ingest.profit-base.online/exchange/v1/me/installation?x=1",
            access_token="token-value",
            nonce="server-nonce",
        )
        header, claims, signing_input, signature = decode_proof(proof)
        self.assertEqual("dpop+jwt", header["typ"])
        self.assertEqual("ES256", header["alg"])
        self.assertEqual(self.key.public_jwk(), header["jwk"])
        self.assertEqual("POST", claims["htm"])
        self.assertEqual(
            "https://ingest.profit-base.online/exchange/v1/me/installation", claims["htu"]
        )
        self.assertTrue(p256.verify_es256(public_point(header["jwk"]), signing_input, signature))
