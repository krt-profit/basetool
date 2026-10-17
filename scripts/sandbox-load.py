"""Load-tests the Exchange API gateway and its Redis byte budget on a local sandbox (#2092).

Never point it at production: it signs in the synthetic `sandbox-load-NN` members of the sandbox
realm with their committed throwaway passwords and reads Redis through the sandbox's admin user.

`throughput` signs in N members (one installation each unless `--installations` says more) through
the device grant of `sandbox-client` and drives, at a configurable total rate, what a syncing client
does: snapshots walked in cursor pages, feed pages from the stored cursor, small stock change sets,
change sets at the 500-op cap, change sets whose answer is just under the 32 KiB result cap, blueprint
and ship change sets and catalogue resolves. It reports latency percentiles per kind of request,
status and problem-code counts, the gateway's and the backend's exchange metrics before and after,
Redis `used_memory` and the exchange budget totals, and the containers' CPU and memory.

`budget` fills the byte budget of one scope - `member`, `client` or `total` - with cached answers
just under the result cap and checks each write against a prediction made from the live budget sets
just before it: a write must be admitted exactly while the reservation (claim + 32 KiB + 512 bytes)
fits all three scopes, and refused `503 EXCHANGE_BUDGET_EXHAUSTED` from the first write that does not
fit. It then checks that each admitted write left its answer's charge and no reservation, and waits
for the answers' lifetime to pass and checks that a write is admitted again. The limits are read
from the ingest container's environment (`docker-compose.sandbox-load.yml`).

Every request is signed with the DPoP reference in `docs/exchange/dpop-reference` through
`scripts/sandbox_client.py`; the script needs the standard library and the `docker` CLI only.
"""

from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import random
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from collections import Counter, defaultdict

from sandbox_client import DEFAULT_CA, ROOT, Answer, SandboxSession

from dpop_reference.openssl import OpenSslKey

CLIENT = "sandbox-client"
STATION = "Sandbox Station Storage"
METAL = {"bt": "5a4d0000-0000-4000-8000-000000001402", "name": "Sandbox Metal"}
MINER = {"bt": "5a4d0000-0000-4000-8000-000000001302", "name": "Sandbox Miner"}
BLUEPRINTS = [{"name": "Sandbox Rifle"}, {"name": "Sandbox Knife"},
              {"scRecord": "BP_CRAFT_SBXM_HELMET_01"}]
RESOURCES = {"blueprints": "/exchange/v1/me/blueprints", "stock": "/exchange/v1/me/stock",
             "ships": "/exchange/v1/me/ships"}
DEFAULT_MIX = "feed=45,snapshot=8,small=20,large=4,conflict=2,oversize=1,blueprint=6,ships=6,resolve=8"
MAX_OPS = 500
BUDGET_DEFAULTS = {"memberBytes": 1048576, "clientBytes": 16777216, "totalBytes": 67108864,
                   "maxResultBytes": 32768, "idempotencyTtl": "PT24H"}
ENTRY_OVERHEAD = 512
CLAIM_PREFIX = "ingest:xch:idem-lock:"
CLAIM_TOKEN = 22
QUOTA_PREFIX = "ingest:xch:quota:"
QUOTA_VALUE = 20
LIVE_SUMS = ("local t = redis.call('TIME') "
             "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) "
             "local out = {} "
             "for i, k in ipairs(KEYS) do "
             "local sum = 0 local claims = 0 local quota = 0 "
             "for _, e in ipairs(redis.call('ZRANGEBYSCORE', k, '(' .. now, '+inf')) do "
             "sum = sum + (tonumber(string.match(e, '|(%d+)$')) or 0) "
             "if string.find(e, ARGV[1], 1, true) == 1 then claims = claims + 1 end "
             "if string.find(e, ARGV[2], 1, true) == 1 then quota = quota + 1 end "
             "end "
             "out[#out + 1] = sum out[#out + 1] = claims out[#out + 1] = quota "
             "end "
             "return out")


def percentile(values: list[float], share: float) -> float:
    """Returns the nearest-rank percentile of a list, or 0 for an empty one."""
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, min(len(ordered) - 1, int(round(share * len(ordered) + 0.5)) - 1))
    return ordered[rank]


