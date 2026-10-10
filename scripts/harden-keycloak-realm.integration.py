#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

"""Proves scripts/harden-keycloak-realm.py against a real, throwaway Keycloak with the test realm.

Starts the sandbox Keycloak image (published throwaway secrets, `start-dev`), runs the hardening
script against it through `docker exec ... kcadm.sh`, and then logs in over HTTP like a browser:

  * step 11: an admin is forced to set an OTP device up and is asked for the code on the next
    login, a member never is, a wrong code is refused, and the rollback gives the admin back a
    plain password login;
  * step 12: the SSO session windows of the chosen profile are on the realm;
  * step 2: "Forgot password" mails a reset link through the realm's SMTP sender to a sink inside
    the container (a single-file Java program on its loopback).

Needs Docker and a free port; touches nothing else. Run it locally:

    python scripts/harden-keycloak-realm.integration.py --image <sandbox keycloak image>

The image is the one the E2E stack builds from docker/sandbox/keycloak/Dockerfile.
"""

from __future__ import annotations

import argparse
import json
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
HARDEN = SCRIPT_DIR / "harden-keycloak-realm.py"
sys.path.insert(0, str(SCRIPT_DIR / "keycloak"))
from hardening_probe import Browser, Outcome, parse_forms  # noqa: E402

REALM = "iri"
CLIENT = "hardening-probe"
REDIRECT = "http://127.0.0.1:9/callback"
MEMBER = ("sandbox-member", "sandbox-member-pw-do-not-use-in-prod")


SINK_SOURCE = """
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class Sink {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(2525, 5, InetAddress.getLoopbackAddress())) {
            while (true) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                    out.print("220 sink\\r\\n"); out.flush();
                    StringBuilder mail = new StringBuilder();
                    boolean data = false;
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (data) {
                            if (line.equals(".")) {
                                data = false;
                                Files.writeString(Path.of("/tmp/mail.log"), mail + "\\n=====\\n",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                out.print("250 queued\\r\\n");
                            } else {
                                mail.append(line).append('\\n');
                            }
                        } else {
                            String verb = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line;
                            if (verb.equals("RCPT") || verb.equals("MAIL")) { mail.append(line).append('\\n'); }
                            if (verb.equals("DATA")) { data = true; out.print("354 go\\r\\n"); }
                            else if (verb.equals("QUIT")) { out.print("221 bye\\r\\n"); out.flush(); break; }
                            else { out.print("250 ok\\r\\n"); }
                        }
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }
}
"""


class SmtpSink:
    """A tiny SMTP server that runs inside the Keycloak container, on its loopback only.

    It is a single-file Java program started with the container's own JDK, so nothing listens on the
    host and the test needs no extra image. Every message it is handed is appended to a file in the
    container, which `messages()` reads.
    """

    HOST = "localhost"
    PORT = 2525
    LOG = "/tmp/mail.log"

    def __init__(self, stack: "Stack") -> None:
        self.stack = stack

    def start(self) -> None:
        """Copy the program into the container and start it in the background."""
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Sink.java"
            source.write_text(SINK_SOURCE, encoding="utf-8")
            self.stack.docker("cp", str(source), f"{self.stack.name}:/tmp/Sink.java")
        self.stack.docker("exec", "-d", self.stack.name, "java", "/tmp/Sink.java")
        for _ in range(60):
            probe = self.stack.docker(
                "exec", self.stack.name, "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/2525", check=False)
            if probe.returncode == 0:
                return
            time.sleep(1)
        raise SystemExit("FATAL: the SMTP sink did not start")

    def messages(self) -> list[dict]:
        """Every message received so far: the recipients and the body."""
        completed = self.stack.docker("exec", self.stack.name, "bash", "-c",
                                      f"cat {self.LOG} 2>/dev/null || true", check=False)
        out = []
        for raw in completed.stdout.split("\n=====\n"):
            if raw.strip():
                out.append({"to": re.findall(r"RCPT TO:\s*(\S+)", raw, re.I), "data": raw})
        return out


