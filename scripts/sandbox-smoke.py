"""Runs a device login and a DPoP-bound sync against a running local exchange sandbox (REQ-XCH-029).

It signs in `sandbox-member` through the device grant of `sandbox-client` exactly as a member would
in a browser, takes the DPoP-bound token, and then calls the service document, labels the
installation, reads every resource, resolves a blueprint, a ship type and a material, syncs one
blueprint, one stock lot and one ship, reads the store choices, creates a refinery order and picks it
up into the member's stock, and checks that removing a default blueprint is refused. The
gateway checks every answer against its v1 schema, so a passing run also proves the answers keep the
contract. With `--conformance` it also sends every change-set conformance fixture of
`docs/exchange/examples/v1`: each valid one as a dry run, which must be accepted, and each invalid
one, which must be refused. With `--proof-limit` it finally sends fresh proofs until the member's
cap answers `429 DPOP_PROOF_LIMIT`. With `--job-orders`, signed in as `sandbox-logistician`, it
reads only the job-order snapshot and create options and checks them against the seed; `--selftest`
runs those checks on the conformance examples without a sandbox. The login and the calls are
`scripts/sandbox_client.py`, which signs with the DPoP reference in `docs/exchange/dpop-reference`,
standard library only.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import tempfile
import time
import urllib.parse
import uuid

from sandbox_client import (
    DEFAULT_CA,
    ROOT,
    SandboxSession,
    delete_installation_key,
    open_installation_key,
)

IRIDIUM = "00000000-0000-0000-0000-000000000001"
SANDBOX_SQUADRON = "5a4d0000-0000-4000-8000-000000000101"
SANDBOX_PROFIT = "5a4d0000-0000-4000-8000-000000000102"
MATERIAL_ORDER = "5a4d0000-0000-4000-8000-000000002201"
ITEM_ORDER = "5a4d0000-0000-4000-8000-000000002202"
SQUADRON_ORDER = "5a4d0000-0000-4000-8000-000000002203"
AUEC_ORDER = "5a4d0000-0000-4000-8000-000000002204"
CLOSED_ORDER = "5a4d0000-0000-4000-8000-000000002205"
# aUEC the seed hands over to sandbox-logistician on AUEC_ORDER.
SEEDED_HANDOVER = 100000


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
            key,
            gateway=args.gateway,
            keycloak=args.keycloak,
            ca=args.ca,
            client=args.client,
            step=self.step,
            user_agent="BasetoolSandboxSmoke/1.0.0 (+https://krt-profit.github.io/basetool/)",
        )

    def step(self, label: str, ok: bool, detail: str = "") -> None:
        """Reports a step and stops the run at the first failure."""
        self.count += 1
        print(f"{'PASS' if ok else 'FAIL'} {label}" + (f" — {detail}" if not ok and detail else ""))
        if not ok:
            sys.exit(1)

    def login(self) -> None:
        """Signs the member in through the device grant, checking every page on the way."""
        self.session.login(self.args.user, self.args.password)

    def call(
        self, method: str, path: str, body: dict | None = None, write: bool = False
    ) -> tuple[int, dict]:
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
                self.step(
                    "the exchange switch reaches the gateway", False, json.dumps(answer)[:300]
                )
            print("waiting for the exchange switch to reach the gateway")
            time.sleep(10)

    def run(self) -> None:
        """Signs in and exercises the exchange: the job-order family alone, or every other one."""
        self.login()
        self.wait_for_exchange()
        if self.args.job_orders:
            self.job_orders()
        else:
            self.families()
        print(f"all {self.count} steps passed")

    def families(self) -> None:
        """Reads every resource, resolves and syncs, and runs the refinery and blueprint checks."""
        for label, method, path, body in [
            ("the service document", "GET", "/exchange/v1", None),
            (
                "the installation is labelled",
                "POST",
                "/exchange/v1/me/installation",
                {"label": "Sandbox smoke"},
            ),
            ("the blueprints", "GET", "/exchange/v1/me/blueprints", None),
            ("the stock", "GET", "/exchange/v1/me/stock", None),
            ("the ships", "GET", "/exchange/v1/me/ships", None),
            ("the refinery orders", "GET", "/exchange/v1/me/refinery-orders", None),
            ("the org demand", "GET", "/exchange/v1/me/org-demand", None),
            ("the org units", "GET", "/exchange/v1/me/org-units", None),
            ("the org stock", "GET", "/exchange/v1/org-stock", None),
            ("the locations", "GET", "/exchange/v1/catalog/locations", None),
        ]:
            status, answer = self.call(method, path, body)
            self.step(label, status in (200, 201), f"{status} {json.dumps(answer)[:300]}")
            if path == "/exchange/v1/me/stock":
                stock = answer
            if path == "/exchange/v1/me/org-demand":
                self.check_org_demand(answer)
        refs = {}
        for kind, ref in [
            ("BLUEPRINT", {"scRecord": "BP_CRAFT_SBXM_HELMET_01"}),
            ("SHIP_TYPE", {"name": "Sandbox Miner"}),
            ("MATERIAL", {"name": "Sandbox Metal"}),
        ]:
            status, answer = self.call(
                "POST", "/exchange/v1/catalog/resolve", {"kind": kind, "refs": [ref]}
            )
            resolved = status == 200 and answer["results"][0].get("status") == "resolved"
            self.step(f"a {kind.lower()} resolves", resolved, json.dumps(answer)[:300])
            refs[kind] = answer["results"][0]["ref"]
        held = next(
            (
                lot["quantity"]["amount"]
                for lot in stock.get("items", [])
                if lot["material"]["bt"] == refs["MATERIAL"]["bt"]
                and lot["location"]["name"] == "Sandbox Station Storage"
                and lot["quality"] == 800
                and not lot["stolen"]
            ),
            0,
        )
        for label, path, body in [
            (
                "a blueprint syncs",
                "/exchange/v1/me/blueprints/changes",
                {"ops": [{"op": "add", "ref": refs["BLUEPRINT"], "provenance": {"source": "log"}}]},
            ),
            (
                "a stock lot syncs",
                "/exchange/v1/me/stock/changes",
                {
                    "ops": [
                        {
                            "op": "set-quantity",
                            "material": refs["MATERIAL"],
                            "location": {"name": "Sandbox Station Storage"},
                            "quality": 800,
                            "stolen": False,
                            "quantity": {"amount": held + 2.5, "unit": "SCU"},
                            "expectedQuantity": {"amount": held, "unit": "SCU"},
                        }
                    ]
                },
            ),
            (
                "a ship syncs",
                "/exchange/v1/me/ships/changes",
                {
                    "ops": [
                        {
                            "op": "upsert",
                            "externalId": f"smoke-{uuid.uuid4().hex[:8]}",
                            "shipType": refs["SHIP_TYPE"],
                            "name": "Smoke Miner",
                            "insurance": {"kind": "LTI"},
                        }
                    ]
                },
            ),
        ]:
            self.sync(label, path, body)
        self.refinery_order(refs["MATERIAL"])
        self.default_blueprint()
        if self.args.conformance:
            self.conformance()
        if self.args.proof_limit:
            self.proof_limit()

    def job_orders(self) -> None:
        """Reads the job-order snapshot to its end and the create options, checking the seed."""
        orders: list[dict] = []
        path = "/exchange/v1/job-orders"
        while True:
            status, answer = self.call("GET", path)
            self.step(
                "a job-order snapshot page", status == 200, f"{status} {json.dumps(answer)[:300]}"
            )
            orders.extend(answer["items"])
            if not answer["hasMore"]:
                break
            path = "/exchange/v1/job-orders?" + urllib.parse.urlencode(
                {"cursor": answer["nextCursor"]}
            )
        problems = job_order_problems(orders)
        self.step(
            "the job orders are the seeded ones of IRIDIUM", not problems, "; ".join(problems)
        )
        status, answer = self.call("GET", "/exchange/v1/job-orders/create-options")
        self.step(
            "the job-order create options", status == 200, f"{status} {json.dumps(answer)[:300]}"
        )
        problems = create_option_problems(answer)
        self.step(
            "the create options offer IRIDIUM and the Profit units",
            not problems,
            "; ".join(problems),
        )

    def check_org_demand(self, answer: dict) -> None:
        """Checks the seeded demand of both memberships or the withheld response."""
        detail = json.dumps(answer)[:600]
        if self.args.demand == "withheld":
            self.step(
                "the org demand is withheld as NOT_PERMITTED",
                answer.get("reason") == "NOT_PERMITTED"
                and answer.get("materials") == []
                and answer.get("items") == []
                and "orders" not in answer,
                detail,
            )
            return
        self.step("the org demand is not withheld", "reason" not in answer, detail)
        self.step(
            "the org demand includes the Sandbox Squadron and IRIDIUM orders",
            any(
                line["material"]["name"] == "Sandbox Ore (Raw)"
                and line["source"] == "material-order"
                for line in answer["materials"]
            )
            and any(line["item"]["name"] == "Sandbox Rifle" for line in answer["items"]),
            detail,
        )

    def sync(self, label: str, path: str, body: dict) -> dict:
        """Sends a change set that must take every op, applied or unchanged.

        Returns:
            the change result.
        """
        status, answer = self.call("POST", path, body, write=True)
        taken = answer.get("applied", 0) + answer.get("unchanged", 0)
        applied = status == 200 and taken >= 1 and answer.get("notApplied", 0) == 0
        self.step(label, applied, f"{status} {json.dumps(answer)[:300]}")
        return answer

    def refinery_order(self, metal: dict) -> None:
        """Reads the store choices, creates a refinery order and picks it up into the member's stock.

        The order refines Sandbox Ore (Raw) at Sandbox Station Storage, whose station hosts a
        refinery; the pickup's one store line books the yield onto the member in one of the member's
        own units, which a member of several units must name.

        Args:
            metal: the resolved reference of Sandbox Metal, the ore's refined output.
        """
        status, answer = self.call("GET", "/exchange/v1/me/store-choices/receivers")
        self.step(
            "the store-choice receivers", status == 200, f"{status} {json.dumps(answer)[:300]}"
        )
        me = next((receiver for receiver in answer.get("items", []) if receiver["self"]), {})
        self.step(
            "the member is a receiver with a unit",
            bool(me.get("orgUnits")),
            json.dumps(answer)[:300],
        )
        unit = {"id": me["orgUnits"][0]["id"]}
        status, answer = self.call(
            "GET",
            "/exchange/v1/me/store-choices/job-orders?"
            + urllib.parse.urlencode({"material": metal["bt"]}),
        )
        self.step(
            "the store-choice job orders", status == 200, f"{status} {json.dumps(answer)[:300]}"
        )
        place = {"name": "Sandbox Station Storage"}
        created = self.sync(
            "a refinery order is created",
            "/exchange/v1/me/refinery-orders/changes",
            {
                "ops": [
                    {
                        "opId": "c1",
                        "op": "create",
                        "clientKey": f"smoke-{uuid.uuid4().hex[:8]}",
                        "orgUnit": unit,
                        "location": place,
                        "goods": [
                            {
                                "input": {"name": "Sandbox Ore (Raw)"},
                                "inputQuantity": 1000,
                                "outputQuantity": 800,
                                "quality": 800,
                            }
                        ],
                    }
                ]
            },
        )
        order = created["orders"][0]
        self.sync(
            "a refinery order is picked up into stock",
            "/exchange/v1/me/refinery-orders/changes",
            {
                "ops": [
                    {
                        "opId": "p1",
                        "op": "pickup",
                        "id": order["id"],
                        "versions": {"gameData": order["versions"]["gameData"]},
                        "lines": [
                            {
                                "material": metal,
                                "location": place,
                                "quality": 800,
                                "quantity": {"amount": 8, "unit": "SCU"},
                                "orgUnit": unit,
                            }
                        ],
                    }
                ]
            },
        )

    def default_blueprint(self, limit: float = 120.0) -> None:
        """Removes a default blueprint as a dry run, which must be refused `DEFAULT_NOT_REMOVABLE`.

        The backend grants the defaults to every member once a minute, so a run right after the
        start waits while the removal is still answered `unchanged`.
        """
        body = {"dryRun": True, "ops": [{"op": "remove", "ref": {"name": "S-38 Pistol"}}]}
        deadline = time.monotonic() + limit
        while True:
            status, answer = self.call(
                "POST", "/exchange/v1/me/blueprints/changes", body, write=True
            )
            result = (answer.get("results") or [{}])[0]
            if status != 200 or result.get("result") != "unchanged":
                break
            if time.monotonic() >= deadline:
                break
            print("waiting for the default blueprints to be granted")
            time.sleep(10)
        self.step(
            "a default blueprint cannot be removed",
            status == 200
            and result.get("result") == "rejected"
            and result.get("reason") == "DEFAULT_NOT_REMOVABLE",
            f"{status} {json.dumps(answer)[:300]}",
        )

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
        self.step(
            "the member's proof cap answers 429 DPOP_PROOF_LIMIT",
            seen.get("429 DPOP_PROOF_LIMIT", 0) == 1,
            f"{sum(seen.values())} requests in {elapsed:.1f} s: {json.dumps(seen)}",
        )
        print(
            f"proof cap reached after {sum(seen.values())} requests in {elapsed:.1f} s: "
            f"{json.dumps(seen)}"
        )

    def conformance(self) -> None:
        """Sends the change-set fixtures: valid ones as dry runs, invalid ones to be refused."""
        examples = ROOT / "docs" / "exchange" / "examples" / "v1"
        routes = {
            "blueprintChangeSet": "/exchange/v1/me/blueprints/changes",
            "stockChangeSet": "/exchange/v1/me/stock/changes",
            "shipChangeSet": "/exchange/v1/me/ships/changes",
            "refineryOrderChangeSet": "/exchange/v1/me/refinery-orders/changes",
            "orgStockChangeSet": "/exchange/v1/org-stock/changes",
        }
        for folder in sorted(examples.glob("change-set--*")):
            kind = folder.name.split("--", 1)[1]
            if kind not in routes:
                self.step(
                    f"fixture folder {folder.name} has a route",
                    False,
                    f"add {kind} to the routes of scripts/sandbox-smoke.py",
                )
            path = routes[kind]
            for fixture in sorted((folder / "valid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                body["dryRun"] = True
                status, answer = self.call("POST", path, body, write=True)
                self.step(
                    f"fixture {folder.name}/valid/{fixture.name} is accepted",
                    status == 200,
                    f"{status} {json.dumps(answer)[:300]}",
                )
            for fixture in sorted((folder / "invalid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                status, answer = self.call("POST", path, body, write=True)
                self.step(
                    f"fixture {folder.name}/invalid/{fixture.name} is refused",
                    status in (400, 413),
                    f"{status} {json.dumps(answer)[:300]}",
                )


def job_order_problems(orders: list[dict]) -> list[str]:
    """Names what in `sandbox-logistician`'s job-order snapshot differs from the seed.

    The logistician, a Logistician of IRIDIUM, reads the material, the item and both aUEC orders
    IRIDIUM requested and never the Sandbox Squadron order; the in-progress aUEC order carries the
    100 000 aUEC handed over to the logistician, and the cancelled one stays in the read.

    Args:
        orders: the items of every snapshot page.

    Returns:
        one line per difference, empty when the snapshot matches the seed.
    """
    by_id = {order["id"]: order for order in orders}
    problems = [
        f"the seeded order {order_id} is missing"
        for order_id in (MATERIAL_ORDER, ITEM_ORDER, AUEC_ORDER, CLOSED_ORDER)
        if order_id not in by_id
    ]
    if SQUADRON_ORDER in by_id:
        problems.append(f"the Sandbox Squadron order {SQUADRON_ORDER} is read")
    auec = by_id.get(AUEC_ORDER)
    if auec is not None and (
        auec.get("status") != "IN_PROGRESS" or auec.get("netHandedOver") != SEEDED_HANDOVER
    ):
        problems.append(
            f"the aUEC order {AUEC_ORDER} reads {auec.get('status')} with"
            f" {auec.get('netHandedOver')} handed over, not IN_PROGRESS with {SEEDED_HANDOVER}"
        )
    closed = by_id.get(CLOSED_ORDER)
    if closed is not None and closed.get("status") != "CANCELLED":
        problems.append(f"the order {CLOSED_ORDER} reads {closed.get('status')}, not CANCELLED")
    return problems


def create_option_problems(options: dict) -> list[str]:
    """Names what in `sandbox-logistician`'s create options differs from the seed.

    The logistician requests for IRIDIUM alone, may name Sandbox Profit and Sandbox Squadron as
    responsible with Sandbox Profit suggested, and the job-order writes are switched on.

    Args:
        options: the create options.

    Returns:
        one line per difference, empty when the options match the seed.
    """
    problems = []
    requesting = [unit["id"] for unit in options.get("requestingUnits", [])]
    if requesting != [IRIDIUM]:
        problems.append(f"the requesting units are {requesting}, not IRIDIUM alone")
    responsible = {unit["id"] for unit in options.get("responsibleUnits", [])}
    missing = sorted({SANDBOX_PROFIT, SANDBOX_SQUADRON} - responsible)
    if missing:
        problems.append(f"the responsible units lack {missing}")
    if options.get("suggestedResponsible") != SANDBOX_PROFIT:
        problems.append(
            f"the suggested responsible unit is {options.get('suggestedResponsible')},"
            f" not Sandbox Profit"
        )
    if options.get("writesEnabled") is not True:
        problems.append("the job-order writes are not enabled")
    return problems


def names_only(problems: list[str], named: str | None) -> bool:
    """Tells whether a check found exactly what a self-test case expects.

    Args:
        problems: what the check found.
        named: the text the one expected problem names, or `None` when none is expected.

    Returns:
        `True` for no problem where none is expected, or for one problem that names `named`.
    """
    if named is None:
        return not problems
    return len(problems) == 1 and named in problems[0]


def selftest() -> int:
    """Runs the job-order seed checks on the documented answer shapes, without a sandbox.

    The seeded snapshot and create options are built from the valid conformance examples of
    `docs/exchange/examples/v1` with the seed's ids, so a renamed field fails here first. Each
    changed case must name exactly its one difference.

    Returns:
        0 when every case passed, 1 otherwise.
    """
    examples = ROOT / "docs" / "exchange" / "examples" / "v1"
    page = json.loads(
        (examples / "page--jobOrderPage" / "valid" / "snapshot.json").read_text(encoding="utf-8")
    )
    template = {order["kind"]: order for order in page["items"]}
    seeded = [
        dict(template["MATERIAL"], id=MATERIAL_ORDER),
        dict(template["ITEM"], id=ITEM_ORDER),
        dict(
            template["AUEC"],
            id=AUEC_ORDER,
            status="IN_PROGRESS",
            target=250000,
            netHandedOver=SEEDED_HANDOVER,
            remaining=150000,
            overpaid=0,
            settlementOpen=False,
        ),
        dict(template["AUEC"], id=CLOSED_ORDER, status="CANCELLED"),
    ]
    material, item, auec, closed = seeded
    options = json.loads(
        (examples / "job-order-create-options" / "valid" / "logistician.json").read_text(
            encoding="utf-8"
        )
    )
    requesting = dict(options["requestingUnits"][0], id=IRIDIUM)
    profit = dict(options["responsibleUnits"][0], id=SANDBOX_PROFIT)
    squadron = dict(options["responsibleUnits"][1], id=SANDBOX_SQUADRON)
    offered = dict(
        options,
        requestingUnits=[requesting],
        responsibleUnits=[profit, squadron],
        suggestedResponsible=SANDBOX_PROFIT,
    )
    cases = [
        ("the seeded snapshot passes", job_order_problems(seeded), None),
        ("a missing order is named", job_order_problems([item, auec, closed]), MATERIAL_ORDER),
        (
            "the Sandbox Squadron order is named",
            job_order_problems([*seeded, dict(material, id=SQUADRON_ORDER)]),
            SQUADRON_ORDER,
        ),
        (
            "an aUEC order without the handover is named",
            job_order_problems([material, item, dict(auec, netHandedOver=0), closed]),
            AUEC_ORDER,
        ),
        (
            "a closed order that is no longer cancelled is named",
            job_order_problems([material, item, auec, dict(closed, status="OPEN")]),
            CLOSED_ORDER,
        ),
        ("the seeded create options pass", create_option_problems(offered), None),
        (
            "a second requesting unit is named",
            create_option_problems(dict(offered, requestingUnits=[requesting, squadron])),
            "requesting",
        ),
        (
            "a missing responsible unit is named",
            create_option_problems(dict(offered, responsibleUnits=[profit])),
            SANDBOX_SQUADRON,
        ),
        (
            "another suggested unit is named",
            create_option_problems(dict(offered, suggestedResponsible=SANDBOX_SQUADRON)),
            "suggested",
        ),
        (
            "switched-off writes are named",
            create_option_problems(dict(offered, writesEnabled=False)),
            "writes",
        ),
    ]
    failures = [
        f"{label}: {problems}"
        for label, problems, named in cases
        if not names_only(problems, named)
    ]
    for failure in failures:
        print(f"selftest FAIL {failure}")
    if failures:
        return 1
    print(f"selftest: ok ({len(cases)} cases)")
    return 0


def main() -> int:
    """Parses the command line and runs the smoke test with a throwaway key.

    Returns:
        0 when every step passed.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="https://127.0.0.1:11262")
    parser.add_argument("--keycloak", default="http://127.0.0.1:18080")
    parser.add_argument("--ca", type=pathlib.Path, default=DEFAULT_CA)
    parser.add_argument("--client", default="sandbox-client")
    parser.add_argument("--user", default="sandbox-member")
    parser.add_argument("--password", default="sandbox-member-pw-do-not-use-in-prod")
    parser.add_argument(
        "--conformance", action="store_true", help="also send the change-set conformance fixtures"
    )
    parser.add_argument(
        "--demand",
        choices=("shown", "withheld"),
        default="shown",
        help="what the member's org demand must be: shown for sandbox-member, "
        "withheld for sandbox-member-2, whose only squadron is outside the "
        "active Profit hierarchy",
    )
    parser.add_argument(
        "--proof-limit",
        action="store_true",
        help="finally flood the gateway until the member's proof cap answers "
        "429 DPOP_PROOF_LIMIT; the member is refused for about 30 s after",
    )
    parser.add_argument(
        "--job-orders",
        action="store_true",
        help="read only the job-order snapshot and create options and check what the seed gives "
        "sandbox-logistician; sign in with --user sandbox-logistician",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="check the job-order seed checks on the conformance examples, without a sandbox",
    )
    args = parser.parse_args()
    if args.selftest:
        return selftest()
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