def iso_seconds(duration: str) -> float:
    """Reads an ISO-8601 duration of hours, minutes and seconds, such as `PT24H` or `PT2M30S`."""
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", duration.upper())
    if not match:
        raise ValueError(f"unsupported duration {duration}")
    hours, minutes, seconds = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(seconds or 0)


class Docker:
    """Reads the sandbox containers: Redis through its admin user, metrics, stats and env."""

    def __init__(self, args: argparse.Namespace) -> None:
        """Keeps the container names and reads the throwaway Redis password of the sandbox."""
        self.args = args
        env = {}
        for line in (ROOT / "docker" / "sandbox" / "sandbox.env").read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                name, value = line.split("=", 1)
                env[name.strip()] = value.strip()
        self.redis_password = env["REDIS_PASSWORD"]

    @staticmethod
    def run(*command: str, timeout: float = 60) -> str:
        """Runs a docker command and returns its standard output, or raises on failure."""
        done = subprocess.run(["docker", *command], capture_output=True, text=True,
                              timeout=timeout, check=False)
        if done.returncode != 0:
            raise RuntimeError(f"docker {command[0]} failed: {done.stderr.strip()[:300]}")
        return done.stdout

    def redis(self, *command: str) -> list[str]:
        """Runs one redis-cli command as the sandbox admin user and returns its output lines."""
        out = self.run("exec", self.args.redis_container, "redis-cli", "--no-auth-warning",
                       "--user", "admin", "-a", self.redis_password, *command)
        return [line for line in out.splitlines() if line != ""]

    def budget_sums(self) -> dict[str, int]:
        """Returns every stored running total of the exchange budget, by scope."""
        keys = self.redis("--scan", "--pattern", "ingest:xch:budget-sum:*")
        sums = {}
        for key in sorted(keys):
            value = self.redis("GET", key)
            sums[key.removeprefix("ingest:xch:budget-sum:")] = int(value[0]) if value else 0
        return sums

    def live(self, member: str) -> dict[str, dict[str, int]]:
        """Sums the unexpired entries of the member's, the client's and the total budget set."""
        keys = [f"ingest:xch:budget:m:{CLIENT}:{member}", f"ingest:xch:budget:c:{CLIENT}",
                "ingest:xch:budget:all"]
        values = [int(v) for v in self.redis("EVAL", LIVE_SUMS, "3", *keys, CLAIM_PREFIX,
                                             QUOTA_PREFIX)]
        scopes = {}
        for index, scope in enumerate(("member", "client", "total")):
            scopes[scope] = {"bytes": values[3 * index], "claims": values[3 * index + 1],
                             "quota": values[3 * index + 2]}
        return scopes

    def memory(self) -> dict[str, int | str]:
        """Returns Redis's memory figures and key count."""
        info = {}
        for line in self.redis("INFO", "memory"):
            if ":" in line:
                name, value = line.split(":", 1)
                if name in ("used_memory", "used_memory_peak", "used_memory_dataset",
                            "maxmemory", "maxmemory_policy", "mem_fragmentation_ratio"):
                    info[name] = int(value) if value.isdigit() else value.strip()
        info["dbsize"] = int(self.redis("DBSIZE")[0])
        info["exchange_keys"] = len(self.redis("--scan", "--pattern", "ingest:xch:*"))
        return info

    def reset_budget(self) -> int:
        """Deletes the gateway's exchange store in Redis: budgets, cached answers, claims, quotas."""
        keys = self.redis("--scan", "--pattern", "ingest:xch:*")
        for start in range(0, len(keys), 200):
            self.redis("DEL", *keys[start:start + 200])
        return len(keys)

    def metrics(self, container: str, port: int, prefixes: tuple[str, ...]) -> dict[str, float]:
        """Reads a service's Prometheus exposition from inside its container, filtered by prefix.

        The scrape credential is the one the container runs with (`MONITORING_SCRAPE_*`).
        """
        try:
            out = self.run("inspect", "--format", "{{json .Config.Env}}", container)
            env = dict(item.split("=", 1) for item in json.loads(out) if "=" in item)
            user = urllib.parse.quote(env.get("MONITORING_SCRAPE_USER", ""), safe="")
            password = urllib.parse.quote(env.get("MONITORING_SCRAPE_PASSWORD", ""), safe="")
            text = self.run("exec", container, "wget", "-q", "--no-check-certificate", "-O", "-",
                            f"https://{user}:{password}@localhost:{port}/actuator/prometheus")
        except RuntimeError as error:
            return {"unreachable": str(error)}
        values = {}
        for line in text.splitlines():
            if line.startswith(prefixes) and not line.startswith("#"):
                series, _, value = line.rpartition(" ")
                try:
                    values[series] = float(value)
                except ValueError:
                    continue
        return values

    def exchange_metrics(self) -> dict[str, dict[str, float]]:
        """Reads the gateway's and the backend's exchange metrics."""
        gateway = self.metrics(
            self.args.ingest_container, 11272,
            ("basetool_ingest_", "basetool_exchange_", "basetool_http_errors",
             'http_server_requests_seconds_count{', 'http_server_requests_seconds_sum{',
             "jvm_memory_used_bytes{", "process_cpu_usage", "hikaricp_connections_active",
             "tomcat_threads_busy"))
        backend = self.metrics(
            self.args.backend_container, 11271,
            ("basetool_exchange_", 'http_server_requests_seconds_count{',
             'http_server_requests_seconds_sum{', "hikaricp_connections_active",
             "hikaricp_connections_pending", "process_cpu_usage"))
        return {"gateway": gateway, "backend": backend}

    def stats(self) -> dict[str, str]:
        """Returns `docker stats` CPU and memory of the sandbox's application containers."""
        names = [self.args.ingest_container, self.args.backend_container,
                 self.args.redis_container, self.args.keycloak_container, self.args.db_container]
        out = self.run("stats", "--no-stream", "--format",
                       "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}", *names)
        return {line.split("|")[0]: " ".join(line.split("|")[1:]) for line in out.splitlines()}

    def store_settings(self) -> dict[str, float | int | str]:
        """Reads the byte-budget settings the ingest container runs with, defaults filled in."""
        out = self.run("inspect", "--format", "{{json .Config.Env}}", self.args.ingest_container)
        env = dict(item.split("=", 1) for item in json.loads(out) if "=" in item)
        settings = dict(BUDGET_DEFAULTS)
        for name in BUDGET_DEFAULTS:
            value = env.get("APP_EXCHANGE_STORE_" + name.upper())
            if value:
                settings[name] = value if name == "idempotencyTtl" else int(value)
        settings["idempotencyTtlSeconds"] = iso_seconds(str(settings["idempotencyTtl"]))
        settings["ipLimitPerMinute"] = int(env.get("APP_RATE_LIMIT_IP_CAPACITY", "120"))
        return settings