class Stack:
    """The throwaway Keycloak container and the checks run against it."""

    def __init__(self, image: str, port: int) -> None:
        self.image = image
        self.port = port
        self.name = f"kc-hardening-{secrets.token_hex(3)}"
        self.admin = ("hardening-admin", secrets.token_urlsafe(12))
        self.failures: list[str] = []

    @property
    def base(self) -> str:
        """The URL the probes talk to."""
        return f"http://127.0.0.1:{self.port}"

    def docker(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        """Run a docker command."""
        return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)

    def start(self) -> None:
        """Start the container and wait until the realm answers."""
        self.docker("run", "-d", "--name", self.name, "-p", f"127.0.0.1:{self.port}:8080",
                    "-e", f"KC_BOOTSTRAP_ADMIN_USERNAME={self.admin[0]}",
                    "-e", f"KC_BOOTSTRAP_ADMIN_PASSWORD={self.admin[1]}",
                    self.image, "start-dev", "--import-realm", "--cache=local")
        url = f"{self.base}/realms/{REALM}/.well-known/openid-configuration"
        for _ in range(120):
            try:
                with urllib.request.urlopen(url, timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(2)
        else:
            raise SystemExit("FATAL: Keycloak did not come up in time")
        self.kcadm("config", "credentials", "--server", "http://localhost:8080", "--realm", "master",
                   "--user", self.admin[0], "--password", self.admin[1])

    def stop(self) -> None:
        """Remove the container."""
        self.docker("rm", "-f", self.name, check=False)

    def kcadm_prefix(self) -> list[str]:
        """The kcadm invocation the hardening script is given."""
        return ["docker", "exec", "-i", self.name, "/opt/keycloak/bin/kcadm.sh"]

    def kcadm(self, *args: str) -> str:
        """Run kcadm inside the container; returns stdout."""
        completed = subprocess.run(self.kcadm_prefix() + list(args), capture_output=True, text=True)
        if completed.returncode != 0:
            raise SystemExit(f"FATAL: kcadm {' '.join(args)}: {completed.stderr.strip()}")
        return completed.stdout

    def get_json(self, path: str) -> dict | list:
        """Read an Admin API path of the realm through kcadm."""
        return json.loads(self.kcadm("get", path, "-r", REALM))

    def harden(self, *extra: str) -> subprocess.CompletedProcess:
        """Run the hardening script against the container."""
        command = [sys.executable, str(HARDEN), "--kcadm-command", " ".join(self.kcadm_prefix()), *extra]
        return subprocess.run(command, capture_output=True, text=True, encoding="utf-8")

    def create_user(self, username: str, password: str, role: str | None) -> None:
        """Create an enabled user with a password and optionally a realm role."""
        self.kcadm("create", "users", "-r", REALM, "-s", f"username={username}", "-s", "enabled=true",
                   "-s", f"email={username}@example.invalid", "-s", "emailVerified=true",
                   "-s", "firstName=Probe", "-s", "lastName=User")
        self.kcadm("set-password", "-r", REALM, "--username", username, "--new-password", password)
        if role:
            self.kcadm("add-roles", "-r", REALM, "--uusername", username, "--rolename", role)

    def browser(self) -> Browser:
        """A fresh browser for the probe client."""
        return Browser(self.base, REALM, CLIENT, REDIRECT)

    def check(self, condition: bool, message: str) -> None:
        """Record and print one assertion."""
        print(f"  {'ok  ' if condition else 'FAIL'} {message}")
        if not condition:
            self.failures.append(message)


def run(stack: Stack, sink: SmtpSink, workdir: Path) -> None:
    """The scenario."""
    admin_pw = "probe-admin-" + secrets.token_hex(4)
    stack.kcadm("create", "clients", "-r", REALM, "-s", f"clientId={CLIENT}", "-s", "publicClient=true",
                "-s", "standardFlowEnabled=true", "-s", f'redirectUris=["{REDIRECT}"]')
    stack.create_user("probe-admin", admin_pw, "Admin")
    stack.create_user("probe-admin-rollback", admin_pw, "Admin")
    stack.create_user("probe-forgetful", admin_pw, None)
    realm_before = stack.get_json(f"realms/{REALM}")
    backup = workdir / "hardening.before.json"
    flags = ["--windows", "proposal", "--reset-password", "on", "--smtp-host", sink.HOST,
             "--smtp-port", str(sink.PORT), "--smtp-from", "noreply@sandbox.invalid",
             "--backup-file", str(backup)]

    print("before: a password is all an admin needs")
    browser = stack.browser()
    stack.check(browser.login("probe-admin-rollback", admin_pw) == Outcome.CODE,
                "an admin signs in with the password alone")

    print("dry run")
    dry = stack.harden(*flags)
    stack.check(dry.returncode == 2, f"a dry run with work to do exits 2 (got {dry.returncode})")
    realm_dry = stack.get_json(f"realms/{REALM}")
    stack.check(realm_dry.get("browserFlow") == realm_before.get("browserFlow")
                and realm_dry.get("ssoSessionMaxLifespan") == realm_before.get("ssoSessionMaxLifespan"),
                "a dry run changes nothing in the realm")
    stack.check(not backup.exists(), "a dry run writes no rollback file")

    print("apply")
    applied = stack.harden("--apply", *flags)
    if applied.returncode != 0:
        print(applied.stdout[-1500:], applied.stderr[-800:])
    stack.check(applied.returncode == 0, f"apply exits 0 after its own verify (got {applied.returncode})")
    stack.check(backup.exists(), "apply wrote the rollback file")
    again = stack.harden(*flags)
    stack.check(again.returncode == 0, f"a second run finds nothing to do (got {again.returncode})")

    print("step 12: session windows")
    realm = stack.get_json(f"realms/{REALM}")
    stack.check(realm["ssoSessionMaxLifespan"] == 7776000 and realm["ssoSessionMaxLifespanRememberMe"] == 7776000
                and realm["ssoSessionIdleTimeout"] == 2592000,
                "the realm carries the proposal's session windows")

    print("step 11: OTP for admins")
    browser = stack.browser()
    stack.check(browser.login("probe-admin", admin_pw) == Outcome.OTP_SETUP,
                "an admin without a device is forced to set one up")
    device = browser.setup_secret()
    stack.check(browser.finish_setup() == Outcome.CODE, "setting the device up completes the login")
    browser = stack.browser()
    stack.check(browser.login("probe-admin", admin_pw) == Outcome.OTP_CODE,
                "the next login asks the admin for the code")
    wrong = stack.browser()
    wrong.login("probe-admin", admin_pw)
    wrong.submit({"otp": "000000"})
    stack.check(wrong.outcome() == Outcome.OTP_CODE, "a wrong code is refused")
    stack.check(browser.answer_otp(device) == Outcome.CODE, "the right code completes the login, asked once")
    member = stack.browser()
    stack.check(member.login(*MEMBER) == Outcome.CODE, "a member signs in with the password alone")
    stack.check(stack.browser().login("probe-admin-rollback", admin_pw) == Outcome.OTP_SETUP,
                "a second admin is forced too")

    print("step 2: forgot password")
    before = len(sink.messages())
    page = stack.browser()
    page.start()
    link = page.forgot_password_link()
    stack.check(link is not None, "the login page offers 'Forgot password'")
    if link:
        page.open_link(link)
        forms = parse_forms(page.page)
        stack.check(bool(forms) and "username" in forms[0]["inputs"], "the reset page asks for the username")
        if forms:
            page.submit({"username": "probe-forgetful"})
        deadline = time.time() + 20
        while len(sink.messages()) == before and time.time() < deadline:
            time.sleep(0.5)
        stack.check(len(sink.messages()) > before, "the realm's sender hands a mail to the SMTP sink")
        if len(sink.messages()) > before:
            mail = sink.messages()[-1]
            stack.check(any("probe-forgetful@example.invalid" in recipient for recipient in mail["to"]),
                        "the mail goes to the member's address")
            stack.check("action-token" in mail["data"] and "key=" in mail["data"],
                        "the mail carries a reset link with an action token")

    print("rollback")
    rolled = stack.harden("--apply", "--rollback", str(backup), "--backup-file", str(backup))
    stack.check(rolled.returncode == 0, f"the rollback exits 0 (got {rolled.returncode})")
    realm = stack.get_json(f"realms/{REALM}")
    stack.check(realm["browserFlow"] == "browser", "the built-in browser flow is bound again")
    stack.check(realm["ssoSessionMaxLifespan"] == realm_before["ssoSessionMaxLifespan"],
                "the session maximum is back")
    stack.check(stack.browser().login("probe-admin-rollback", admin_pw) == Outcome.CODE,
                "an admin signs in with the password alone again")
    flows = {flow["alias"] for flow in stack.get_json("authentication/flows")}
    stack.check("browser-admin-otp" in flows, "the flow the run created is left in place, unbound")


def main() -> int:
    """Entry point."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--image", required=True, help="the sandbox Keycloak image")
    parser.add_argument("--port", type=int, default=0, help="host port (default: a free one)")
    parser.add_argument("--keep", action="store_true", help="leave the container running afterwards")
    args = parser.parse_args()

    port = args.port
    if not port:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
    stack = Stack(args.image, port)
    sink = SmtpSink(stack)
    try:
        stack.start()
        sink.start()
        with tempfile.TemporaryDirectory() as directory:
            run(stack, sink, Path(directory))
    finally:
        if not args.keep:
            stack.stop()
    print(f"\n{'FAILED: ' + str(len(stack.failures)) + ' check(s)' if stack.failures else 'all checks passed'}")
    return 1 if stack.failures else 0


if __name__ == "__main__":
    sys.exit(main())
