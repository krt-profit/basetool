#!/usr/bin/env python3
"""Verify that the realm's refresh-token DPoP binding actually holds.

`provision-keycloak-mobile-client.py` asserts the client's **configuration**: the profile exists,
the executor is right, `dpop.bound.access.tokens` is false. None of that proves the realm *behaves*
that way, and the difference is the entire security value of ADR-0131 / REQ-SEC-030.

This script measures the behaviour, end to end, the way the app performs it — authorization code
with PKCE S256 and `dpop_jkt`, then four token calls:

  1. code exchange with the key         -> expect a grant, `token_type: Bearer`
  2. refresh with the same key          -> expect a grant
  3. refresh with NO proof              -> expect a refusal
  4. refresh with a DIFFERENT key       -> expect a refusal

3 and 4 are the point. If either is granted, the binding is decoration: a refresh token lifted off
a device would work anywhere — exactly what a sender-constrained token exists to prevent. RFC 9700
requires a public client's refresh token to be rotated or sender-constrained, and rotation is off
realm-wide (REQ-SEC-012 / ADR-0019 amendment 4), so this binding is the only thing standing there.

The run also prints the `cnf.jkt` of both tokens. The refresh token must carry one and the access
token must not: Spring Security's bearer filter rejects a `cnf`-bound access token outright, so that
split is what lets the backend stay unchanged.

Run it against a **test** realm — it performs a real login and needs a password:

    python scripts/verify-dpop-binding.py \\
        --issuer http://127.0.0.1:18080/realms/iri \\
        --username test-member --password test-member-pw

Exit code 0 when all measurements match, 1 otherwise, so it can gate a release once the client is
provisioned in production.

Requires `requests` and `cryptography`.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import uuid

import requests
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils as asym_utils

HTTP_TIMEOUT_SECONDS = 15


def b64url(raw: bytes) -> str:
    """Encodes bytes the only way JOSE does.

    :param raw: bytes to encode
    :returns: base64url without padding
    """
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


class DpopKey:
    """A P-256 key that mints RFC 9449 proofs, standing in for the app's Keystore key."""

    def __init__(self) -> None:
        self.key = ec.generate_private_key(ec.SECP256R1())
        numbers = self.key.public_key().public_numbers()
        self.jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": b64url(numbers.x.to_bytes(32, "big")),
            "y": b64url(numbers.y.to_bytes(32, "big")),
        }

    def thumbprint(self) -> str:
        """The RFC 7638 thumbprint the authorization request sends as `dpop_jkt`.

        :returns: base64url SHA-256 over the canonical JWK
        """
        canonical = json.dumps(
            {"crv": "P-256", "kty": "EC", "x": self.jwk["x"], "y": self.jwk["y"]},
            separators=(",", ":"),
            sort_keys=True,
        )
        return b64url(hashlib.sha256(canonical.encode()).digest())

    def proof(self, method: str, url: str) -> str:
        """Mints one proof JWT for a single request.

        :param method: HTTP method, upper case (`htm`)
        :param url: target URL without query or fragment (`htu`)
        :returns: the serialised proof for the `DPoP` header
        """
        header = {"typ": "dpop+jwt", "alg": "ES256", "jwk": self.jwk}
        claims = {"jti": str(uuid.uuid4()), "htm": method, "htu": url, "iat": int(time.time())}
        signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}"
        der = self.key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256()))
        r, s = asym_utils.decode_dss_signature(der)
        return f"{signing_input}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def authorization_code(issuer: str, client_id: str, redirect_uri: str,
                       username: str, password: str, key: DpopKey, verifier: str) -> str:
    """Drives the browser half of the flow with a cookie jar.

    :param issuer: realm base URL
    :param client_id: the public client
    :param redirect_uri: a registered redirect; the response is read, never followed
    :param username: the test user
    :param password: their password
    :param key: the key whose thumbprint travels as `dpop_jkt`
    :param verifier: the PKCE verifier
    :returns: the authorization code
    """
    session = requests.Session()
    page = session.get(
        f"{issuer}/protocol/openid-connect/auth",
        params={
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "scope": "openid profile email roles",
            "state": "verify-dpop",
            "nonce": "verify-dpop",
            "code_challenge": b64url(hashlib.sha256(verifier.encode()).digest()),
            "code_challenge_method": "S256",
            "dpop_jkt": key.thumbprint(),
        },
        timeout=HTTP_TIMEOUT_SECONDS,
    )
    page.raise_for_status()

    # Keycloak marks its session cookies Secure. A browser still sends those to http://127.0.0.1,
    # because localhost counts as a secure context; `requests` implements no such exception, drops
    # them silently, and Keycloak then refuses the login POST with `cookie_not_found`. Cleared in
    # this client's own jar only — nothing about the server changes, and over https it is a no-op.
    for cookie in session.cookies:
        cookie.secure = False

    action = re.search(r'action="([^"]+)"', page.text)
    if not action:
        raise SystemExit("no login form in the authorization response — is the client public?")
    posted = session.post(
        action.group(1).replace("&amp;", "&"),
        data={"username": username, "password": password, "credentialId": ""},
        allow_redirects=False,
        timeout=HTTP_TIMEOUT_SECONDS,
    )
    location = posted.headers.get("Location", "")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code")
    if not code:
        raise SystemExit(f"login produced no code (HTTP {posted.status_code}) — check the "
                         f"credentials, and the realm's event log for the reason")
    return code[0]