def metric_deltas(before: dict, after: dict) -> dict[str, dict[str, float]]:
    """Lists, per service, every series that changed and the value of every gauge after."""
    changes = {}
    for service in after:
        if "unreachable" in after[service] or "unreachable" in before.get(service, {}):
            changes[service] = {"unreachable": after[service].get("unreachable")
                                or before[service].get("unreachable")}
            continue
        moved = {}
        for series, value in after[service].items():
            previous = before[service].get(series, 0.0)
            if value != previous:
                moved[series] = round(value - previous, 6) if "_total" in series or \
                    "_count" in series or "_sum" in series else value
        changes[service] = dict(sorted(moved.items()))
    return changes


class Recorder:
    """Collects one line per request: its kind, status, code, latency and answer size."""

    def __init__(self) -> None:
        """Starts empty."""
        self.lock = threading.Lock()
        self.samples: list[tuple[str, int, str, float, int, int]] = []

    def add(self, kind: str, answer: Answer, millis: float) -> None:
        """Records one answer."""
        with self.lock:
            self.samples.append((kind, answer.status, answer.code, millis, answer.size,
                                 answer.nonce_retries))

    def summary(self, seconds: float) -> dict:
        """Returns the percentiles per kind and the status and code counts."""
        per_kind: dict[str, list] = defaultdict(list)
        statuses: Counter = Counter()
        for kind, status, code, millis, size, retries in self.samples:
            per_kind[kind].append((status, code, millis, size, retries))
            statuses[f"{status} {code}".strip()] += 1
        kinds = {}
        for kind, rows in sorted(per_kind.items()):
            ok = [r[2] for r in rows if 200 <= r[0] < 300]
            every = [r[2] for r in rows]
            kinds[kind] = {
                "count": len(rows),
                "ok": len(ok),
                "codes": dict(Counter(f"{r[0]} {r[1]}".strip() for r in rows)),
                "p50_ms": round(percentile(every, 0.50), 1),
                "p90_ms": round(percentile(every, 0.90), 1),
                "p95_ms": round(percentile(every, 0.95), 1),
                "p99_ms": round(percentile(every, 0.99), 1),
                "max_ms": round(max(every), 1),
                "ok_p95_ms": round(percentile(ok, 0.95), 1),
                "mean_bytes": int(statistics.mean(r[3] for r in rows)),
                "max_bytes": max(r[3] for r in rows),
                "nonce_retries": sum(r[4] for r in rows),
            }
        every = [s[3] for s in self.samples]
        return {"requests": len(self.samples), "seconds": round(seconds, 1),
                "achieved_rps": round(len(self.samples) / seconds, 2) if seconds else 0,
                "p50_ms": round(percentile(every, 0.50), 1),
                "p95_ms": round(percentile(every, 0.95), 1),
                "p99_ms": round(percentile(every, 0.99), 1),
                "statuses": dict(statuses.most_common()), "kinds": kinds}


