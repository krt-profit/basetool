# 11. Risks and technical debt

Each item says what it costs and what closing it involves. Nothing here is a vague "could be
cleaner" — an entry earns its place by naming a failure that can actually happen.

## 11.1 `iri-docker-cleanup` does not work on the Podman runtime — **open, and it fires an alert**

`scripts/docker-cleanup.sh` calls `docker system df` and `docker volume prune` directly. It is the
**only** operational script that does not source `scripts/lib/container-runtime.sh`, the abstraction
that `deploy.sh`, `backup.sh` and `restore-drill.sh` all use.

Measured on the migration target, 2026-09-21: there is **no `docker` binary** (the Ansible role
installs `podman` and not `podman-docker`), the timer is nevertheless **`enabled`**, and no
`docker_cleanup` series exists in any textfile.

**Cost.** The weekly run fails at its first command, so nothing reclaims unused images, build cache
or anonymous volumes — on a single host with finite disk, that is a slow leak toward the condition
the script exists to prevent. Worse, the alert is
`(time() - basetool_docker_cleanup_last_success_timestamp) > 8d **or absent(...)**`, so it starts
firing an hour after the cutover and can never be satisfied. **An alert that is always firing is an
alert nobody reads**, and this one sits in the same group as the backup and restore-drill staleness
alerts.

**Closing it** means porting the script onto `lib/container-runtime.sh` (Podman's `system df` and
`image prune` differ from Docker's in output, not only in name), renaming the unit and the metric,
and updating the alert rule, the Ansible role and the dashboards together — a metric rename that
misses one of those is exactly the failure mode §8.9 warns about. The script's prose is also German,
which the English-only rule forbids; that is a good moment to fix it rather than a separate errand.

## 11.2 The frontend hand-mirrors the backend's DTOs

There is no shared module: the frontend declares its own records. A contract change has to be made
twice, and the second one can be forgotten.

**Mitigated, not solved.** `FrontendDtoContractTest` diffs the mirrors against `openapi.json`, and
`GeneratedDtoAgreementTest` compares them field by field against 411 models generated from the same
document. Nothing in `main` imports a generated type yet — replacing the mirrors is a separate epic,
and until it happens the duplication is real.

## 11.3 The knowledge base cannot be gated by this repository's CI

The vault is a separate git repository, so no build here can fail because a note was not updated. The
rule is written into `CLAUDE.md`, into every repository's `CLAUDE.md`, and into agent memory — which
is mitigation by repetition, not enforcement.

**Cost.** A vault that has drifted is worse than no vault, because each stale note still reads as
authoritative. The only real defence is the habit of correcting a note the moment it is caught
disagreeing with the code, and saying so in the note, dated.

## 11.4 Derived nullity annotations have no gate

ADR-0192 applied 1,063 nullity annotations derived mechanically from the code. Nothing checks that
they stay true as the code changes: a method that starts returning `null` under a `@NotNull` is a
compile-clean lie. SpotBugs catches some of it at call sites, which is how the ADR's own first
parameter rule was caught being wrong — but that is a backstop, not coverage.

The same shape applies to the accessor sweep (ADR-0192 Amendment 1): it is true on the day it runs,
and three files added after the first sweep had already put six accessors back before anyone
noticed.

## 11.5 One host, no failover

A single machine runs the applications, both databases, the session store, the edge and the whole
monitoring plane. There is no standby and no automatic failover.

**This is a deliberate trade, not an oversight** — it matches the one-maintainer constraint, and the
money and complexity go into *recoverability* instead: an off-site backup, a restore drill that
proves the snapshot, and a rebuildable host (ADR-0188). The residual risk is honest: a host loss is
a restore, and a restore is measured in hours, not seconds.

## 11.6 Post-cutover follow-ups that are not yet closed

- **The new host's first own backup.** Until it runs, the edge certificates, the ACME account and
  the redis ACL exist in exactly one place — that host's disk — because every snapshot in the
  repository was written by the old host, whose deployed `backup.sh` captures none of the three.
  The restore drill correctly reports those three as `0` until then.
- **Two prerequisites are verified after the restore, not before it** (`basetool-ca.crt`,
  `KC_METRICS_ENABLED`): their inputs arrive with the restore itself. A missing CA takes out all
  four application scrape targets loudly; a missing `KC_METRICS_ENABLED` is silent — Keycloak stays
  healthy and simply answers `404` on `/metrics`.

## 11.7 Smaller, known, and deliberately left

- **A fifth certificate directory** (`keycloak.<domain>`) is carried and served by nothing, left
  from before identity moved onto the app origin (ADR-0166). Pruning it during a migration window
  is riskier than carrying it; it is documented so the count does not read as a missing
  certificate.
- **`versions.properties` is vestigial** — zero entries, nothing reads it. It survives because
  deleting it has never been worth a commit, and it is recorded here because an earlier revision of
  `CLAUDE.md` pointed readers at it.
- **Sessions are not carried across a host move** unless somebody chooses to copy the Redis data.
  Skipping it is fine and needs no command — but it logs everyone out at the moment of cutover,
  which is a user-visible decision rather than a technical one.
