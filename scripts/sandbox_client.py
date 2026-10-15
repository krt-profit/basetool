"""The device login and the DPoP-bound gateway call of a local exchange sandbox client (REQ-XCH-029).

`scripts/sandbox-smoke.py` and `scripts/sandbox-load.py` share it. A session signs one member in
through the device grant of a third-party client exactly as a member would in a browser, keeps the
DPoP-bound token, and calls the gateway over one kept-alive TLS connection with a fresh proof per
request, retrying once on a nonce challenge. It signs with the DPoP reference in
`docs/exchange/dpop-reference`, standard library only.
"""

from __future__ import annotations

import base64
import html
import http.client
import http.cookiejar
import json
import pathlib
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "docs" / "exchange" / "dpop-reference"))

from dpop_reference import (  # noqa: E402
    DpopKey,
    NonceCache,
    asks_for_nonce,
    authorization_header,
    build_proof,
)

PUBLIC_KEYCLOAK = "http://host.docker.internal:18080"
REALM = "/auth/realms/iri"
SCOPES = ("offline_access exchange.connect exchange.blueprints.read exchange.blueprints.write "
          "exchange.stock.read exchange.stock.write exchange.hangar.read exchange.hangar.write "
          "exchange.demand.read exchange.orgunits.read exchange.orgstock.read")
DEFAULT_CA = ROOT / "docker" / "test-tls" / "basetool-test-ca.crt"
USER_AGENT = "BasetoolSandboxClient/1.0.0 (+https://krt-profit.github.io/basetool/)"


def raising_step(label: str, ok: bool, detail: str = "") -> None:
    """Raises `RuntimeError` for a failed step and ignores a passed one."""
    if not ok:
        raise RuntimeError(f"{label}: {detail}" if detail else label)


@dataclass
class Answer:
    """One gateway answer.

    Attributes:
        status: the HTTP status.
        body: the decoded JSON body, empty when there is none or it is no JSON.
        headers: the answer's headers, names lower-cased.
        size: the body's length in bytes.
        nonce_retries: how many nonce challenges the call answered before this.
    """

    status: int
    body: dict
    headers: dict[str, str] = field(default_factory=dict)
    size: int = 0
    nonce_retries: int = 0

    @property
    def code(self) -> str:
        """The problem's `code`, or an empty string."""
        value = self.body.get("code") if isinstance(self.body, dict) else None
        return value if isinstance(value, str) else ""