class Pacer:
    """Hands out request slots at a fixed total rate to every worker."""

    def __init__(self, rate: float) -> None:
        """Starts the schedule now."""
        self.interval = 1.0 / rate
        self.next = time.monotonic()
        self.lock = threading.Lock()

    def wait(self) -> None:
        """Blocks until the caller's slot."""
        with self.lock:
            slot = max(self.next, time.monotonic())
            self.next = slot + self.interval
        delay = slot - time.monotonic()
        if delay > 0:
            time.sleep(delay)


def member_names(args: argparse.Namespace) -> list[str]:
    """Names the synthetic load members this run signs in."""
    first = args.first_member
    return [f"sandbox-load-{n:02d}" for n in range(first, first + args.members)]


def sign_in(args: argparse.Namespace, user: str) -> SandboxSession:
    """Signs one installation of a member in with a fresh in-memory key."""
    session = SandboxSession(OpenSslKey.generate(), gateway=args.gateway, keycloak=args.keycloak,
                             ca=args.ca, client=CLIENT,
                             user_agent="BasetoolSandboxLoad/1.0.0 (+https://krt-profit.github.io/basetool/)")
    session.login(user, f"{user}-pw-do-not-use-in-prod")
    return session


def wait_for_exchange(session: SandboxSession, limit: float = 150.0) -> None:
    """Waits while the gateway answers `503 EXCHANGE_DISABLED` after a fresh start."""
    deadline = time.monotonic() + limit
    while True:
        answer = session.request("GET", "/exchange/v1")
        if answer.code != "EXCHANGE_DISABLED":
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("the exchange switch did not reach the gateway")
        print("waiting for the exchange switch to reach the gateway", flush=True)
        time.sleep(5)


def stock_op(quality: int, amount: float, expected: float) -> dict:
    """Builds one stock `set-quantity` op on the member's Sandbox Metal lot of one quality."""
    return {"op": "set-quantity", "material": METAL, "location": {"name": STATION},
            "quality": quality, "stolen": False, "quantity": {"amount": amount, "unit": "SCU"},
            "expectedQuantity": {"amount": expected, "unit": "SCU"}}


def conflict_set(ops: int = MAX_OPS) -> dict:
    """Builds a dry-run stock change set whose every op conflicts, answering about 31 KB."""
    return {"dryRun": True, "ops": [stock_op(q, 1.5, 987654) for q in range(ops)]}


