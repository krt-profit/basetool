# 10. Quality requirements

## 10.1 The quality tree

```
Profit Basetool
├── Confidentiality  ── no surface without a login · per-member isolation · no PII in logs
├── Correctness      ── no lost update · ledger integrity · schema validated at start-up
├── Recoverability   ── restorable off-site backup, proven by a drill, not by a green job
├── Operability      ── one person can deploy, diagnose and restore it
├── Auditability     ── every mutation in an audited area is on an append-only trail
└── Responsiveness   ── shared surfaces update in place; a slow dependency degrades, not blanks
```

## 10.2 Quality scenarios

Each scenario is written so it could be *falsified*. The right-hand column names what would notice —
because a quality goal nobody measures is a preference, and a gate that cannot fail is decoration.

| # | Scenario | What notices |
| --- | --- | --- |
| Q1 | An unauthenticated request reaches any URL other than the landing page or the legal pages and receives content. | `blackbox-members-only` and `blackbox-public-surface` probes; ADR-0159 |
| Q2 | Two members edit the same aggregate; the later write silently overwrites the earlier. | Optimistic locking → HTTP 409; the section-counter tests |
| Q3 | Two members edit *unrelated sections* of the same aggregate and one gets a 409. | Treated as a defect: per-section counters (§8.4) |
| Q4 | A member's name, e-mail or a token appears in a log line. | The unconditional logging rule; the Alloy masking gate (`alloy-log-masking` CI job) |
| Q5 | The nightly backup reports success while its snapshot cannot actually be restored. | `restore-drill.sh` scores **seven** artifacts from the restored snapshot; `RestoreDrillArtifactNotRestorable` |
| Q6 | A public name's certificate is within 14 days of expiry. | `CertificateExpiringSoon` on `probe_ssl_earliest_cert_expiry`; the certificate *files* are covered separately by the `iri-cert-expiry` collector and `CertificateFileExpiringSoon` |
| Q7 | A deploy applies an image whose signature does not verify. | `deploy.sh` Cosign-verifies every digest *before* pulling; fail-closed |
| Q8 | A scheduled job silently stops running. | Per-timer `last_success_timestamp` metrics with staleness alerts |
| Q9 | Keycloak advertises an issuer the applications do not validate, and all three die at start-up. | The `keycloak-issuer` CI gate renders every stack through `docker compose config` and compares them |
| Q10 | A new mutation lands in an audited area with no audit event. | `REQ-AUDIT-001` coverage list; review |
| Q11 | A metric is renamed and a dashboard or alert rule silently breaks. | The monitoring-moves-with-the-change rule; the dashboard and rule gates |
| Q12 | A backend call hangs and the page blanks instead of degrading. | Resilience4j Timeout/CircuitBreaker with logged transitions; `REQ-FE-*` |

## 10.3 What operationalises them

- **182 alert rules** in six rule files — `apps`, `business`, `containers-runtime`,
  `infrastructure`, `meta`, `ops-automation` — with `promtool` unit tests beside them. The `meta`
  rules are the ones that matter most and are easiest to forget: they alert on the *monitoring
  itself* being silent, which is the failure mode that makes every other alert useless.
- **Thirteen Grafana dashboards** — host, containers, Spring apps, PostgreSQL, Redis, Keycloak,
  Basetool operations, edge, SSH host authentication, logs and errors, ops automation, meta
  monitoring, tracing.
- **A large blackbox probe fleet** — not just liveness: the deny rules, the members-only boundary,
  the public surface, HSTS, forced SSL, internal TLS, IPv6 and DNS (A and AAAA) for both public
  names.
- **CI gates** that assert things review cannot: ADR numbering and registry, Flyway numbering,
  monitoring image pins, Grafana dashboards, Prometheus rules, log masking, the Keycloak issuer,
  Quadlet drift (which also checks the edge's trusted-address pins), the container runtime, the
  logging facade, PID-1 reaping, probes against the API allow-list, ansible-lint, shellcheck,
  actionlint, hadolint, gitleaks, SBOM coverage and the E2E device matrix.
- **A conformance suite** ([`check-conformance.py`](../../scripts/check-conformance.py)) that
  asserts invariants against a **running host** rather than against configuration — the recurring
  defect class here is a configuration that is correct on disk and not in force in the process.

## 10.4 The quality goal with no automated gate

**Auditability of the documentation itself.** Specs, ADRs, the README, the role matrix, the wiki and
the knowledge base are all required to move with the change — and nothing in CI can enforce the
vault half, because it is a separate repository. That is a known, accepted gap, mitigated only by
writing the rule into every place a contributor or an agent starts. §11 lists it as debt rather than
pretending it is covered.
