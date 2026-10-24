# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Lucas Greuloch
"""Tests of the encoding helpers against published vectors."""

from __future__ import annotations

import os
import unittest

from dpop_reference.jose import (
    access_token_hash,
    b64url_decode,
    b64url_encode,
    der_to_raw,
    ec_public_jwk,
    jwk_thumbprint,
    normalize_htu,
    raw_to_der,
)

RFC7638_N = (
    "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECP"
    "ebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY"
    "368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0f"
    "M4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw"
)

RFC9449_JWK = {
    "kty": "EC",
    "x": "l8tFrhx-34tV3hRICRDY9zCkDlpBhF42UQUfWVAWBFs",
    "y": "9VE4jf_Ok_o64zbTTlcuNJajHmt6v9TDVrU0CdvGRDA",
    "crv": "P-256",
}


class Base64UrlTest(unittest.TestCase):
    """base64url without padding (RFC 7515 §2)."""

    def test_known_values(self) -> None:
        """Encodes the RFC 4648 test strings without padding and with the URL-safe alphabet."""
        self.assertEqual("", b64url_encode(b""))
        self.assertEqual("Zg", b64url_encode(b"f"))
        self.assertEqual("Zm8", b64url_encode(b"fo"))
        self.assertEqual("Zm9v", b64url_encode(b"foo"))
        self.assertEqual("-_-_", b64url_encode(bytes([0xFB, 0xFF, 0xBF])))

    def test_round_trip_never_pads(self) -> None:
        """Every length round-trips and no output carries ``=``, ``+`` or ``/``."""
        for length in range(0, 41):
            data = os.urandom(length)
            encoded = b64url_encode(data)
            self.assertNotRegex(encoded, r"[=+/]")
            self.assertEqual(data, b64url_decode(encoded))

    def test_decode_accepts_padding_and_rejects_other_alphabets(self) -> None:
        """Padding is tolerated on input; the standard alphabet and stray characters are not."""
        self.assertEqual(b"f", b64url_decode("Zg=="))
        for bad in ("+_-_", "-/-_", "Zm 9", "Zm9v!", "Z", "Zg=a"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                b64url_decode(bad)


class ThumbprintTest(unittest.TestCase):
    """RFC 7638 JWK thumbprints."""

    def test_rfc7638_example(self) -> None:
        """The RSA example of RFC 7638 §3.1, including its ignored ``alg`` and ``kid``."""
        jwk = {"kty": "RSA", "n": RFC7638_N, "e": "AQAB", "alg": "RS256", "kid": "2011-04-29"}
        self.assertEqual("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", jwk_thumbprint(jwk))

    def test_rfc9449_example_key(self) -> None:
        """The P-256 key of RFC 9449's examples has the ``jkt`` those examples show."""
        self.assertEqual("0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I", jwk_thumbprint(RFC9449_JWK))

    def test_member_order_and_extra_members_do_not_matter(self) -> None:
        """Only the required members count, whatever the input order."""
        reordered = {"y": RFC9449_JWK["y"], "crv": "P-256", "x": RFC9449_JWK["x"], "kty": "EC",
                     "kid": "k1", "use": "sig"}
        self.assertEqual(jwk_thumbprint(RFC9449_JWK), jwk_thumbprint(reordered))

    def test_incomplete_or_unknown_keys_are_refused(self) -> None:
        """A missing required member or an unknown ``kty`` is an error, not a guess."""
        with self.assertRaises(ValueError):
            jwk_thumbprint({"kty": "EC", "crv": "P-256", "x": RFC9449_JWK["x"]})
        with self.assertRaises(ValueError):
            jwk_thumbprint({"kty": "oct", "k": "AAAA"})

    def test_ec_public_jwk(self) -> None:
        """Coordinates become a four-member JWK; a wrong length is refused."""
        jwk = ec_public_jwk(b64url_decode(RFC9449_JWK["x"]), b64url_decode(RFC9449_JWK["y"]))
        self.assertEqual(RFC9449_JWK, jwk)
        with self.assertRaises(ValueError):
            ec_public_jwk(b"\x01" * 31, b"\x01" * 32)


class AccessTokenHashTest(unittest.TestCase):
    """The ``ath`` claim (RFC 9449 §4.2)."""

    def test_rfc9449_example(self) -> None:
        """The access token of RFC 9449 §7.1 hashes to the ``ath`` shown there."""
        self.assertEqual(
            "fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo",
            access_token_hash("Kz~8mXK1EalYznwH-LC-1fBAo.4Ljp~zsPE_NeO.gxU"),
        )

    def test_fixed_token(self) -> None:
        """A fixed token hashes to its known base64url SHA-256, without padding."""
        self.assertEqual("n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg", access_token_hash("test"))


class HtuTest(unittest.TestCase):
    """``htu`` normalisation: scheme, host, non-default port and path; never query or fragment."""

    def test_normalisation(self) -> None:
        """Each URL maps to the ``htu`` the gateway compares with."""
        cases = {
            "https://ingest.profit-base.online/exchange/v1/me/stock?limit=5#top":
                "https://ingest.profit-base.online/exchange/v1/me/stock",
            "HTTPS://Ingest.Profit-Base.Online:443/exchange/v1":
                "https://ingest.profit-base.online/exchange/v1",
            "http://localhost:18081/exchange/v1/":
                "http://localhost:18081/exchange/v1/",
            "http://localhost:80/exchange/v1": "http://localhost/exchange/v1",
            "https://ingest.profit-base.online": "https://ingest.profit-base.online/",
            "https://[::1]:8443/exchange/v1": "https://[::1]:8443/exchange/v1",
            "https://ingest.profit-base.online/exchange/v1/schemas/a%2Fb":
                "https://ingest.profit-base.online/exchange/v1/schemas/a%2Fb",
            "https://profit-base.online/auth/realms/basetool/protocol/openid-connect/token":
                "https://profit-base.online/auth/realms/basetool/protocol/openid-connect/token",
        }
        for url, expected in cases.items():
            with self.subTest(url=url):
                self.assertEqual(expected, normalize_htu(url))

    def test_unusable_urls_are_refused(self) -> None:
        """Relative URLs, other schemes, user info and invalid ports are errors."""
        for url in ("/exchange/v1", "ftp://host/x", "https://user:secret@host/x",
                    "https://host:99999/x", "https:///x"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                normalize_htu(url)


class SignatureFormTest(unittest.TestCase):
    """DER and the JWS ``r || s`` form of an ECDSA signature."""

    def test_der_with_padded_r_and_short_s(self) -> None:
        """A high-bit ``r`` loses its sign byte and a short ``s`` is left-padded."""
        r = bytes([0x80]) + bytes(range(1, 32))
        s = bytes(range(1, 32))
        der = bytes([0x30, 2 + 33 + 2 + 31, 0x02, 33, 0x00]) + r + bytes([0x02, 31]) + s
        raw = der_to_raw(der)
        self.assertEqual(r + b"\x00" + s, raw)
        self.assertEqual(der, raw_to_der(raw))

    def test_round_trip(self) -> None:
        """Random raw signatures survive the trip through DER."""
        for _ in range(50):
            raw = os.urandom(64)
            self.assertEqual(raw, der_to_raw(raw_to_der(raw)))

    def test_malformed_der_is_refused(self) -> None:
        """Truncated, trailing, negative or oversized values are errors."""
        good = raw_to_der(bytes([0x7F] * 64))
        for bad in (good[:-1], good + b"\x00", b"\x31" + good[1:],
                    bytes([0x30, 6, 0x02, 1, 0x80, 0x02, 1, 0x01]),
                    bytes([0x30, 38, 0x02, 33]) + b"\x01" * 33 + bytes([0x02, 1, 0x01])):
            with self.subTest(bad=bad.hex()), self.assertRaises(ValueError):
                der_to_raw(bad)


if __name__ == "__main__":
    unittest.main()