class SandboxSession:
    """One installation of a sandbox client, signed in as one member."""

    def __init__(self, key: DpopKey, *, gateway: str = "https://127.0.0.1:11262",
                 keycloak: str = "http://127.0.0.1:18080", ca: pathlib.Path = DEFAULT_CA,
                 client: str = "sandbox-client",
                 step: Callable[[str, bool, str], None] = raising_step,
                 user_agent: str = USER_AGENT) -> None:
        """Keeps the settings and the installation key.

        Args:
            key: the installation's DPoP key.
            gateway: the gateway's local origin.
            keycloak: the local origin of the sandbox Keycloak.
            ca: the CA that signed the gateway's certificate.
            client: the third-party client id the member signs in to.
            step: receives each login check as label, outcome and detail.
            user_agent: the `User-Agent` of every gateway call.
        """
        self.key = key
        self.gateway = gateway.rstrip("/")
        self.local_keycloak = keycloak.rstrip("/")
        self.client = client
        self.step = step
        self.user_agent = user_agent
        self.jar = http.cookiejar.CookieJar()
        session = self

        class Rewrite(urllib.request.HTTPRedirectHandler):
            """Follows Keycloak's redirects to the advertised host on the local port."""

            def redirect_request(self, req, fp, code, msg, headers, newurl):
                """Rewrites the redirect target to the local Keycloak and keeps its cookies.

                Keycloak marks its cookies `Secure` for a request to 127.0.0.1 even over plain
                HTTP, so the cookie jar would drop them on the redirect it answers with.
                """
                session.unsecure_cookies()
                return super().redirect_request(req, fp, code, msg, headers, session.local(newurl))

        self.browser = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), Rewrite())
        self.tls = ssl.create_default_context(cafile=str(ca))
        self.nonces = NonceCache()
        self.token: dict | None = None
        self.subject: str | None = None
        self.connection: http.client.HTTPSConnection | None = None

    def local(self, url: str) -> str:
        """Maps the advertised Keycloak origin to the local one."""
        return url.replace(PUBLIC_KEYCLOAK, self.local_keycloak)

    def unsecure_cookies(self) -> None:
        """Clears the `Secure` flag of every cookie so plain-HTTP Keycloak requests carry them."""
        for cookie in self.jar:
            cookie.secure = False

    def page(self, url: str, data: dict | None = None) -> tuple[str, str]:
        """Loads a Keycloak page as a browser would, submitting a form when data is given.

        It asks for HTML like a browser: Keycloak answers a form post to the device page with the
        device authorization endpoint's JSON unless the request prefers HTML.
        """
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        self.unsecure_cookies()
        request = urllib.request.Request(
            self.local(url), data=body, headers={"Accept": "text/html,application/xhtml+xml"})
        with self.browser.open(request) as answer:
            return answer.geturl(), answer.read().decode("utf-8", "replace")

    @staticmethod
    def form(text: str) -> tuple[str | None, dict]:
        """Reads a page's form action and its hidden and text fields."""
        match = re.search(r'<form[^>]*action="([^"]+)"', text)
        action = html.unescape(match.group(1)) if match else None
        fields = {}
        for tag in re.findall(r"<input[^>]*>", text):
            name = re.search(r'name="([^"]+)"', tag)
            value = re.search(r'value="([^"]*)"', tag)
            kind = re.search(r'type="([^"]+)"', tag)
            if name and (kind is None or kind.group(1) in ("hidden", "text")):
                fields[name.group(1)] = html.unescape(value.group(1)) if value else ""
        return action, fields

    def post_form(self, url: str, data: dict, dpop: str | None = None) -> tuple[int, dict]:
        """Posts a form to Keycloak and reads the JSON answer."""
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if dpop:
            headers["DPoP"] = dpop
        request = urllib.request.Request(
            self.local(url), data=urllib.parse.urlencode(data).encode(), headers=headers)
        try:
            with urllib.request.urlopen(request) as answer:
                return answer.status, json.load(answer)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def login(self, user: str, password: str, scopes: str = SCOPES) -> None:
        """Runs the device grant: authorise, sign in and consent, then poll for the token.

        Each check goes to the session's `step`: the phishing warnings on the code-entry and the
        consent page, the user code on the consent page, the token and its DPoP binding.

        Args:
            user: the member's username.
            password: the member's password.
            scopes: the scopes the client asks for.
        """
        base = PUBLIC_KEYCLOAK + REALM + "/protocol/openid-connect"
        status, device = self.post_form(
            base + "/auth/device", {"client_id": self.client, "scope": scopes})
        self.step("device authorization answers", status == 200, str(status))
        url, text = self.page(device["verification_uri"])
        self.step("the code-entry page warns about device-code phishing",
                  'id="krt-device-phishing-warning"' in text, url)
        consent_seen = False
        for _ in range(6):
            action, fields = self.form(text)
            if "device_user_code" in fields:
                fields["device_user_code"] = device["user_code"]
            elif "password" in text and "username" in fields:
                fields.update({"username": user, "password": password})
            elif 'name="accept"' in text:
                self.step("the consent page warns about device-code phishing",
                          'id="krt-device-consent-warning"' in text, url)
                shown = re.search(r'id="krt-device-user-code"[^>]*>([^<]*)<', text)
                self.step("the consent page shows the device login's user code",
                          shown is not None and shown.group(1).strip() == device["user_code"],
                          shown.group(1) if shown else "no code on the page")
                consent_seen = True
                fields["accept"] = "yes"
            else:
                break
            url, text = self.page(urllib.parse.urljoin(url, action), fields)
        self.step("the device login reached the consent page", consent_seen, url)
        done = "success" in text.lower() or "erfolgreich" in text.lower()
        self.step("the member signs in and consents on the device page", done, url)
        token_url = self.local(base + "/token")
        for _ in range(10):
            status, answer = self.post_form(
                base + "/token",
                {"grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                 "device_code": device["device_code"], "client_id": self.client},
                build_proof(self.key, "POST", token_url))
            if status == 200:
                self.token = answer
                break
            time.sleep(2)
        self.step("the token endpoint issues a token", self.token is not None, str(status))
        claims = self.claims()
        self.subject = claims.get("sub")
        self.step("the token is DPoP-bound to the installation key",
                  claims.get("cnf", {}).get("jkt") == self.key.thumbprint(), "")
        audience = claims["aud"] if isinstance(claims["aud"], list) else [claims["aud"]]
        self.step("the token names the gateway audience", "basetool-ingest" in audience, "")

    def claims(self) -> dict:
        """Decodes the current access token's claims without checking its signature."""
        return json.loads(base64.urlsafe_b64decode(
            self.token["access_token"].split(".")[1] + "=="))

    def refresh(self, margin: float = 30.0) -> None:
        """Renews the access token with the refresh token once it expires within `margin` seconds.

        Raises:
            RuntimeError: if Keycloak refuses the refresh.
        """
        if self.claims().get("exp", 0) - time.time() > margin:
            return
        token_url = PUBLIC_KEYCLOAK + REALM + "/protocol/openid-connect/token"
        status, answer = self.post_form(
            token_url,
            {"grant_type": "refresh_token", "refresh_token": self.token["refresh_token"],
             "client_id": self.client},
            build_proof(self.key, "POST", self.local(token_url)))
        if status != 200:
            raise RuntimeError(f"token refresh refused: {status} {answer.get('error', '')}")
        self.token = answer

    def request(self, method: str, path: str, body: dict | None = None, write: bool = False,
                idempotency_key: str | None = None) -> Answer:
        """Calls the gateway with a fresh DPoP proof, retrying once on a nonce challenge.

        An access token about to expire is renewed first, and a connection the gateway closed
        while idle is opened again once, with a new proof.

        Args:
            method: the HTTP method.
            path: the path below the gateway origin, query included.
            body: the JSON body, if any.
            write: whether to send an `Idempotency-Key`; a random one unless one is given.
            idempotency_key: the `Idempotency-Key` to send.

        Returns:
            The gateway's answer.
        """
        url = self.gateway + path
        self.refresh()
        access = self.token["access_token"]
        if write and idempotency_key is None:
            idempotency_key = str(uuid.uuid4())
        data = json.dumps(body).encode() if body is not None else None
        retries = 0
        reconnected = False
        while True:
            headers = {
                "Authorization": authorization_header(access),
                "DPoP": build_proof(self.key, method, url, access_token=access,
                                    nonce=self.nonces.get(url)),
                "Accept": "application/json",
                "User-Agent": self.user_agent,
            }
            if data is not None:
                headers["Content-Type"] = "application/json"
            if idempotency_key:
                headers["Idempotency-Key"] = idempotency_key
            try:
                status, answer_headers, raw = self._send(method, path, headers, data)
            except (http.client.RemoteDisconnected, http.client.CannotSendRequest,
                    ConnectionResetError, BrokenPipeError):
                self.close()
                if reconnected:
                    raise
                reconnected = True
                continue
            self.nonces.update(url, answer_headers)
            if retries == 0 and asks_for_nonce(status, answer_headers):
                retries += 1
                continue
            try:
                parsed = json.loads(raw) if raw else {}
            except ValueError:
                parsed = {}
            return Answer(status, parsed if isinstance(parsed, dict) else {"items": parsed},
                          answer_headers, len(raw), retries)

    def call(self, method: str, path: str, body: dict | None = None,
             write: bool = False) -> tuple[int, dict]:
        """Calls the gateway and returns only the status and the decoded body."""
        answer = self.request(method, path, body, write)
        return answer.status, answer.body

    def _send(self, method: str, path: str, headers: dict[str, str],
              data: bytes | None) -> tuple[int, dict[str, str], bytes]:
        """Sends one request over the kept-alive connection and reads the whole answer."""
        if self.connection is None:
            parts = urllib.parse.urlsplit(self.gateway)
            self.connection = http.client.HTTPSConnection(
                parts.hostname, parts.port or 443, context=self.tls, timeout=120)
        self.connection.request(method, path, body=data, headers=headers)
        response = self.connection.getresponse()
        raw = response.read()
        answer_headers = {name.lower(): value for name, value in response.getheaders()}
        if response.will_close:
            self.close()
        return response.status, answer_headers, raw

    def close(self) -> None:
        """Closes the gateway connection; the next call opens a new one."""
        if self.connection is not None:
            self.connection.close()
            self.connection = None
