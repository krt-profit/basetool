# ADR-0095 — Ship the JVM apps' container stdout/stderr to Loki under distinct labels to capture native/JVM errors

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** @greluc
- **Related:** REQ-OBS-007 (amended here) · REQ-OBS-014 · REQ-OBS-004 · ADR-0072 (established the file-only shipping decision this amends) · #1274 (`init: true` zombie-reaping root-cause fix) · #1041 item 24 (`mon-*` stdout shipping precedent) · the 2026-07-12 ingest native-thread pids-cap OOM

## Context

REQ-OBS-007 (epic #936, ADR-0072) ships the three JVM modules (backend / frontend / ingest) to
Loki via their **masked JSON file** only — `loki.source.file` → `loki.process.app_json`, with the
`level` field promoted to a Loki label. The Alloy container-log keep-list deliberately **excluded**
those three services from docker-log shipping, on the rationale that the JSON file is already
PII-masked at source (so no shipper masking is needed) and that shipping the docker stream too would
double-ship the same lines.

The 2026-07-12 ingest native-thread OOM (the container hit its cgroup `pids: 2048` cap, JVM could no
longer `clone()`, `pthread_create failed (EAGAIN)` / `unable to create native thread`) exposed a hole
in that decision: the **decisive native line is printed by the JVM/glibc directly to the container's
stderr, outside logback**. It therefore lands in `docker logs` (captured by the `json-file` driver)
but **never** in the logback JSON file — so it is invisible to Loki. The same is true of every JVM
fatal-error path (the `hs_err` preamble, a segfault, OOMKill-adjacent stderr) and of any
default-uncaught-exception-handler stack trace the JVM prints to `System.err`. When such a crash
happens, the operator's only evidence today is `docker logs` (SSH-only, capped at the 5×10 MB
rotation), not Loki.

Two facts bound the value:

- The **root cause of that specific incident is fixed** (`init: true` reaps the healthcheck
  `ssl_client` zombies, #1274) and **`JvmThreadsHigh`** (`apps.yml`) is the **leading** indicator, so
  a native-thread crash should not recur. This stream is therefore a **lagging forensic breadcrumb**,
  not a prevention — but it also closes the *general* blind spot for any future JVM/native crash.
- The apps' **own** console lines *are* masked at source: the prod logback config keeps the CONSOLE
  appender active and it runs through `PiiMaskingPatternLayout` → `PiiMasker`. Only the truly-raw,
  non-logback stderr is unmasked.

## Decision

1. **Ship the three apps' container stdout/stderr to Loki** via `loki.source.docker` (the same
   GET-only `socket-proxy` path already used for the `mon-*` / `npm` streams), by adding
   `backend|frontend|ingest` to the Alloy keep-list.

2. **Distinct labels.** Map them to `app=<svc>-stdout` (`backend-stdout` / `frontend-stdout` /
   `ingest-stdout`), kept separate from the JSON-file `app=<svc>` streams. Reusing `app="backend"`
   would mix masked-JSON (which carries the `level` label) with raw stdout under one label and muddy
   `{app="backend",level="error"}` queries.

3. **Mask in the shipper.** Route these streams through `loki.process.container_mask` (the renamed
   `mon_mask`) with a `stage.match` that mirrors the source-side `PiiMasker` — JWT, e-mail and
   bearer-token-keyword scrubbing — as **defense-in-depth** for exactly the residual the source masker
   does not cover (raw non-logback stderr). RE2 patterns, no possessive groups.

4. **No new IP/username retention.** These streams carry no client IPs or usernames by design and are
   masked, so they are **PII-free operational logging** like the `mon-*` streams: they inherit the
   global 744 h retention and have **no** REQ-OBS-010 privacy-policy impact.

5. **The native-thread rule ships STAGED.** `JvmNativeThreadExhaustion`
   (`monitoring/loki/rules/fake/basetool-log-alerts.yml`) is committed **commented-out** pending a
   test/prod-stack verification that the native line actually lands in the shipped stream — avoiding
   the dead-alert trap (REQ-OBS-014). The verification step lives in the rollout runbook.

## Consequences

- **Positive:** JVM/glibc fatal native errors are visible in Loki for the first time; a native-thread
  OOM (or any future native crash) leaves a durable breadcrumb instead of only an SSH-only,
  rotation-capped `docker logs` trail.
- **Accepted cost:** the full INFO console stream (~150 MB/day/app, already present as masked JSON) is
  double-shipped → roughly 10–15 GB extra in Loki at 744 h retention; a residual raw-stderr PII surface
  remains (mitigated, not eliminated, by the shipper mask); two more low-cardinality `app` labels.
- **Reverses** the REQ-OBS-007 file-only decision (ADR-0072) for these three streams. @greluc signed
  off on the reversal (2026-07-12) and REQ-OBS-007 is amended in the same change.

## Alternatives considered

- **Narrow "drop logback-formatted lines, keep only raw stderr"** — cheaper (no double-ship), but the
  `stage.drop` discriminator regex is fragile on multi-line logback stack-trace continuations (only the
  first line carries the timestamp prefix), and the owner chose the full stream for simplicity and
  completeness.
- **Match the native line on the existing JSON stream** — unreliable: in the incident the JVM was too
  thread-starved to emit the line through logback at all, so the JSON stream would not have caught it.
- **Leave it as a documented known gap** — rejected; the blind spot is general to every JVM/native
  crash, not specific to the one fixed incident.

