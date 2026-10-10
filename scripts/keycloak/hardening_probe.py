#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""A browser stand-in for the hardening integration test: it walks Keycloak's login pages over HTTP.

It follows redirects by hand so it can tell the three outcomes apart: a redirect to the client with
an authorization code (login done), the page that asks for an OTP code, and the page that asks to
set an OTP device up. It also computes the TOTP codes (RFC 6238) the set-up page needs.
"""

from __future__ import annotations

import hashlib
import hmac
import html.parser
import http.cookiejar
import re
import struct
import time
import urllib.error
import urllib.parse
import urllib.request


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hand the redirect back to the caller instead of following it."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _Form(html.parser.HTMLParser):
    """Collects the first form with a password or OTP field: its action and every input."""

    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict] = []
        self._current: dict | None = None

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "form":
            self._current = {"action": attributes.get("action", ""), "inputs": {}}
            self.forms.append(self._current)
        elif tag == "input" and self._current is not None and attributes.get("name"):
            self._current["inputs"][attributes["name"]] = attributes.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self._current = None


def parse_forms(page: str) -> list[dict]:
    """Every form of a page with its action and the name -> value of its inputs."""
    parser = _Form()
    parser.feed(page)
    return parser.forms


def totp(secret: str, at: float | None = None) -> str:
    """The six-digit TOTP code (RFC 6238, SHA-1, 30 s) of the raw secret the set-up page carries."""
    key = secret.encode("ascii")
    counter = int((at if at is not None else time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{value:06d}"


_LAST_COUNTER: dict[str, int] = {}


class Outcome:
    """What a login attempt ended on."""

    CODE = "code"
    OTP_CODE = "otp-code"
    OTP_SETUP = "otp-setup"
    LOGIN_FORM = "login-form"
    OTHER = "other"


class Browser:
    """One cookie jar, one realm, one client: enough to log a user in and read the next page."""

    def __init__(self, base_url: str, realm: str, client_id: str, redirect_uri: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.realm = realm
        self.client_id = client_id
        self.redirect_uri = redirect_uri
        self.jar = http.cookiejar.CookieJar(
            http.cookiejar.DefaultCookiePolicy(secure_protocols=("https", "http")))
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), _NoRedirect())
        self.page = ""
        self.url = ""

    def _fetch(self, url: str, data: dict | None = None) -> tuple[int, str, str | None]:
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        request = urllib.request.Request(url, data=body)
        try:
            with self.opener.open(request, timeout=30) as response:
                return response.status, response.read().decode("utf-8", "replace"), None
        except urllib.error.HTTPError as error:
            location = error.headers.get("Location")
            return error.code, error.read().decode("utf-8", "replace"), location

    def _follow(self, status: int, page: str, location: str | None, url: str) -> tuple[str, str]:
        hops = 0
        while location and hops < 15:
            target = urllib.parse.urljoin(url, location)
            if target.startswith(self.redirect_uri):
                return target, ""
            url = target
            status, page, location = self._fetch(url)
            hops += 1
        return url, page

    def start(self) -> str:
        """Open the authorization endpoint; returns the login page."""
        query = urllib.parse.urlencode({
            "client_id": self.client_id, "response_type": "code", "scope": "openid",
            "redirect_uri": self.redirect_uri, "state": "probe", "nonce": "probe"})
        url = f"{self.base_url}/realms/{self.realm}/protocol/openid-connect/auth?{query}"
        status, page, location = self._fetch(url)
        self.url, self.page = self._follow(status, page, location, url)
        return self.page

    def submit(self, fields: dict[str, str], form_index: int = 0) -> str:
        """Post the form `form_index` of the current page with `fields` merged over its inputs."""
        form = parse_forms(self.page)[form_index]
        data = {**form["inputs"], **fields}
        action = urllib.parse.urljoin(self.url, form["action"].replace("&amp;", "&"))
        status, page, location = self._fetch(action, data)
        self.url, self.page = self._follow(status, page, location, action)
        return self.page

    def forgot_password_link(self) -> str | None:
        """The 'Forgot password' link of the current login page, or None when it is not offered."""
        match = re.search(r'href="([^"]*reset-credentials[^"]*)"', self.page)
        return match.group(1).replace("&amp;", "&") if match else None

    def open_link(self, href: str) -> str:
        """Open a link of the current page; returns the page it lands on."""
        url = urllib.parse.urljoin(self.url, href)
        status, page, location = self._fetch(url)
        self.url, self.page = self._follow(status, page, location, url)
        return self.page

    def outcome(self) -> str:
        """Classify the current page."""
        if self.url.startswith(self.redirect_uri):
            return Outcome.CODE
        inputs = {name for form in parse_forms(self.page) for name in form["inputs"]}
        if "totpSecret" in inputs:
            return Outcome.OTP_SETUP
        if "otp" in inputs:
            return Outcome.OTP_CODE
        if "password" in inputs and "username" in inputs:
            return Outcome.LOGIN_FORM
        return Outcome.OTHER

    def login(self, username: str, password: str) -> str:
        """Username and password; returns the outcome."""
        self.start()
        self.submit({"username": username, "password": password})
        return self.outcome()

    def setup_secret(self) -> str:
        """The raw secret the OTP set-up page carries in its hidden field."""
        for form in parse_forms(self.page):
            if "totpSecret" in form["inputs"]:
                return form["inputs"]["totpSecret"]
        raise AssertionError("the OTP set-up page carries no secret")

    def finish_setup(self, label: str = "probe") -> str:
        """Register the OTP device the set-up page offers; returns the outcome afterwards."""
        secret = self.setup_secret()
        counter = int(time.time() // 30)
        self.submit({"totp": totp(secret, counter * 30), "userLabel": label})
        _LAST_COUNTER[secret] = counter
        return self.outcome()

    def answer_otp(self, secret: str) -> str:
        """Answer the OTP code page with a code Keycloak has not seen yet; returns the outcome.

        Keycloak accepts a code only once, so the period after the one that finished the set-up (or
        the current one, when time has moved on) is used; both are inside the look-ahead window.
        """
        counter = max(int(time.time() // 30), _LAST_COUNTER.get(secret, -1) + 1)
        self.submit({"otp": totp(secret, counter * 30)})
        _LAST_COUNTER[secret] = counter
        return self.outcome()


def message_texts(page: str) -> str:
    """The visible text of a page, roughly: tags dropped, whitespace collapsed."""
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", page)).strip()