class Worker(threading.Thread):
    """One installation syncing at the pace the shared pacer allows."""

    def __init__(self, session: SandboxSession, args: argparse.Namespace, pacer: Pacer,
                 recorder: Recorder, mix: list[tuple[str, int]], stop_at: float,
                 seed: int) -> None:
        """Keeps the installation's session, its cursors and the lots it knows."""
        super().__init__(daemon=True)
        self.session = session
        self.args = args
        self.pacer = pacer
        self.recorder = recorder
        self.mix = mix
        self.stop_at = stop_at
        self.random = random.Random(seed)
        self.cursors: dict[str, str] = {}
        self.lots: dict[int, float] = {}
        self.errors: Counter = Counter()

    def send(self, kind: str, method: str, path: str, body: dict | None = None,
             write: bool = False) -> Answer | None:
        """Waits for a slot, sends one request and records it; `None` once the run is over."""
        if time.monotonic() >= self.stop_at:
            return None
        self.pacer.wait()
        started = time.perf_counter()
        try:
            answer = self.session.request(method, path, body, write)
        except (OSError, RuntimeError) as error:
            self.errors[type(error).__name__] += 1
            answer = Answer(0, {"code": f"TRANSPORT_{type(error).__name__}"})
        self.recorder.add(kind, answer, (time.perf_counter() - started) * 1000)
        return answer

    def walk(self, resource: str, feed: bool) -> None:
        """Reads a resource to its end: a snapshot in cursor pages, or the feed from its cursor."""
        cursor = self.cursors.get(resource) if feed else None
        if feed and cursor is None:
            feed = False
        limit = 500 if feed else self.args.page
        kind = f"GET {resource} {'feed' if feed else 'snapshot'}"
        for _ in range(200):
            query = f"?limit={limit}" + (f"&cursor={cursor}" if cursor else "")
            answer = self.send(kind, "GET", RESOURCES[resource] + query)
            if answer is None or answer.status != 200:
                if answer is not None and answer.code == "CURSOR_EXPIRED":
                    self.cursors.pop(resource, None)
                return
            if resource == "stock":
                for lot in answer.body.get("items", []):
                    if lot.get("material", {}).get("bt") == METAL["bt"] and \
                            lot.get("location", {}).get("name") == STATION and not lot.get("stolen"):
                        self.lots[lot["quality"]] = lot["quantity"]["amount"]
            cursor = answer.body.get("nextCursor")
            if not answer.body.get("hasMore"):
                if cursor:
                    self.cursors[resource] = cursor
                return

    def stock_write(self, kind: str, count: int) -> None:
        """Sets `count` distinct lots of the member to their known quantity plus one."""
        qualities = self.random.sample(range(0, 1001), count)
        ops = [stock_op(q, self.lots.get(q, 0) + 1, self.lots.get(q, 0)) for q in qualities]
        answer = self.send(kind, "POST", "/exchange/v1/me/stock/changes", {"ops": ops}, True)
        if answer is None or answer.status != 200:
            return
        refused = {r["index"] for r in answer.body.get("results", [])
                   if r.get("result") not in ("applied", "unchanged")}
        for index, q in enumerate(qualities):
            if index in refused:
                self.lots.pop(q, None)
            else:
                self.lots[q] = ops[index]["quantity"]["amount"]

    def act(self, action: str) -> None:
        """Runs one action of the mix."""
        if action == "feed":
            self.walk(self.random.choice(list(RESOURCES)), True)
        elif action == "snapshot":
            self.walk(self.random.choice(list(RESOURCES)), False)
        elif action == "small":
            self.stock_write("POST stock small (1-20 ops)", self.random.randint(1, 20))
        elif action == "large":
            self.stock_write("POST stock 500 ops", MAX_OPS)
        elif action == "conflict":
            self.send("POST stock 500 conflicts (~31 KB answer)", "POST",
                      "/exchange/v1/me/stock/changes", conflict_set(), True)
        elif action == "oversize":
            body = {"dryRun": True, "ops": [{"op": "remove", "ref": {"name": "S-38 Pistol"}}] * MAX_OPS}
            self.send("POST blueprints 500 refused (>32 KiB answer)", "POST",
                      "/exchange/v1/me/blueprints/changes", body, True)
        elif action == "blueprint":
            ops = [{"op": "add", "ref": ref, "provenance": {"source": "log"}}
                   for ref in self.random.sample(BLUEPRINTS, self.random.randint(1, 3))]
            self.send("POST blueprints", "POST", "/exchange/v1/me/blueprints/changes",
                      {"ops": ops}, True)
        elif action == "ships":
            ops = [{"op": "upsert", "externalId": f"load-{uuid.uuid4().hex[:12]}",
                    "shipType": MINER, "name": "Load Miner", "insurance": {"kind": "LTI"}}
                   for _ in range(self.random.randint(1, 5))]
            self.send("POST ships", "POST", "/exchange/v1/me/ships/changes", {"ops": ops}, True)
        elif action == "resolve":
            self.send("POST catalog/resolve", "POST", "/exchange/v1/catalog/resolve",
                      {"kind": "MATERIAL", "refs": [{"name": "Sandbox Metal"}]})

    def run(self) -> None:
        """Takes the first sync's snapshots, then runs random actions of the mix until the end."""
        for resource in RESOURCES:
            self.walk(resource, False)
        actions = [name for name, _ in self.mix]
        weights = [weight for _, weight in self.mix]
        while time.monotonic() < self.stop_at:
            self.act(self.random.choices(actions, weights)[0])


