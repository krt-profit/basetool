#!/usr/bin/env python3
# Copyright (C) 2026 Lucas Greuloch
# SPDX-License-Identifier: GPL-3.0-only
"""Regression scenarios for the audit's operational and script contracts."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import env_file
import host_companions
import http_budget
from keycloak_kcadm import Kcadm, KcadmError


def module(path: str, name: str):
    """Load one standalone command without executing its CLI."""
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def linux(path: Path) -> str:
    """Map the test workspace to WSL when the Gradle wrapper runs on Windows."""
    if os.name == "nt":
        return "/mnt/" + path.drive[0].lower() + path.as_posix()[2:]
    return str(path)


def bash_file(path: Path) -> subprocess.CompletedProcess:
    """Run a written Bash scenario with scratch files confined to the task directory."""
    environment = os.environ.copy()
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    if os.name == "nt":
        command = ["wsl", "--distribution", "Ubuntu", "--exec", "env",
                   f"TMPDIR={linux(path.parent)}", "PYTHONDONTWRITEBYTECODE=1", "bash", linux(path)]
    else:
        environment["TMPDIR"] = str(path.parent)
        command = ["bash", str(path)]
    return subprocess.run(command, capture_output=True, text=True, check=False,
                          timeout=600, env=environment)


def function(path: str, name: str) -> str:
    """Extract a named top-level Bash function for isolated command-failure fixtures."""
    return re.search(rf"^{name}\(\) \{{.*?^\}}", (ROOT / path).read_text(), re.M | re.S)[0]


class OperationsTests(unittest.TestCase):
    """Use synthetic values and local subprocesses only."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="audit-ops-")
        self.addCleanup(self.directory.cleanup)
        self.scratch = Path(self.directory.name)

    def shell(self, body: str):
        path = self.scratch / "scenario.sh"
        path.write_text("set -euo pipefail\n" + body, encoding="utf-8", newline="\n")
        result = bash_file(path)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout

    def test_literal_env_grammar_and_both_renderers(self):
        source = self.scratch / "fixture.env"
        source.write_bytes(b"# comment\r\n A = first \r\nexport A='last $ # literal'\r\n"
                           b'export\tB=" spaced "\r\nEMPTY=\r\nQ=\'$HOME\\literal#x\'\r\n')
        expected = {"A": "last $ # literal", "B": " spaced ", "EMPTY": "", "Q": "$HOME\\literal#x"}
        self.assertEqual(expected, env_file.parse_env_file(source))
        for script in ("render-env-d.py", "render-redis-acl.py"):
            renderer = module("scripts/" + script, script.replace("-", "_"))
            self.assertEqual(expected, renderer.parse_env_file(source))
        self.assertEqual("last $ # literal", self.shell(
            f'ENV_FILE="{linux(source)}"\n. "{linux(ROOT / "scripts/lib/common.sh")}"\nread_env A'))

    def test_hung_kcadm_is_killed_and_reports_uncertain_mutation(self):
        start = time.monotonic()
        cli = Kcadm([sys.executable, "-c", "import time; time.sleep(10)"], "synthetic", False, timeout=.1)
        with self.assertRaisesRegex(KcadmError, "read its current state"):
            cli._run(["create", "clients"])
        self.assertLess(time.monotonic() - start, 3)
        with self.assertRaisesRegex(KcadmError, "No mutation"):
            cli._run(["get", "clients"])

    def test_graphql_timeout_has_no_blind_retry_or_token(self):
        command = module(".github/scripts/create_signed_commit.py", "signed_commit")
        with patch.object(command.urllib.request, "urlopen", side_effect=TimeoutError) as send:
            with self.assertRaisesRegex(SystemExit, "inspect the branch head") as caught:
                command.create_commit({}, "synthetic-credential", timeout=.1)
            self.assertNotIn("synthetic-credential", str(caught.exception))
            self.assertEqual(1, send.call_count)
            self.assertEqual(.1, send.call_args.kwargs["timeout"])

    def test_slow_http_stream_stops_at_total_budget(self):
        class SlowResponse:
            def read1(self, _):
                time.sleep(.02)
                return b"x"
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            http_budget.read_before_deadline(SlowResponse(), started + .05)
        self.assertLess(time.monotonic() - started, .5)
        self.assertEqual(5, http_budget.connection_timeout(60))
        self.assertEqual(.1, http_budget.connection_timeout(.1))

    def test_alloy_status_and_output_are_both_required(self):
        check = function("scripts/check-monitoring-configs.sh", "check_alloy_validate")
        self.shell(check + r'''
image_of() { echo synthetic@sha256:0000; }
MONITORING_DIR=/synthetic
STATUS=9
OUTPUT=''
docker() { printf '%s' "$OUTPUT"; return "$STATUS"; }
if check_alloy_validate; then exit 1; fi
STATUS=0
OUTPUT='invalid configuration'
if check_alloy_validate; then exit 2; fi
OUTPUT=''
check_alloy_validate
''')

    def test_cleanup_attempts_all_steps_preserves_failure_and_protects_volumes(self):
        directory = linux(self.scratch)
        script = ROOT / "scripts/container-cleanup.sh"
        self.shell(f'''
mkdir -p "{directory}/textfile"
printf 'basetool_container_cleanup_last_success_timestamp 123\\n' > "{directory}/textfile/container_cleanup.prom"
cat > "{directory}/runtime" <<'STUB'
#!/bin/bash
printf '%s\\n' "$*" >> "$CALLS"
case "$1 $2" in
  "container prune") exit "${{PRUNE_FAIL:-0}}" ;;
  "system df") echo 0B ;;
esac
STUB
cat > "{directory}/systemctl" <<'STUB'
#!/bin/bash
echo running
STUB
chmod +x "{directory}/runtime" "{directory}/systemctl"
export CALLS="{directory}/calls"
export RT_BACKEND=podman RT_CLI="{directory}/runtime" RT_SYSTEMCTL="{directory}/systemctl"
export BASETOOL_CLEANUP_LOCKFILE="{directory}/cleanup.lock" BASETOOL_MONITORING_TEXTFILE_DIR="{directory}/textfile"
export PRUNE_FAIL=7
if bash "{linux(script)}" > "{directory}/cleanup.log" 2>&1; then exit 1; fi
grep -q 'image prune' "$CALLS"
grep -q 'network prune' "$CALLS"
! grep -q 'volume prune' "$CALLS"
grep -q 'timestamp 123$' "{directory}/textfile/container_cleanup.prom"
export PRUNE_FAIL=0
bash "{linux(script)}" >> "{directory}/cleanup.log" 2>&1
! grep -q 'timestamp 123$' "{directory}/textfile/container_cleanup.prom"
cp "{directory}/textfile/container_cleanup.prom" "{directory}/before.prom"
: > "$CALLS"
bash "{linux(script)}" --dry-run >> "{directory}/cleanup.log" 2>&1
! grep -q prune "$CALLS"
cmp "{directory}/before.prom" "{directory}/textfile/container_cleanup.prom"
''')

    def test_restore_rejects_missing_data_and_unvalidated_constraints(self):
        verify = function("scripts/restore-drill.sh", "verify_restored_contents")
        self.shell(verify + r'''
log() { :; }
dexec() { printf 'COPY public.fixture (id) FROM stdin;\n1\n2\n\\.\n'; }
ACTUAL=2
INVALID=0
q() { if [[ "$2" == *pg_constraint* ]]; then echo "$INVALID"; else echo "$ACTUAL"; fi; }
verify_restored_contents fixture fixture.dump fixture
ACTUAL=0
if verify_restored_contents fixture fixture.dump fixture; then exit 1; fi
ACTUAL=1
if verify_restored_contents fixture fixture.dump fixture; then exit 2; fi
ACTUAL=2
INVALID=1
if verify_restored_contents fixture fixture.dump fixture; then exit 3; fi
INVALID=0
dexec() { return 7; }
if verify_restored_contents fixture fixture.dump fixture; then exit 4; fi
''')

    def test_companion_inventory_and_uid_normalization(self):
        entries = json.loads((ROOT / "scripts/host-companions.json").read_text())
        self.assertEqual(21, len(entries))
        self.assertEqual(len(entries), len({x["target"] for x in entries}))
        self.assertEqual(len(entries), len(host_companions.generate()))
        template = (ROOT / entries[3]["source"]).read_bytes()
        actual = b"# local generator header\n[Unit]\nAfter=systemd-logind.service user@1001.service\n"
        self.assertEqual(host_companions.digest(template, True), host_companions.digest(actual, True))
        self.assertNotEqual(host_companions.digest(actual, True), host_companions.digest(actual.replace(b"After", b"Before"), True))
        self.assertNotEqual(host_companions.digest(template, True),
                            host_companions.digest(actual, True, service_uid=1002))
        self.assertEqual(host_companions.digest(template, True),
                         host_companions.digest(actual, True, service_uid=1001))

    def test_companion_drift_reports_missing_and_changed_targets(self):
        directory = linux(self.scratch)
        checker = function("scripts/lib/deploy-drift.sh", "host_scripts_compare")
        self.shell(checker + f'''
BASETOOL_SCRIPT_DIR="{linux(ROOT / 'scripts')}"
HOST_SCRIPTS_DIR="{linux(ROOT / 'scripts')}"
BASETOOL_HOST_ROOT="{directory}/host"
mkdir -p "$BASETOOL_HOST_ROOT/etc/systemd/system"
printf '[Unit]\\nAfter=synthetic\\n' > "$BASETOOL_HOST_ROOT/etc/systemd/system/fixture.service"
digest="$(sha256sum < "$BASETOOL_HOST_ROOT/etc/systemd/system/fixture.service")"
printf '%s  host/etc/systemd/system/fixture.service\\n' "${{digest%% *}}" > "{directory}/manifest"
host_scripts_compare "{directory}/manifest"
[[ "${{#HS_MISSING[@]}} ${{#HS_DIFFERS[@]}}" == '0 0' ]]
printf 'changed\\n' >> "$BASETOOL_HOST_ROOT/etc/systemd/system/fixture.service"
host_scripts_compare "{directory}/manifest"
[[ "${{#HS_DIFFERS[@]}}" == 1 ]]
rm "$BASETOOL_HOST_ROOT/etc/systemd/system/fixture.service"
host_scripts_compare "{directory}/manifest"
[[ "${{#HS_MISSING[@]}}" == 1 ]]
''')

    def test_existing_operational_shell_scenarios(self):
        for name in ("deploy.test.sh", "host-scripts-manifest.test.sh", "container-runtime.test.sh", "render-env-d.test.sh"):
            with self.subTest(script=name):
                self.shell(f'bash "{linux(ROOT / "scripts" / name)}"')
        self.shell(f'python3 "{linux(ROOT / "scripts/render-redis-acl.py")}" --selftest')

    def test_image_reuse_and_bridge_gates(self):
        for script in (".github/scripts/image_reuse_plan.py", "scripts/check-deploy-podman-allowlist.py",
                       ".github/scripts/check_sbom_coverage.py"):
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, str(ROOT / script), "--selftest"],
                                        capture_output=True, text=True, timeout=60, check=False)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_admin_read_grant_resets_environment_and_refuses_setenv(self):
        role = (ROOT / "ansible/roles/basetool_host/tasks/23-admin-access.yml").read_text()
        self.assertIn("NOPASSWD: NOSETENV: BASETOOL_ADMIN_READ_PODMAN", role)
        self.assertIn("env_reset, always_set_home, !setenv", role)
        self.assertIn('env_keep = "TERM"', role)
        self.assertNotIn("NOPASSWD: SETENV:", role)

    def test_otp_alt_text_is_localized_in_both_languages(self):
        theme = ROOT / "keycloak-theme/krt-theme/login"
        self.assertIn('alt="${msg("totpQrCodeAlt")}"', (theme / "login-config-totp.ftl").read_text())
        for language in ("de", "en"):
            self.assertRegex((theme / f"messages/messages_{language}.properties").read_text(), r"(?m)^totpQrCodeAlt=.+$")


if __name__ == "__main__":
    unittest.main(verbosity=2)
