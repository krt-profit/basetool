#!/usr/bin/env python3
"""Profit Basetool - render the Redis ACL file from its committed template and the host .env.

One ACL user per service (REQ-SEC-068, ADR-0207), rendered into the file a Redis unit loads with
``--aclfile``; each instance has its own template and file (ADR-0388). Every ``{{hash:NAME}}``
becomes ``#`` + the SHA-256 of NAME's value and every ``{{state:NAME}}`` NAME's ``on`` or ``off``;
a missing or empty variable, or a result without exactly one ``default`` user, refuses the whole
render. The file is written atomically; a new file is owner-only (``0600``), an existing one keeps
its mode and owner.

Usage
-----
::

    render-redis-acl.py --env /etc/basetool/basetool.env \\
        --template /opt/basetool/scripts/redis-session-users.acl.tmpl \\
        --out /etc/basetool/redis/session-users.acl
    render-redis-acl.py --env /etc/basetool/basetool.env \\
        --template /opt/basetool/scripts/redis-shared-users.acl.tmpl \\
        --out /etc/basetool/redis/shared-users.acl
    render-redis-acl.py ... --check       # report drift, write nothing
    render-redis-acl.py --selftest        # the regression tests, no files touched

Apply it live with ``ACL LOAD``; run ``restorecon -F`` on the file on an SELinux host.

Exit codes: ``0`` clean, ``1`` a refusal or (under ``--check``) drift, ``2`` bad invocation.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import os
import re
import stat
import sys
import tempfile
from collections.abc import Sequence

from env_file import EnvFileError, parse_env_file

PLACEHOLDER = re.compile(r"\{\{(hash|state):([A-Za-z_][A-Za-z0-9_]*)\}\}")

# The committed templates, each with the (key, channel) prefixes an application user of its
# instance may be granted (ADR-0388): the session store holds only Spring Session, the shared Redis
# everything else. The operator's users are exempt.
COMMITTED_TEMPLATES: dict[str, tuple[tuple[str, ...], tuple[str, ...]]] = {
    "redis-session-users.acl.tmpl": (
        ("basetool:session:",),
        ("basetool:session:", "__keyevent@0__:"),
    ),
    "redis-shared-users.acl.tmpl": (
        ("ingest:", "exchange:"),
        ("basetool:livesync:", "basetool:notify:"),
    ),
}
OPERATOR_USERS = frozenset({"default", "admin"})
KEY_SELECTOR = re.compile(r"^(?:%(?:R|W|RW))?~")


class Refusal(Exception):
    """Raised when the template cannot be rendered into a safe ACL file."""


def read_text(path: str) -> str:
    """Read a UTF-8 text file.

    Args:
        path: the file.

    Returns:
        Its content.
    """
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def grants_outside(rendered: str, keys: tuple[str, ...], channels: tuple[str, ...]) -> list[str]:
    """Name every key or channel grant of an application user outside its instance's prefixes.

    Args:
        rendered: the rendered ACL, one ``user`` rule per line.
        keys: the key prefixes an application user may be granted.
        channels: the channel prefixes an application user may be granted.

    Returns:
        ``"<user>: <token>"`` for each offending ``~``/``%R~``/``%W~``/``%RW~`` key pattern, ``&``
        channel pattern, ``allkeys`` or ``allchannels``; empty when every grant is inside.
    """
    findings: list[str] = []
    for line in rendered.splitlines():
        tokens = line.split()
        if tokens[1] in OPERATOR_USERS:
            continue
        for token in tokens[2:]:
            selector = KEY_SELECTOR.match(token)
            if (
                token in ("allkeys", "allchannels")
                or selector
                and not token[selector.end() :].startswith(keys)
                or token.startswith("&")
                and not token[1:].startswith(channels)
            ):
                findings.append(f"{tokens[1]}: {token}")
    return findings


def sha256_password(value: str) -> str:
    """Return a password in the form an ACL rule accepts in place of ``>password``.

    Args:
        value: the clear-text password.

    Returns:
        ``#`` followed by the lowercase hex SHA-256 of the UTF-8 bytes.
    """
    return "#" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def hash_variables(template: str) -> set[str]:
    """Return the variables a template turns into password hashes.

    Args:
        template: the template's text.

    Returns:
        The names of its ``{{hash:NAME}}`` placeholders.
    """
    return {m.group(2) for m in PLACEHOLDER.finditer(template) if m.group(1) == "hash"}


def shared_passwords(own: set[str], foreign: set[str], env: dict[str, str]) -> list[str]:
    """Name the pairs of distinct variables that hold one password.

    Args:
        own: the template's hash variables.
        foreign: the hash variables of the other instance's template.
        env: the host environment.

    Returns:
        ``"A and B"`` for every pair with at least one variable in ``own`` and one non-empty value;
        never a value.
    """
    pool = sorted(own | foreign)
    return [
        f"{first} and {second}"
        for index, first in enumerate(pool)
        for second in pool[index + 1 :]
        if (first in own or second in own) and env.get(first) and env.get(first) == env.get(second)
    ]


def render(template: str, env: dict[str, str], foreign: frozenset[str] = frozenset()) -> str:
    """Render the template text against an environment.

    Args:
        template: the template's text.
        env: the host environment.
        foreign: the hash variables of the other instance's template, whose passwords this
            template's must not repeat.

    Returns:
        One ``user`` line per template rule, comments dropped, ending in a newline.

    Raises:
        Refusal: on missing variables, invalid states or one password under two variables (all
            named at once), or without exactly one ``default`` user.
    """
    problems: list[str] = [
        f"{pair} hold the same password; every user of each instance needs its own"
        for pair in shared_passwords(hash_variables(template), set(foreign), env)
    ]

    def substitute(match: re.Match[str]) -> str:
        kind, name = match.group(1), match.group(2)
        value = env.get(name, "")
        if kind == "hash":
            if not value:
                problems.append(f"{name} is unset or empty")
                return "#" + "0" * 64
            return sha256_password(value)
        state = value.strip().lower()
        if not state:
            problems.append(
                f"{name} is unset; set it to off, or to on only while an application "
                "still authenticates without a username"
            )
            return "off"
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
            raise Refusal(
                f"template line {number} carries a placeholder this renderer does not know"
            )
        out.append(rendered)
    if problems:
        raise Refusal(
            "required values are missing or invalid:\n  " + "\n  ".join(sorted(set(problems)))
        )
    defaults = [line for line in out if line.split()[1] == "default"]
    if len(defaults) != 1:
        raise Refusal(
            f"the result carries {len(defaults)} `user default` line(s); it must carry exactly one, "
            "or Redis resets `default` to nopass ~* &* +@all (the 2026-07-10 incident)"
        )
    return "\n".join(out) + "\n"


def write_atomically(path: str, content: str) -> None:
    """Replace ``path`` with ``content`` in one rename.

    An existing target keeps its mode and, when running as root, its owner, so the file redis reads
    stays readable to it. A new file is owner-only (``0600``): it holds password hashes.

    Args:
        path: the target file.
        content: what it must contain.
    """
    directory = os.path.dirname(os.path.abspath(path))
    try:
        existing = os.stat(path)
    except FileNotFoundError:
        existing = None
    fd, tmp = tempfile.mkstemp(prefix=".users.acl.", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        if existing is not None:
            os.chmod(tmp, stat.S_IMODE(existing.st_mode))
            if hasattr(os, "geteuid") and os.geteuid() == 0:
                os.chown(tmp, existing.st_uid, existing.st_gid)
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
    env = {"REDIS_PASSWORD": "pw", "SVC_PASSWORD": "a$b c", "REDIS_DEFAULT_USER": "on"}
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        if not condition:
            failures.append(name)

    out = render(template, env)
    check("comment dropped", "comment" not in out)
    check("state on", out.startswith("user default on #"))
    check("hash, never the password", ">pw" not in out and " pw " not in out)
    check("sha256 of the value", sha256_password("pw") in out)
    check("a $ and a space survive into the hash", sha256_password("a$b c") in out)
    check(
        "known digest",
        sha256_password("pw")
        == "#30c952fab122c3f9759f02a6d95c3758b246b4fee239957b2d4fee46e26170c4",
    )
    check(
        "state off",
        render(template, {**env, "REDIS_DEFAULT_USER": "OFF"}).startswith("user default off "),
    )
    unset_state = {key: value for key, value in env.items() if key != "REDIS_DEFAULT_USER"}
    for broken, label in (
        ({"REDIS_PASSWORD": "pw", "REDIS_DEFAULT_USER": "off"}, "missing variable refuses"),
        ({**env, "SVC_PASSWORD": ""}, "empty variable refuses"),
        ({**env, "REDIS_DEFAULT_USER": "maybe"}, "invalid state refuses"),
        (unset_state, "unset state refuses"),
        ({**env, "REDIS_DEFAULT_USER": " "}, "blank state refuses"),
    ):
        with contextlib.suppress(Refusal):
            render(template, broken)
            failures.append(label)
    try:
        render(
            template,
            {**env, "REDIS_PASSWORD": "s3cr3t-sentinel", "SVC_PASSWORD": "s3cr3t-sentinel"},
        )
        failures.append("two variables with one password refuse")
    except Refusal as refusal:
        check(
            "a refusal names the variables, never the value",
            "SVC_PASSWORD" in str(refusal) and "s3cr3t" not in str(refusal),
        )
    with contextlib.suppress(Refusal):
        render(template, {**env, "OTHER_PASSWORD": "a$b c"}, frozenset({"OTHER_PASSWORD"}))
        failures.append("a password the other instance uses refuses")
    try:
        render(template, {**env, "OTHER_PASSWORD": "other"}, frozenset({"OTHER_PASSWORD"}))
    except Refusal:
        failures.append("distinct passwords across instances render")
    try:
        render(template, {**env, "OTHER_PASSWORD": "pw", "UNRELATED": "pw"}, frozenset())
    except Refusal:
        failures.append("a variable no template names is not compared")
    with contextlib.suppress(Refusal):
        render("user svc on {{hash:SVC_PASSWORD}} -@all\n", env)
        failures.append("missing default refuses")
    with contextlib.suppress(Refusal):
        render(template + "user default on nopass\n", env)
        failures.append("two defaults refuse")
    with contextlib.suppress(Refusal):
        render("default on\n", env)
        failures.append("a non-user line refuses")
    with contextlib.suppress(Refusal):
        render(template.replace("{{hash:SVC_PASSWORD}}", "{{secret:SVC_PASSWORD}}"), env)
        failures.append("an unknown placeholder refuses")
    with tempfile.TemporaryDirectory() as scratch:
        target = os.path.join(scratch, "users.acl")
        write_atomically(target, out)
        with open(target, encoding="utf-8") as handle:
            check("atomic write round-trips", handle.read() == out)
        check("no temporary file left behind", os.listdir(scratch) == ["users.acl"])
    session_prefixes = COMMITTED_TEMPLATES["redis-session-users.acl.tmpl"]
    for rule, label in (
        ("user svc on #0 ~ingest:* -@all", "a foreign key prefix"),
        ("user svc on #0 %R~exchange:* -@all", "a foreign read-only key prefix"),
        ("user svc on #0 ~* -@all", "a wildcard key pattern"),
        ("user svc on #0 allkeys -@all", "allkeys"),
        ("user svc on #0 &basetool:livesync:changed -@all", "a foreign channel"),
        ("user svc on #0 &* -@all", "a wildcard channel pattern"),
    ):
        check(
            f"{label} is found",
            grants_outside(rule, *session_prefixes) == [f"svc: {rule.split()[4]}"],
        )
    check(
        "the operator's users are exempt",
        not grants_outside(
            "user admin on #0 ~* &* +@all\nuser default off #0 allkeys", *session_prefixes
        ),
    )
    check(
        "an inside grant passes",
        not grants_outside(
            "user svc on #0 ~basetool:session:* &__keyevent@0__:del -@all", *session_prefixes
        ),
    )
    here = os.path.dirname(os.path.abspath(__file__))
    present = {
        name: read_text(os.path.join(here, name))
        for name in COMMITTED_TEMPLATES
        if os.path.exists(os.path.join(here, name))
    }
    selftest_env = {
        name: f"selftest-{name}" for text in present.values() for name in hash_variables(text)
    }
    selftest_env["REDIS_DEFAULT_USER"] = "off"
    for template_name, text in present.items():
        foreign = frozenset().union(
            *(hash_variables(other) for name, other in present.items() if name != template_name)
        )
        real = render(text, selftest_env, foreign)
        check(f"{template_name} renders", real.count("\nuser ") + 1 == real.count("user "))
        check(f"{template_name} keeps default, off", real.startswith("user default off "))
        check(f"{template_name} has no clear-text password", ">" not in real)
        check(
            f"{template_name} grants its application users only its own prefixes",
            not grants_outside(real, *COMMITTED_TEMPLATES[template_name]),
        )
    check(
        "the two instances name disjoint password variables",
        len(present) < 2 or not set.intersection(*(hash_variables(t) for t in present.values())),
    )
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
    parser.add_argument(
        "--template",
        help="scripts/redis-session-users.acl.tmpl or scripts/redis-shared-users.acl.tmpl",
    )
    parser.add_argument(
        "--out",
        help="the ACL file to write: /etc/basetool/redis/session-users.acl "
        "or /etc/basetool/redis/shared-users.acl",
    )
    parser.add_argument("--check", action="store_true", help="report drift and write nothing")
    parser.add_argument("--selftest", action="store_true", help="run the regression tests")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if not (args.env and args.template and args.out):
        parser.error("--env, --template and --out are required")
    own = os.path.basename(args.template)
    directory = os.path.dirname(os.path.abspath(args.template))
    siblings = [os.path.join(directory, name) for name in COMMITTED_TEMPLATES if name != own]
    try:
        env = parse_env_file(args.env)
        foreign = frozenset().union(
            *(hash_variables(read_text(path)) for path in siblings if os.path.exists(path))
        )
        content = render(read_text(args.template), env, foreign)
        if own in COMMITTED_TEMPLATES:
            outside = grants_outside(content, *COMMITTED_TEMPLATES[own])
            if outside:
                raise Refusal(
                    "application users are granted outside this instance's prefixes: "
                    + "; ".join(outside)
                )
    except (Refusal, EnvFileError, OSError) as exc:
        print(f"render-redis-acl: REFUSING, nothing written: {exc}", file=sys.stderr)
        return 1
    users = [line.split()[1] for line in content.splitlines()]
    default_state = content.splitlines()[0].split()[2]
    if default_state == "on":
        print(
            "render-redis-acl: WARNING - `default` is on: a password-only AUTH with the admin "
            "password reaches the whole instance. Switch it off once every application "
            "authenticates as its own user.",
            file=sys.stderr,
        )
    if args.check:
        try:
            with open(args.out, encoding="utf-8") as handle:
                current = handle.read()
        except OSError:
            current = None
        if current != content:
            print(
                f"render-redis-acl: DRIFT - {args.out} does not match the template and the .env",
                file=sys.stderr,
            )
            return 1
        print(f"render-redis-acl: {args.out} matches ({', '.join(users)}; default {default_state})")
        return 0
    write_atomically(args.out, content)
    print(
        f"render-redis-acl: wrote {args.out} ({', '.join(users)}; default {default_state}). "
        "Apply it with ACL LOAD."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