def snapshot(docker: Docker) -> dict:
    """Takes the before/after picture: metrics, Redis memory, budget totals, container stats."""
    return {"at": datetime.datetime.now(datetime.UTC).isoformat(timespec="seconds"),
            "metrics": docker.exchange_metrics(), "redis": docker.memory(),
            "budget_sums": docker.budget_sums(), "containers": docker.stats()}


def throughput(args: argparse.Namespace, docker: Docker) -> dict:
    """Runs the mixed sync load and returns its report."""
    mix = [(name, int(weight)) for name, weight in
           (part.split("=") for part in args.mix.split(","))]
    names = member_names(args)
    sessions = []
    started = time.monotonic()
    for name in names:
        for _ in range(args.installations):
            sessions.append(sign_in(args, name))
    login_seconds = time.monotonic() - started
    wait_for_exchange(sessions[0])
    print(f"signed in {len(sessions)} installations of {len(names)} members "
          f"in {login_seconds:.1f} s", flush=True)
    before = snapshot(docker)
    recorder = Recorder()
    pacer = Pacer(args.rate)
    begin = time.monotonic()
    workers = [Worker(session, args, pacer, recorder, mix, begin + args.duration, index)
               for index, session in enumerate(sessions)]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed = time.monotonic() - begin
    time.sleep(2)
    after = snapshot(docker)
    for session in sessions:
        session.close()
    transport = sum((worker.errors for worker in workers), Counter())
    return {"phase": "throughput", "settings": docker.store_settings(),
            "members": len(names), "installations": len(sessions), "rate": args.rate,
            "duration": args.duration, "mix": dict(mix), "page": args.page,
            "login_seconds": round(login_seconds, 1), "result": recorder.summary(elapsed),
            "transport_errors": dict(transport), "before": before, "after": after,
            "metric_changes": metric_deltas(before["metrics"], after["metrics"])}


def reservation(member: str, settings: dict) -> int:
    """Returns what a write reserves: its claim, the largest cacheable answer and the overhead."""
    namespace = len(CLIENT) + 1 + len(member) + 1 + 64
    return len(CLAIM_PREFIX) + namespace + CLAIM_TOKEN + settings["maxResultBytes"] + ENTRY_OVERHEAD


def quota_charge(member: str) -> int:
    """Returns what the member's daily write counter is charged."""
    day = datetime.datetime.now(datetime.UTC).date().isoformat()
    return len(f"{QUOTA_PREFIX}{CLIENT}:{member}:{day}") + QUOTA_VALUE + ENTRY_OVERHEAD


