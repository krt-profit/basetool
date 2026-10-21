#!/usr/bin/env python3
"""Create a GitHub-signed commit on a branch via the GraphQL
``createCommitOnBranch`` mutation.

The commit is signed with GitHub's web-flow key. The files are committed onto an
existing branch whose tip must equal ``--expected-head-oid``. The token comes from
``GH_TOKEN``, and its identity is the commit author.

Usage:
    create_signed_commit.py --repo owner/name --branch release/v1.2.3 \\
        --expected-head-oid <sha> --message "chore(release): prepare v1.2.3" \\
        [--trailer "Signed-off-by: Name <email>"] [--delete PATH ...] [--dry-run] [FILE ...]

Prints the created commit's OID on success; exits non-zero with the GraphQL
error otherwise.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

GRAPHQL_ENDPOINT = "https://api.github.com/graphql"

_MUTATION = (
    "mutation($input: CreateCommitOnBranchInput!) {\n"
    "  createCommitOnBranch(input: $input) { commit { oid url } }\n"
    "}"
)


def build_payload(
    repo: str,
    branch: str,
    head_oid: str,
    message: str,
    trailers: list[str],
    files: list[str],
    deletions: list[str] | None = None,
) -> dict:
    """Build the GraphQL request body for ``createCommitOnBranch``.

    :param repo: ``owner/name`` of the target repository.
    :param branch: branch name to commit onto; it must already exist.
    :param head_oid: the branch's current tip SHA, asserted as ``expectedHeadOid``.
    :param message: the commit headline.
    :param trailers: commit-body lines; blank entries are dropped.
    :param files: working-tree paths to commit, each Base64-encoded.
    :param deletions: repository paths the commit deletes.
    :return: a ``{"query", "variables"}`` dict ready to POST to the GraphQL API.
    """
    additions = []
    for path in files:
        with open(path, "rb") as handle:
            encoded = base64.b64encode(handle.read()).decode("ascii")
        additions.append({"path": path, "contents": encoded})

    message_obj: dict[str, str] = {"headline": message}
    body = "\n".join(trailer for trailer in trailers if trailer)
    if body:
        message_obj["body"] = body

    return {
        "query": _MUTATION,
        "variables": {
            "input": {
                "branch": {
                    "repositoryNameWithOwner": repo,
                    "branchName": branch,
                },
                "expectedHeadOid": head_oid,
                "message": message_obj,
                "fileChanges": {
                    "additions": additions,
                    "deletions": [{"path": path} for path in deletions or []],
                },
            }
        },
    }


def create_commit(payload: dict, token: str) -> str:
    """POST the GraphQL payload and return the created commit's OID.

    :param payload: the ``{"query", "variables"}`` body from :func:`build_payload`.
    :param token: a GitHub token with ``contents: write`` on the repository.
    :return: the OID (SHA) of the commit created by ``createCommitOnBranch``.
    :raises SystemExit: on a transport error or any GraphQL ``errors`` entry.
    """
    request = urllib.request.Request(
        GRAPHQL_ENDPOINT,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "krt-profit-release-prepare",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit(f"GraphQL HTTP {error.code}: {detail}")
    except urllib.error.URLError as error:
        raise SystemExit(f"GraphQL request failed: {error.reason}")

    if result.get("errors"):
        raise SystemExit("GraphQL errors: " + json.dumps(result["errors"]))
    try:
        return result["data"]["createCommitOnBranch"]["commit"]["oid"]
    except (KeyError, TypeError):
        raise SystemExit("Unexpected GraphQL response: " + json.dumps(result))


def selftest() -> int:
    """Check that additions and deletions reach the ``fileChanges`` of the payload.

    :return: 0 when every check passed, 1 otherwise.
    """
    with tempfile.TemporaryDirectory(prefix="signed-commit-selftest-") as directory:
        changelog = os.path.join(directory, "CHANGELOG.md")
        with open(changelog, "wb") as handle:
            handle.write(b"# Changelog\n")
        payload = build_payload("krt-profit/basetool", "release/v1.0.0", "abc", "chore: x", [],
                                [changelog], ["changelog.d/suche.md", "changelog.d/hangar.md"])
    changes = payload["variables"]["input"]["fileChanges"]
    expected = {
        "additions": [{"path": changelog, "contents": "IyBDaGFuZ2Vsb2cK"}],
        "deletions": [{"path": "changelog.d/suche.md"}, {"path": "changelog.d/hangar.md"}],
    }
    if changes != expected:
        print(f"SELFTEST FAIL: fileChanges\n  expected {expected}\n  got      {changes}")
        return 1
    print("selftest: ok")
    return 0


def main() -> None:
    """Parse arguments, build the payload, and create (or preview) the commit."""
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--repo", required=True, help="owner/name of the repo.")
    parser.add_argument("--branch", required=True,
                        help="Existing branch to commit onto.")
    parser.add_argument("--expected-head-oid", required=True,
                        help="Current branch tip SHA (optimistic-lock guard).")
    parser.add_argument("--message", required=True, help="Commit headline.")
    parser.add_argument("--trailer", action="append", default=[],
                        help="Commit-body line, repeatable (e.g. Signed-off-by).")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print the payload (contents elided) without POSTing.")
    parser.add_argument("--delete", action="append", default=[],
                        help="Repository path the commit deletes, repeatable.")
    parser.add_argument("files", nargs="*", help="Working-tree paths to commit.")
    args = parser.parse_args()
    if not args.files and not args.delete:
        parser.error("nothing to commit: pass at least one FILE or --delete")

    payload = build_payload(
        args.repo, args.branch, args.expected_head_oid,
        args.message, args.trailer, args.files, args.delete,
    )

    if args.dry_run:
        preview = json.loads(json.dumps(payload))
        for addition in preview["variables"]["input"]["fileChanges"]["additions"]:
            addition["contents"] = f"<{len(addition['contents'])} base64 chars>"
        print(json.dumps(preview, indent=2))
        return

    token = os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("GH_TOKEN environment variable is not set.")
    print(create_commit(payload, token))


if __name__ == "__main__":
    main()
