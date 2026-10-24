# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests of the proof builder and the nonce handling, with a stand-in key."""

from __future__ import annotations

import unittest

from dpop_reference.jose import access_token_hash, jwk_thumbprint
from dpop_reference.proof import NonceCache, asks_for_nonce, authorization_header, build_proof
from tests.support import decode_proof
from tests.test_jose import RFC9449_JWK

URL = "https://ingest.profit-base.online/exchange/v1/me/stock?limit=5"


class StandInKey:
    """A key that records what it signs and answers a fixed signature."""

    def __init__(self, jwk: dict[str, str]) -> None:
        """Creates the stand-in with the JWK it reports."""
        self.jwk = jwk
        self.signed: list[bytes] = []

    def public_jwk(self) -> dict[str, str]:
        """Returns the configured JWK."""
        return dict(self.jwk)

    def thumbprint(self) -> str:
        """Returns the thumbprint of the configured JWK."""
        return jwk_thumbprint(self.jwk)

    def sign(self, data: bytes) -> bytes:
        """Records ``data`` and answers 64 fixed bytes."""
        self.signed.append(data)
        return bytes(range(64))

    def verify(self, data: bytes, signature: bytes) -> bool:
        """Accepts only the fixed signature."""
        return signature == bytes(range(64))

    def close(self) -> None:
        """Does nothing."""


class BuildProofTest(unittest.TestCase):
    """The header and claims of a proof."""

    def setUp(self) -> None:
        """Creates a stand-in key with RFC 9449's example public key."""
        self.key = StandInKey(RFC9449_JWK)

    def test_header_carries_the_public_key_only(self) -> None:
        """The header is exactly ``typ``, ``alg`` and the public ``jwk``."""
        header, _, _, _ = decode_proof(build_proof(self.key, "GET", URL))
        self.assertEqual({"typ": "dpop+jwt", "alg": "ES256", "jwk": RFC9449_JWK}, header)

    def test_token_endpoint_claims(self) -> None:
        """Without a token or nonce the proof has exactly ``jti``, ``htm``, ``htu`` and ``iat``."""
        _, claims, signing_input, signature = decode_proof(
            build_proof(self.key, "post", "https://profit-base.online/auth/realms/basetool/protocol/"
                        "openid-connect/token", issued_at=1_790_000_000, jti="fixed-id"))
        self.assertEqual(
            {"jti": "fixed-id", "htm": "POST", "iat": 1_790_000_000,
             "htu": "https://profit-base.online/auth/realms/basetool/protocol/openid-connect/token"},
            claims,
        )
        self.assertEqual([signing_input], self.key.signed)
        self.assertEqual(bytes(range(64)), signature)

    def test_resource_claims(self) -> None:
        """With a token and nonce the proof adds ``ath`` and ``nonce``; ``htu`` drops the query."""
        token = "Kz~8mXK1EalYznwH-LC-1fBAo.4Ljp~zsPE_NeO.gxU"
        _, claims, _, _ = decode_proof(
            build_proof(self.key, "GET", URL, access_token=token, nonce="eyJ7S_zG.eyJH0-Z.HX4w-7v"))
        self.assertEqual("https://ingest.profit-base.online/exchange/v1/me/stock", claims["htu"])
        self.assertEqual("fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo", claims["ath"])
        self.assertEqual(access_token_hash(token), claims["ath"])
        self.assertEqual("eyJ7S_zG.eyJH0-Z.HX4w-7v", claims["nonce"])
        self.assertIsInstance(claims["iat"], int)

    def test_every_proof_has_a_fresh_jti(self) -> None:
        """Two proofs never share a ``jti``, and it carries at least 128 bits."""
        ids = {decode_proof(build_proof(self.key, "GET", URL))[1]["jti"] for _ in range(100)}
        self.assertEqual(100, len(ids))
        self.assertTrue(all(len(value) >= 22 for value in ids))

    def test_private_key_material_is_refused(self) -> None:
        """A JWK with ``d`` never reaches a header."""
        with self.assertRaises(ValueError):
            build_proof(StandInKey({**RFC9449_JWK, "d": "secret"}), "GET", URL)

    def test_authorization_header(self) -> None:
        """The token travels under the DPoP scheme."""
        self.assertEqual("DPoP abc.def.ghi", authorization_header("abc.def.ghi"))


class NonceTest(unittest.TestCase):
    """Recognising a nonce challenge and remembering the latest nonce."""

    CHALLENGE = (
        'DPoP algs="ES256 ES384 ES512 RS256 RS384 RS512 PS256 PS384 PS512", '
        'error="use_dpop_nonce"'
    )

    def test_gateway_challenge(self) -> None:
        """A 401 with ``use_dpop_nonce`` and a nonce is a challenge; other 401s are not."""
        self.assertTrue(asks_for_nonce(401, {"www-authenticate": self.CHALLENGE,
                                             "dpop-nonce": "n1"}))
        self.assertFalse(asks_for_nonce(401, {"WWW-Authenticate": self.CHALLENGE}))
        self.assertFalse(asks_for_nonce(401, {
            "WWW-Authenticate": 'DPoP algs="ES256", error="invalid_dpop_proof"',
            "DPoP-Nonce": "n1"}))

    def test_authorization_server_challenge(self) -> None:
        """A 400 with the JSON error ``use_dpop_nonce`` and a nonce is a challenge."""
        self.assertTrue(asks_for_nonce(400, {"DPoP-Nonce": "n1"}, "use_dpop_nonce"))
        self.assertFalse(asks_for_nonce(400, {"DPoP-Nonce": "n1"}, "invalid_dpop_proof"))

    def test_cache_is_per_origin(self) -> None:
        """A nonce from one path serves the whole origin, and never another origin."""
        cache = NonceCache()
        cache.update(URL, {"DPoP-Nonce": "gateway-nonce"})
        cache.update("https://ingest.profit-base.online/exchange/v1", {"Other": "x"})
        self.assertEqual("gateway-nonce",
                         cache.get("https://INGEST.profit-base.online:443/exchange/v1/me/ships"))
        self.assertIsNone(cache.get("https://profit-base.online/auth/realms/basetool/protocol/"
                                    "openid-connect/token"))
        cache.update(URL, {"dpop-nonce": "newer"})
        self.assertEqual("newer", cache.get(URL))


if __name__ == "__main__":
    unittest.main()