def budget(args: argparse.Namespace, docker: Docker) -> dict:
    """Fills one budget scope and checks every admission against the live budget."""
    settings = docker.store_settings()
    limits = {"member": settings["memberBytes"], "client": settings["clientBytes"],
              "total": settings["totalBytes"]}
    if args.reset:
        print(f"reset: deleted {docker.reset_budget()} exchange keys in Redis", flush=True)
    names = member_names(args)
    sessions = [sign_in(args, name) for name in names]
    wait_for_exchange(sessions[0])
    memory_before = docker.memory()
    metrics_before = docker.exchange_metrics()
    rows = []
    mismatches = []
    refused_by = None
    body = conflict_set()
    for session in sessions:
        member = session.subject
        need = reservation(member, settings)
        while refused_by is None:
            live = docker.live(member)
            quota_new = 0 if live["member"]["quota"] else quota_charge(member)
            over = [scope for scope in ("member", "client", "total")
                    if live[scope]["bytes"] + quota_new + need > limits[scope]]
            started = time.perf_counter()
            answer = session.request("POST", "/exchange/v1/me/stock/changes", body, write=True)
            millis = (time.perf_counter() - started) * 1000
            after = docker.live(member)
            admitted = answer.status == 200
            refused = answer.status == 503 and answer.code == "EXCHANGE_BUDGET_EXHAUSTED"
            row = {"user": session_user(session), "status": answer.status, "code": answer.code,
                   "ms": round(millis, 1), "answer_bytes": answer.size,
                   "retry_after": answer.headers.get("retry-after"),
                   "before": {s: live[s]["bytes"] for s in live}, "quota_new": quota_new,
                   "reservation": need, "predicted_over": over,
                   "after": {s: after[s]["bytes"] for s in after},
                   "claims_after": after["member"]["claims"]}
            rows.append(row)
            if admitted == bool(over) or not (admitted or refused) or after["member"]["claims"]:
                mismatches.append(row)
            if not admitted:
                if args.scope in over and (args.scope == "member" or "member" not in over):
                    refused_by = args.scope
                break
        if refused_by is not None:
            break
    admitted_rows = [r for r in rows if r["status"] == 200]
    charges = [r["after"]["member"] - r["before"]["member"] - r["quota_new"] for r in admitted_rows]
    report = {"phase": "budget", "scope": args.scope, "settings": settings, "limits": limits,
              "writes": len(rows), "admitted": len(admitted_rows),
              "refused": len(rows) - len(admitted_rows), "refused_by": refused_by,
              "mismatches": mismatches, "settled_charge_bytes": sorted(set(charges)),
              "rows": rows, "redis_before": memory_before}
    last = rows[-1] if rows else None
    if last and last["status"] == 503:
        report["at_refusal"] = {
            "scope_bytes": last["before"][args.scope] + last["quota_new"],
            "limit": limits[args.scope],
            "headroom": limits[args.scope] - last["before"][args.scope] - last["quota_new"],
            "reservation": last["reservation"],
            "retry_after": last["retry_after"]}
    report["redis_after_fill"] = docker.memory()
    report["budget_sums_after_fill"] = docker.budget_sums()
    wait = settings["idempotencyTtlSeconds"] + 5
    if refused_by and wait <= args.max_wait:
        print(f"waiting {wait:.0f} s for the cached answers to expire", flush=True)
        time.sleep(wait)
        session = sessions[[session_user(s) for s in sessions].index(last["user"])]
        answer = session.request("POST", "/exchange/v1/me/stock/changes", body, write=True)
        live = docker.live(session.subject)
        report["after_ttl"] = {"waited_seconds": round(wait), "status": answer.status,
                               "code": answer.code,
                               "live_after": {s: live[s]["bytes"] for s in live}}
    elif refused_by:
        report["after_ttl"] = {"skipped": f"the answers live {settings['idempotencyTtl']}, "
                                          f"more than --max-wait {args.max_wait} s"}
    report["metric_changes"] = metric_deltas(metrics_before, docker.exchange_metrics())
    report["redis_after"] = docker.memory()
    for session in sessions:
        session.close()
    return report


def session_user(session: SandboxSession) -> str:
    """Names a session's member by its synthetic username."""
    return f"sandbox-load-{int(session.subject[-2:]):02d}"


