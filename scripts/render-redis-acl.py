#!/usr/bin/env python3
"""Profit Basetool - render the Redis ACL file from its committed template and the host .env.

``scripts/redis-users.acl.tmpl`` holds the rules: one ACL user per service (REQ-SEC-068,
ADR-0207). This renders it against the host ``.env`` into ``/var/iri/redis/users.acl``, the file
the ``redis`` unit mounts read-only and loads with ``--aclfile``.

Why it exists
-------------
The ACL file used to be written by hand from a two-line recipe, with the ``default`` password in
**plain text**, and nothing checked its shape. That is how the 2026-07-10 incident happened: a file
without a ``default`` line makes Redis reset ``default`` to ``nopass ~* &* +@all``, and the session
store stood open on the internal network. Per-service users make the file five users long, which is
past what a recipe should be trusted with.

What it guarantees
------------------
* **Hashes, never passwords.** Every ``{{hash:NAME}}`` becomes ``#`` + the SHA-256 of NAME's value,
  which Redis accepts in place of ``>password``. The rendered file can be read by the ``redis``
  container user and captured by every backup without disclosing a credential.
* **All or nothing.** A missing or empty variable refuses the whole render, naming every one, and
  nothing is written. A half-rendered ACL is a Redis that refuses its clients, or worse.
* **A ``default`` line, always.** The output is refused if it does not carry exactly one.
* **Atomic.** Written to a temporary file in the same directory, then renamed over the target, so
  ``ACL LOAD`` never reads a half-written file. Mode ``0644``: the container's ``redis`` user must be
  able to read the read-only mount (see docs/deployment.md), and the content is hashes only.

Usage
-----
::

    render-redis-acl.py --env /var/iri/code/.env \\
        --template /var/iri/code/scripts/redis-users.acl.tmpl --out /var/iri/redis/users.acl
    render-redis-acl.py ... --check       # report drift, write nothing
    render-redis-acl.py --selftest        # the regression tests below, no files touched

After writing, apply it live with ``ACL LOAD`` (atomic; a malformed file is rejected and the
running ACL stays), and run ``restorecon -F`` on the file on an SELinux host.

Exit codes: ``0`` clean, ``1`` a refusal or (under ``--check``) drift, ``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
import tempfile
from typing import Sequence

PLACEHOLDER = re.compile(r"\{\{(hash|state):([A-Za-z_][A-Za-z0-9_]*)\}\}")


class Refusal(Exception):
    """Raised when the template cannot be rendered into a safe ACL file."""


def parse_env_file(path: str) -> dict[str, str]:
    """Read a ``.env`` the way compose and ``render-env-d.py`` read one.

    Blank lines and ``#`` comments are skipped, a leading ``export`` is tolerated, one layer of
    matching quotes is stripped, a key assigned twice keeps its last value, and nothing inside a
    value is interpolated.

    Args:
        path: path to the ``.env``.

    Returns:
        The mapping.

    Raises:
        Refusal: if the file cannot be read.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.readlines()
    except OSError as exc:
        raise Refusal(f"cannot read {path}: {exc}") from exc
    env: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        env[key] = value
    return env


