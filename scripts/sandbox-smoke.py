"""Runs a device login and a DPoP-bound sync against a running local exchange sandbox (REQ-XCH-029).

It signs in `sandbox-member` through the device grant of `sandbox-client` exactly as a member would
in a browser, takes the DPoP-bound token, and then calls the service document, labels the
installation, reads every resource, resolves a blueprint, a ship type and a material, and syncs one
blueprint, one stock lot and one ship, and checks that removing a default blueprint is refused. The
gateway checks every answer against its v1 schema, so a passing run also proves the answers keep the
contract. With `--conformance` it also sends every change-set conformance fixture of
`docs/exchange/examples/v1`: each valid one as a dry run, which must be accepted, and each invalid
one, which must be refused. With `--proof-limit` it finally sends fresh proofs until the member's
cap answers `429 DPOP_PROOF_LIMIT`. The login and the calls are `scripts/sandbox_client.py`, which
signs with the DPoP reference in `docs/exchange/dpop-reference`, standard library only.
"""

import argparse
import json
import pathlib
import sys
import tempfile
import time
import uuid

from sandbox_client import DEFAULT_CA, ROOT, SandboxSession

from dpop_reference import delete_installation_key, open_installation_key


class Smoke:
    """One smoke run: the device login, then the calls, each reported as a step."""

    def __init__(self, args: argparse.Namespace, key) -> None:
        """Keeps the settings and opens the installation's session.

        Args:
            args: the command line.
            key: the DPoP key of this run.
        """
        self.args = args
        self.count = 0
        self.session = SandboxSession(
            key, gateway=args.gateway, keycloak=args.keycloak, ca=args.ca, client=args.client,
            step=self.step,
            user_agent="BasetoolSandboxSmoke/1.0.0 (+https://krt-profit.github.io/basetool/)")

    def step(self, label: str, ok: bool, detail: str = "") -> None:
        """Reports a step and stops the run at the first failure."""
        self.count += 1
        print(f"{'PASS' if ok else 'FAIL'} {label}" + (f" — {detail}" if not ok and detail else ""))
        if not ok:
            sys.exit(1)

    def login(self) -> None:
        """Signs the member in through the device grant, checking every page on the way."""
        self.session.login(self.args.user, self.args.password)

    def call(self, method: str, path: str, body: dict | None = None,
             write: bool = False) -> tuple[int, dict]:
        """Calls the gateway with a DPoP proof, retrying once on a nonce challenge."""
        return self.session.call(method, path, body, write)

    def wait_for_exchange(self, limit: float = 150.0) -> None:
        """Waits, honouring `Retry-After`, while the gateway answers `503 EXCHANGE_DISABLED`.

        The seed switches the exchange on in the database; the backend mirrors it into Redis within
        its reconcile interval and the gateway reads the mirror within its refresh interval.
        """
        deadline = time.monotonic() + limit
        while True:
            status, answer = self.call("GET", "/exchange/v1")
            if status != 503 or answer.get("code") != "EXCHANGE_DISABLED":
                return
            if time.monotonic() >= deadline:
                self.step("the exchange switch reaches the gateway", False, json.dumps(answer)[:300])
            print("waiting for the exchange switch to reach the gateway")
            time.sleep(10)

    def run(self) -> None:
        """Signs in and exercises the exchange."""
        self.login()
        self.wait_for_exchange()
        for label, method, path, body in [
            ("the service document", "GET", "/exchange/v1", None),
            ("the installation is labelled", "POST", "/exchange/v1/me/installation",
             {"label": "Sandbox smoke"}),
            ("the blueprints", "GET", "/exchange/v1/me/blueprints", None),
            ("the stock", "GET", "/exchange/v1/me/stock", None),
            ("the ships", "GET", "/exchange/v1/me/ships", None),
            ("the org demand", "GET", "/exchange/v1/me/org-demand", None),
            ("the org units", "GET", "/exchange/v1/me/org-units", None),
            ("the org stock", "GET", "/exchange/v1/org-stock", None),
            ("the locations", "GET", "/exchange/v1/catalog/locations", None),
        ]:
            status, answer = self.call(method, path, body)
            self.step(label, status in (200, 201), f"{status} {json.dumps(answer)[:300]}")
            if path == "/exchange/v1/me/stock":
                stock = answer
            if path == "/exchange/v1/me/org-demand" and self.args.demand == "shown":
                self.step("the org demand is not withheld", "reason" not in answer,
                          json.dumps(answer)[:300])
            if path == "/exchange/v1/me/org-demand" and self.args.demand == "withheld":
                self.step("the org demand is withheld as NOT_PERMITTED",
                          answer.get("reason") == "NOT_PERMITTED", json.dumps(answer)[:300])
        refs = {}
        for kind, ref in [("BLUEPRINT", {"scRecord": "BP_CRAFT_SBXM_HELMET_01"}),
                          ("SHIP_TYPE", {"name": "Sandbox Miner"}),
                          ("MATERIAL", {"name": "Sandbox Metal"})]:
            status, answer = self.call("POST", "/exchange/v1/catalog/resolve",
                                       {"kind": kind, "refs": [ref]})
            resolved = status == 200 and answer["results"][0].get("status") == "resolved"
            self.step(f"a {kind.lower()} resolves", resolved, json.dumps(answer)[:300])
            refs[kind] = answer["results"][0]["ref"]
        held = next((lot["quantity"]["amount"] for lot in stock.get("items", [])
                     if lot["material"]["bt"] == refs["MATERIAL"]["bt"]
                     and lot["location"]["name"] == "Sandbox Station Storage"
                     and lot["quality"] == 800 and not lot["stolen"]), 0)
        for label, path, body in [
            ("a blueprint syncs", "/exchange/v1/me/blueprints/changes",
             {"ops": [{"op": "add", "ref": refs["BLUEPRINT"],
                       "provenance": {"source": "log"}}]}),
            ("a stock lot syncs", "/exchange/v1/me/stock/changes",
             {"ops": [{"op": "set-quantity", "material": refs["MATERIAL"],
                       "location": {"name": "Sandbox Station Storage"}, "quality": 800,
                       "stolen": False, "quantity": {"amount": held + 2.5, "unit": "SCU"},
                       "expectedQuantity": {"amount": held, "unit": "SCU"}}]}),
            ("a ship syncs", "/exchange/v1/me/ships/changes",
             {"ops": [{"op": "upsert", "externalId": f"smoke-{uuid.uuid4().hex[:8]}",
                       "shipType": refs["SHIP_TYPE"], "name": "Smoke Miner",
                       "insurance": {"kind": "LTI"}}]}),
        ]:
            status, answer = self.call("POST", path, body, write=True)
            taken = answer.get("applied", 0) + answer.get("unchanged", 0)
            applied = status == 200 and taken >= 1 and answer.get("notApplied", 0) == 0
            self.step(label, applied, f"{status} {json.dumps(answer)[:300]}")
        self.default_blueprint()
        if self.args.conformance:
            self.conformance()
        if self.args.proof_limit:
            self.proof_limit()
        print(f"all {self.count} steps passed")

    def default_blueprint(self, limit: float = 120.0) -> None:
        """Removes a default blueprint as a dry run, which must be refused `DEFAULT_NOT_REMOVABLE`.

        The backend grants the defaults to every member once a minute, so a run right after the
        start waits while the removal is still answered `unchanged`.
        """
        body = {"dryRun": True,
                "ops": [{"op": "remove", "ref": {"name": "S-38 Pistol"}}]}
        deadline = time.monotonic() + limit
        while True:
            status, answer = self.call("POST", "/exchange/v1/me/blueprints/changes", body,
                                       write=True)
            result = (answer.get("results") or [{}])[0]
            if status != 200 or result.get("result") != "unchanged":
                break
            if time.monotonic() >= deadline:
                break
            print("waiting for the default blueprints to be granted")
            time.sleep(10)
        self.step("a default blueprint cannot be removed",
                  status == 200 and result.get("result") == "rejected"
                  and result.get("reason") == "DEFAULT_NOT_REMOVABLE",
                  f"{status} {json.dumps(answer)[:300]}")

    def proof_limit(self, requests: int = 800) -> None:
        """Sends one fresh proof per request until the member's cap answers `429 DPOP_PROOF_LIMIT`.

        A proof counts for about 30 s and the member may hold 600, so the requests are sent as
        fast as one connection at a time allows; the member's client limit refuses most of them
        `RATE_LIMITED` first, which still counts their proofs.
        """
        seen: dict[str, int] = {}
        started = time.monotonic()
        for _ in range(requests):
            status, answer = self.call("GET", "/exchange/v1")
            key = f"{status} {answer.get('code', '')}".strip()
            seen[key] = seen.get(key, 0) + 1
            if answer.get("code") == "DPOP_PROOF_LIMIT":
                break
        elapsed = time.monotonic() - started
        self.step("the member's proof cap answers 429 DPOP_PROOF_LIMIT",
                  seen.get("429 DPOP_PROOF_LIMIT", 0) == 1,
                  f"{sum(seen.values())} requests in {elapsed:.1f} s: {json.dumps(seen)}")
        print(f"proof cap reached after {sum(seen.values())} requests in {elapsed:.1f} s: "
              f"{json.dumps(seen)}")

    def conformance(self) -> None:
        """Sends the change-set fixtures: valid ones as dry runs, invalid ones to be refused."""
        examples = ROOT / "docs" / "exchange" / "examples" / "v1"
        routes = {"blueprintChangeSet": "blueprints", "stockChangeSet": "stock",
                  "shipChangeSet": "ships"}
        for folder in sorted(examples.glob("change-set--*")):
            resource = routes[folder.name.split("--", 1)[1]]
            path = f"/exchange/v1/me/{resource}/changes"
            for fixture in sorted((folder / "valid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                body["dryRun"] = True
                status, answer = self.call("POST", path, body, write=True)
                self.step(f"fixture {folder.name}/valid/{fixture.name} is accepted",
                          status == 200, f"{status} {json.dumps(answer)[:300]}")
            for fixture in sorted((folder / "invalid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                status, answer = self.call("POST", path, body, write=True)
                self.step(f"fixture {folder.name}/invalid/{fixture.name} is refused",
                          status in (400, 413), f"{status} {json.dumps(answer)[:300]}")


def main() -> int:
    """Parses the command line and runs the smoke test with a throwaway key.

    Returns:
        0 when every step passed.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="https://127.0.0.1:11262")
    parser.add_argument("--keycloak", default="http://127.0.0.1:18080")
    parser.add_argument("--ca", type=pathlib.Path,
                        default=DEFAULT_CA)
    parser.add_argument("--client", default="sandbox-client")
    parser.add_argument("--user", default="sandbox-member")
    parser.add_argument("--password", default="sandbox-member-pw-do-not-use-in-prod")
    parser.add_argument("--conformance", action="store_true",
                        help="also send the change-set conformance fixtures")
    parser.add_argument("--demand", choices=("shown", "withheld"), default="shown",
                        help="what the member's org demand must be: shown for sandbox-member, "
                             "withheld for sandbox-member-2, whose only squadron takes no part in "
                             "the profit sharing")
    parser.add_argument("--proof-limit", action="store_true",
                        help="finally flood the gateway until the member's proof cap answers "
                             "429 DPOP_PROOF_LIMIT; the member is refused for about 30 s after")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        key_file = pathlib.Path(tmp) / "smoke-dpop.pem"
        name = f"basetool-sandbox-smoke-{uuid.uuid4().hex[:8]}"
        key = open_installation_key(name, key_file=key_file)
        try:
            Smoke(args, key).run()
        finally:
            delete_installation_key(name, key_file=key_file)
    return 0


if __name__ == "__main__":
    sys.exit(main())