def print_throughput(report: dict) -> None:
    """Prints the throughput report as a table."""
    result = report["result"]
    print(f"\n{result['requests']} requests in {result['seconds']} s = {result['achieved_rps']} rps "
          f"(target {report['rate']}), {report['installations']} installations; "
          f"p50 {result['p50_ms']} ms, p95 {result['p95_ms']} ms, p99 {result['p99_ms']} ms")
    print(f"statuses: {json.dumps(result['statuses'])}")
    print(f"{'kind':48} {'n':>6} {'p50':>7} {'p95':>7} {'p99':>7} {'max':>7} {'bytes':>7}  codes")
    for kind, row in result["kinds"].items():
        print(f"{kind:48} {row['count']:>6} {row['p50_ms']:>7} {row['p95_ms']:>7} "
              f"{row['p99_ms']:>7} {row['max_ms']:>7} {row['max_bytes']:>7}  "
              f"{json.dumps(row['codes'])}")
    for label in ("before", "after"):
        snap = report[label]
        print(f"{label}: redis used_memory {snap['redis']['used_memory']} "
              f"exchange keys {snap['redis']['exchange_keys']} budget {json.dumps(snap['budget_sums'])}")
        print(f"{label}: containers {json.dumps(snap['containers'])}")


def print_budget(report: dict) -> None:
    """Prints the budget report."""
    print(f"\nscope {report['scope']}: limits {json.dumps(report['limits'])}, "
          f"{report['writes']} writes, {report['admitted']} admitted, refused by "
          f"{report['refused_by']}, mismatches {len(report['mismatches'])}")
    print(f"settled charge per admitted answer: {report['settled_charge_bytes']}")
    if "at_refusal" in report:
        print(f"at the refusal: {json.dumps(report['at_refusal'])}")
    if "after_ttl" in report:
        print(f"after the TTL: {json.dumps(report['after_ttl'])}")
    for row in report["mismatches"]:
        print(f"MISMATCH {json.dumps(row)}")


def main() -> int:
    """Parses the command line, runs the phase and writes the JSON report.

    Returns:
        0 when the phase ran and, for `budget`, every admission matched its prediction.
    """
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("phase", choices=("throughput", "budget"))
    parser.add_argument("--gateway", default="https://127.0.0.1:11262")
    parser.add_argument("--keycloak", default="http://127.0.0.1:18080")
    parser.add_argument("--ca", type=pathlib.Path, default=DEFAULT_CA)
    parser.add_argument("--members", type=int, default=16, help="synthetic members to sign in")
    parser.add_argument("--first-member", type=int, default=1,
                        help="the first sandbox-load-NN member to use")
    parser.add_argument("--installations", type=int, default=1, help="installations per member")
    parser.add_argument("--rate", type=float, default=10.0, help="requests per second, in total")
    parser.add_argument("--duration", type=float, default=300.0, help="seconds of load")
    parser.add_argument("--page", type=int, default=100, help="page size of a snapshot walk")
    parser.add_argument("--mix", default=DEFAULT_MIX, help="action weights, name=weight,...")
    parser.add_argument("--scope", choices=("member", "client", "total"), default="member",
                        help="the budget scope to fill")
    parser.add_argument("--reset", action="store_true",
                        help="delete the gateway's exchange keys in the sandbox Redis first")
    parser.add_argument("--max-wait", type=float, default=900.0,
                        help="the longest wait for cached answers to expire")
    parser.add_argument("--out", type=pathlib.Path, help="write the JSON report here")
    parser.add_argument("--ingest-container", default="basetool-sandbox-ingest-dev-1")
    parser.add_argument("--backend-container", default="basetool-sandbox-backend-dev-1")
    parser.add_argument("--redis-container", default="basetool-sandbox-redis-dev-1")
    parser.add_argument("--keycloak-container", default="basetool-sandbox-keycloak-dev-1")
    parser.add_argument("--db-container", default="basetool-sandbox-db-backend-dev-1")
    args = parser.parse_args()
    docker = Docker(args)
    if args.phase == "throughput":
        report = throughput(args, docker)
        print_throughput(report)
        ok = True
    else:
        report = budget(args, docker)
        print_budget(report)
        ok = not report["mismatches"] and report["refused_by"] == args.scope and \
            report.get("after_ttl", {}).get("status", 200) == 200
    if args.out:
        args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"report written to {args.out}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
