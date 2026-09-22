# ADR-0173 — A security alert watches a signal the deployment cannot produce itself

- **Status:** Proposed
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** [`observability.md`](../specs/observability.md) > *REQ-OBS-018* ·
  [`apps.yml`](../../monitoring/prometheus/alerts/apps.yml) (`BackendAuthFailureSpike`,
  `BackendUnauthenticatedFlood`, `IngestAuthFailureSpike`) ·
  [`prometheus.yml`](../../monitoring/prometheus/prometheus.yml) (`blackbox-http-auth`) ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) — the vhost whose liveness probe
  is the traffic in question

## Context

`basetool_auth_failures_total{reason}` was added so that a 401 spike could be read without raising a
log level on an internet-facing surface (REQ-OBS-018). The label is the RFC 6750 bearer error code,
so an operator could tell a rejected token from a malformed header from a missing scope.

Measured on production on 2026-09-13, it could tell them nothing at all:

|                                           |        Backend        |       Ingest       |
|-------------------------------------------|-----------------------|--------------------|
| 401s since process start (13.5 h)         | **6 618**             | **4 927**          |
| share of all requests                     | **8.5 %** (of 77 776) | —                  |
| `reason="invalid_token"`                  | **0**                 | **0**              |
| `reason="other"`                          | **6 618**             | **4 927**          |
| `uri="UNKNOWN"` on `http_server_requests` | **6 618 of 6 618**    | **4 927 of 4 927** |

Three findings, and they compound:

**The reason label had no slot for the common case.** A caller that presents no `Authorization`
header never reaches `BearerTokenAuthenticationFilter`'s failure path. `ExceptionTranslationFilter`
raises a plain `InsufficientAuthenticationException`, which is not an `OAuth2AuthenticationException`
and therefore carries no RFC code — RFC 6750 §3.1 says a resource server SHOULD omit the code
entirely for exactly this case. The mapper collapsed it to `other`. Since *every* real 401 on these
surfaces is credential-less, 100 % of the traffic landed on the one literal that means "unclassified".

**The traffic is our own monitoring.** Of 601 401s at the edge in a sampled hour, **600 carried the
user agent `Blackbox-Exporter/0.28.0`** and exactly one was an outside scanner. Three blackbox jobs
probe `https://api.profit-base.online/api/v1/terms/status` at a 30 s interval — deliberately, because
the API vhost is a default-deny allow-list that 404s its own root, so an allow-listed path answering
401 is the only thing that proves the backend behind it is alive (REQ-OBS-018). The ingest gateway's
root is probed the same way. The 401 volume is therefore a *designed* constant, not a signal.

**So the alert was measuring the monitoring plane.** `BackendAuthFailureSpike` fired on
`rate(basetool_http_error_total{code="UNAUTHENTICATED"}[5m]) > 0.2`, and the probe floor alone is
~0.1/s — roughly three quarters of the threshold consumed before a single real request arrives. The
floor also grows every time a probe target is added, silently, in a file nobody reads as a tuning
input. And the alert's own remediation text told the operator to split by `reason` into three values,
none of which ever occurs.

This was half-foreseen and never followed up. [`MEMBERS_ONLY_PLAN.md`](../archive/MEMBERS_ONLY_PLAN.md) §
*Risks* predicted that closing the anonymous surface would push scanners from `200` to `401` and
that "`BackendAuthFailureSpike` may fire during the first days. Measure, then retune (WP-E)." The
measurement is this ADR, eight days late, and it found a larger cause than the one predicted: not
scanners, but the deployment's own probes — which no amount of retuning a volume threshold would
have made into a signal.

`uri="UNKNOWN"` compounds it: Micrometer tags the URI from the handler mapping, and a filter-chain
rejection never reaches the `DispatcherServlet`, so no 401 anywhere carries a path. Neither does the
access log — `RequestLoggingFilter` is ordered inside the security chain and never runs for a
rejected request, and the 401 is logged at `DEBUG` by design (REQ-OBS-001) to keep scanner noise out.
An operator asking "which endpoint, from whom, why" about 8.5 % of all traffic had no answer at all.

## Decision

**A security alert watches a series the deployment's own infrastructure cannot produce.**

1. **Split the no-credential case out of `other`.** `InsufficientAuthenticationException` and
   `AuthenticationCredentialsNotFoundException` map to a new bounded literal `no_credentials`, in
   both the backend and the ingest gateway. It is deliberately not an RFC code, because the RFC
   defines none; the distinction it draws — *brought nothing* versus *brought something that failed*
   — is the counter's entire diagnostic value. `other` now means what it always claimed to: an
   unenumerated failure mode, and worth investigating precisely because it should be empty.

2. **Point the brute-force alerts at `reason="invalid_token"`.** A presented-and-rejected token is
   what credential guessing looks like, and it is the one thing a blackbox probe, a scanner and a
   pre-login navigation can never generate — they present nothing. The series sits at a true zero, so
   the threshold drops from `0.2` to `0.05` and *means* something. `IngestAuthFailureSpike` moves
   onto the dedicated counter for the same reason and loses its dependence on the `uri`-less
   `http_server_requests_seconds_count`.

3. **Keep the volume half as its own alert, on both surfaces.** `BackendUnauthenticatedFlood` and
   `IngestUnauthenticatedFlood` watch `reason="no_credentials"` at `> 1/s for 15m` — an order of magnitude above the probe floor, so it
   means scanning or a looping client rather than a Tuesday. The floor is written down in
   REQ-OBS-018 and must be re-checked whenever a probe target is added.

## Alternatives rejected

- **Filter probe traffic out of the counter by user agent.** A security metric an attacker can
  silence by setting a header is worse than no metric. Rejected on principle, not on effort.
- **Filter it by source address.** Less spoofable, but it couples the app to the monitoring
  network's layout, and hairpinned probe traffic is already SNAT'd to the Docker bridge gateways —
  the same effect that makes the edge-deny probes assert nothing from inside. It would work until a
  network change, then fail silently open.
- **Stop probing an authenticated path.** That is what the probe is *for*: the vhost 404s its own
  root, so a root probe asserts nothing about the backend and fails on a healthy edge. Removing it
  trades a noisy metric for no liveness coverage.
- **Only raise the threshold.** Cheapest, and it leaves the counter polluted, the `reason`
  breakdown useless, and a floor that drifts upward with each added probe while the threshold stays
  where someone once put it.

## Consequences

- A 401 is now diagnosable from the metric alone, which is what REQ-OBS-018 promised and did not
  deliver: `no_credentials` is background, `invalid_token` is somebody trying, `invalid_request` is a
  broken client, `other` is a bug.
- `BackendAuthFailureSpike` changes meaning. Its summary becomes "Rejected-token spike", and a
  volume spike no longer reaches it — that is `BackendUnauthenticatedFlood`. Anything reading the old
  alert as a traffic signal must move.
- The probe floor becomes a documented, re-checkable number instead of an accident. Adding a
  blackbox target is now a change with a stated monitoring consequence.
- `uri="UNKNOWN"` is **not** fixed by this decision and stays a known gap: the path of a rejected
  request is recoverable only from the edge access log. Closing it would mean either a bounded
  surface label on the counter or moving `RequestLoggingFilter` outside the security chain, and both
  are larger changes than the defect at hand justifies. Stated here so the next reader does not
  rediscover it as a surprise.