def sha256_password(value: str) -> str:
    """Return a password in the form an ACL rule accepts in place of ``>password``.

    Args:
        value: the clear-text password.

    Returns:
        ``#`` followed by the lowercase hex SHA-256 of the UTF-8 bytes.
    """
    return "#" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def render(template: str, env: dict[str, str]) -> str:
    """Render the template text against an environment.

    Args:
        template: the template's text.
        env: the host environment.

    Returns:
        The ACL file's content: one ``user`` line per template rule, comments dropped, ending in a
        newline.

    Raises:
        Refusal: naming every missing variable and every invalid state at once, or when the result
            does not carry exactly one ``default`` user.
    """
    problems: list[str] = []

    def substitute(match: re.Match[str]) -> str:
        kind, name = match.group(1), match.group(2)
        value = env.get(name, "")
        if kind == "hash":
            if not value:
                problems.append(f"{name} is unset or empty")
                return "#" + "0" * 64
            return sha256_password(value)
        state = value.strip().lower() or "on"
        if state not in ("on", "off"):
            problems.append(f"{name}={value!r} is neither on nor off")
            return "on"
        return state

    out: list[str] = []
    for number, raw in enumerate(template.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if not line.startswith("user "):
            raise Refusal(f"template line {number} is neither a comment nor a `user` rule")
        rendered = PLACEHOLDER.sub(substitute, line)
        if "{{" in rendered or "}}" in rendered:
            raise Refusal(f"template line {number} carries a placeholder this renderer does not know")
        out.append(rendered)
    if problems:
        raise Refusal("required values are missing or invalid:\n  " + "\n  ".join(sorted(set(problems))))
    defaults = [line for line in out if line.split()[1] == "default"]
    if len(defaults) != 1:
        raise Refusal(
            f"the result carries {len(defaults)} `user default` line(s); it must carry exactly one, "
            "or Redis resets `default` to nopass ~* &* +@all (the 2026-07-10 incident)"
        )
    return "\n".join(out) + "\n"


def write_atomically(path: str, content: str) -> None:
    """Replace ``path`` with ``content`` in one rename, mode ``0644``.

    Args:
        path: the target file.
        content: what it must contain.
    """
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(prefix=".users.acl.", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def selftest() -> int:
    """Run the renderer's regression tests against in-memory inputs.

    Returns:
        ``0`` when every case holds, ``1`` otherwise.
    """
    template = (
        "# a comment that must not reach the output\n"
        "user default {{state:REDIS_DEFAULT_USER}} {{hash:REDIS_PASSWORD}} ~* &* +@all\n"
        "user svc on {{hash:SVC_PASSWORD}} ~svc:* -@all +get\n"
    )
    env = {"REDIS_PASSWORD": "pw", "SVC_PASSWORD": "a$b c"}
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        if not condition:
            failures.append(name)

    out = render(template, env)
    check("comment dropped", "comment" not in out)
    check("default on when unset", out.startswith("user default on #"))
    check("hash, never the password", ">pw" not in out and " pw " not in out)
    check("sha256 of the value", sha256_password("pw") in out)
    check("a $ and a space survive into the hash", sha256_password("a$b c") in out)
    check("known digest", sha256_password("pw")
          == "#30c952fab122c3f9759f02a6d95c3758b246b4fee239957b2d4fee46e26170c4")
    check("state off", render(template, {**env, "REDIS_DEFAULT_USER": "OFF"}).startswith("user default off "))
    for broken, label in (
        ({"REDIS_PASSWORD": "pw"}, "missing variable refuses"),
        ({**env, "SVC_PASSWORD": ""}, "empty variable refuses"),
        ({**env, "REDIS_DEFAULT_USER": "maybe"}, "invalid state refuses"),
    ):
        try:
            render(template, broken)
            failures.append(label)
        except Refusal:
            pass
    try:
        render("user svc on {{hash:SVC_PASSWORD}} -@all\n", env)
        failures.append("missing default refuses")
    except Refusal:
        pass
    try:
        render(template + "user default on nopass\n", env)
        failures.append("two defaults refuse")
    except Refusal:
        pass
    try:
        render("default on\n", env)
        failures.append("a non-user line refuses")
    except Refusal:
        pass
    try:
        render(template.replace("{{hash:SVC_PASSWORD}}", "{{secret:SVC_PASSWORD}}"), env)
        failures.append("an unknown placeholder refuses")
    except Refusal:
        pass
    with tempfile.TemporaryDirectory() as scratch:
        target = os.path.join(scratch, "users.acl")
        write_atomically(target, out)
        with open(target, encoding="utf-8") as handle:
            check("atomic write round-trips", handle.read() == out)
        check("no temporary file left behind", os.listdir(scratch) == ["users.acl"])
    repo_template = os.path.join(os.path.dirname(os.path.abspath(__file__)), "redis-users.acl.tmpl")
    if os.path.exists(repo_template):
        with open(repo_template, encoding="utf-8") as handle:
            text = handle.read()
        names = sorted({m.group(2) for m in PLACEHOLDER.finditer(text) if m.group(1) == "hash"})
        real = render(text, {name: f"selftest-{name}" for name in names})
        check("the committed template renders", real.count("\nuser ") + 1 == real.count("user "))
        check("the committed template keeps default", real.startswith("user default on "))
        check("the committed template has no clear-text password", ">" not in real)
    for failure in failures:
        print(f"render-redis-acl selftest FAILED: {failure}", file=sys.stderr)
    if not failures:
        print("render-redis-acl selftest: all cases hold")
    return 1 if failures else 0


def main(argv: Sequence[str] | None = None) -> int:
    """Render the ACL file, report drift under ``--check``, or run the self-test.

    Args:
        argv: command-line arguments, defaulting to ``sys.argv[1:]``.

    Returns:
        ``0`` clean, ``1`` a refusal or drift, ``2`` a bad invocation.
    """
    parser = argparse.ArgumentParser(prog="render-redis-acl.py")
    parser.add_argument("--env", help="the host .env")
    parser.add_argument("--template", help="scripts/redis-users.acl.tmpl")
    parser.add_argument("--out", help="the ACL file to write, normally /var/iri/redis/users.acl")
    parser.add_argument("--check", action="store_true", help="report drift and write nothing")
    parser.add_argument("--selftest", action="store_true", help="run the regression tests")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if not (args.env and args.template and args.out):
        parser.error("--env, --template and --out are required")
    try:
        env = parse_env_file(args.env)
        with open(args.template, encoding="utf-8") as handle:
            content = render(handle.read(), env)
    except (Refusal, OSError) as exc:
        print(f"render-redis-acl: REFUSING, nothing written: {exc}", file=sys.stderr)
        return 1
    users = [line.split()[1] for line in content.splitlines()]
    default_state = content.splitlines()[0].split()[2]
    if args.check:
        try:
            with open(args.out, encoding="utf-8") as handle:
                current = handle.read()
        except OSError:
            current = None
        if current != content:
            print(f"render-redis-acl: DRIFT - {args.out} does not match the template and the .env",
                  file=sys.stderr)
            return 1
        print(f"render-redis-acl: {args.out} matches ({', '.join(users)}; default {default_state})")
        return 0
    write_atomically(args.out, content)
    print(f"render-redis-acl: wrote {args.out} ({', '.join(users)}; default {default_state}). "
          "Apply it with ACL LOAD.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