def cnf_of(token: str) -> str:
    """Reads a token's `cnf.jkt` confirmation claim.

    :param token: a JWT
    :returns: the thumbprint it is bound to, or `<none>`
    """
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return (json.loads(base64.urlsafe_b64decode(payload)).get("cnf") or {}).get("jkt", "<none>")


def report(label: str, response: requests.Response, expect_grant: bool) -> bool:
    """Prints one measurement and says whether it matched.

    :param label: what was attempted
    :param response: the token endpoint's answer
    :param expect_grant: whether a grant is the correct outcome
    :returns: True when the outcome matched the expectation
    """
    is_json = response.headers.get("content-type", "").startswith("application/json")
    body = response.json() if is_json else {}
    granted = response.status_code == 200
    detail = body.get("token_type", "") if granted else f"{body.get('error')}: {body.get('error_description')}"
    matched = granted == expect_grant
    print(f"  {'ok  ' if matched else 'FAIL'} {label:<40} HTTP {response.status_code}  "
          f"{'GRANTED' if granted else 'REFUSED'}  {detail}")
    return matched


def main() -> int:
    """Runs the measurements.

    :returns: 0 when every one matched, 1 otherwise
    """
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--issuer", required=True,
                        help="realm base URL, e.g. http://127.0.0.1:18080/realms/iri")
    parser.add_argument("--client-id", default="basetool-android")
    parser.add_argument("--redirect-uri", default="http://127.0.0.1/callback",
                        help="must be registered on the client; the response is read, never followed")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()

    token_endpoint = f"{args.issuer}/protocol/openid-connect/token"
    key, other = DpopKey(), DpopKey()
    verifier = b64url(os.urandom(32))

    print(f"\nrealm    : {args.issuer}\nclient   : {args.client_id}\ndpop_jkt : {key.thumbprint()}\n")

    code = authorization_code(args.issuer, args.client_id, args.redirect_uri,
                              args.username, args.password, key, verifier)

    def call(form: dict, proof: str | None) -> requests.Response:
        """Posts one form to the token endpoint, with or without a proof."""
        return requests.post(token_endpoint, data=form,
                             headers={"DPoP": proof} if proof else {},
                             timeout=HTTP_TIMEOUT_SECONDS)

    granted = call({
        "grant_type": "authorization_code",
        "client_id": args.client_id,
        "code": code,
        "code_verifier": verifier,
        "redirect_uri": args.redirect_uri,
    }, key.proof("POST", token_endpoint))
    results = [report("1. code exchange, with the key", granted, expect_grant=True)]
    if granted.status_code != 200:
        return 1

    tokens = granted.json()
    access_cnf, refresh_cnf = cnf_of(tokens["access_token"]), cnf_of(tokens["refresh_token"])
    print(f"\n     access token  cnf.jkt = {access_cnf}")
    print(f"     refresh token cnf.jkt = {refresh_cnf}\n")
    if access_cnf != "<none>":
        print("  FAIL the access token is DPoP-bound — the backend's bearer filter will reject it")
    if refresh_cnf != key.thumbprint():
        print("  FAIL the refresh token is not bound to the key that requested it")
    results.append(access_cnf == "<none>")
    results.append(refresh_cnf == key.thumbprint())

    form = {"grant_type": "refresh_token", "client_id": args.client_id,
            "refresh_token": tokens["refresh_token"]}
    results.append(report("2. refresh, with the same key", call(form, key.proof("POST", token_endpoint)), True))
    results.append(report("3. refresh, NO proof at all", call(form, None), False))
    results.append(report("4. refresh, with a DIFFERENT key", call(form, other.proof("POST", token_endpoint)), False))

    ok = all(results)
    print(f"\n{'the binding holds' if ok else 'THE BINDING DOES NOT HOLD'}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
