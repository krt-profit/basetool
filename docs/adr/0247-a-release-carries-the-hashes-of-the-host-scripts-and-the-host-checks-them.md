# ADR-0247 — A release carries the hashes of the host scripts, and the host checks them

- **Status:** Accepted
- **Date:** 2026-10-11
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-045 · REQ-OBS-014 · REQ-OPS-001 · REQ-OPS-007 · ADR-0188 · ADR-0213 · incident `50 Issues and Incidents/Remote host terminated the handshake because the edge started last` (knowledge base) · runbook `docs/deployment.md` → *Before a promotion: the host scripts*

## Context

The operational scripts (`deploy.sh`, `backup.sh`, `lib/*.sh`, `render-env-d.py`, the collectors) run
as root-owned files under `/opt/basetool/scripts` and change only through the Ansible role
(`--tags deploy,scripts`). That is deliberate (ADR-0188): the deploy account runs them and cannot
rewrite them, and a deployer that replaces itself mid-run is a self-update hazard. The config bundle
therefore carries no script.

On 2026-10-10 the v1.14.0 promotion failed on production. The release changed the edge unit, so the
restart window included the edge, and the host's `deploy.sh` still started the edge after `ingest`
and `frontend` — the order fixed in #2334 had reached the testing host through the role and never
production. The apps could not resolve their issuer through the stopped edge, the health gate rolled
back, the rollback failed too, and the site was down for about four minutes with the database already
migrated. The release runbook listed the host-script comparison as *optional*; it would have shown
`deploy.sh` and `backup.sh` as the only two of 14 files that differed.

Nothing on the host or in the release said that a script was stale: no metric, no alert, no log line,
and the comparison existed only as a command a human might run.

## Decision

We will make a stale host script **visible on every tick and a refusal where it matters**, and the
manual comparison **mandatory**.

- **The release carries a manifest.** `scripts/host-scripts.list` names the files the role installs
  under `/opt/basetool/scripts`. `release-images.yml` runs `scripts/host-scripts-manifest.sh generate`
  at build time and the config image ships the result as `/config/host-scripts.sha256` (`sha256sum`
  format). A repo self-test keeps the list equal to what the role installs.
- **The host compares on every tick that stages no bundle.** `deploy.sh` hashes the listed files in its own directory
  against the manifest of the **deployed** release, which it keeps next to the compose file, and
  writes `basetool_host_scripts_drift{file,kind}` (`differs` | `missing`; one series per drifting file,
  none when in sync) and `basetool_host_scripts_check_timestamp_seconds` to the textfile directory.
  It logs one `host scripts:` line per file. `HostScriptDrift` (warning, 1 h) and `HostScriptsCheckStale`
  (warning, 12 h or absent) read them.
- **The host refuses what its apply path cannot apply.** Before it changes anything, `deploy.sh`
  compares the **incoming** release's manifest too. When `deploy.sh`, `lib/common.sh`,
  `lib/container-runtime.sh` or `render-env-d.py` — the files that apply a release — differ, it exits
  with a `HOST SCRIPTS:` FATAL naming the files and the fix, stamps a failed deploy and records **no
  backoff and no state**. Other stale scripts only warn. `--force` and `BASETOOL_REQUIRE_CURRENT_SCRIPTS=false`
  bypass it.
- **The manual comparison is a mandatory pre-promotion step** in `docs/deployment.md`, with a
  byte-exact command, and a mismatch blocks the promotion until the role has been run from the
  release's tag.

### The chicken and the egg

A script cannot police its own staleness: the host that lacks this change has a `deploy.sh` that
ignores the manifest. Three consequences are accepted and written down:

1. **The first install comes through the role.** Until `--tags deploy,scripts` has run from a tag
   that contains this change, the only protection is the mandatory manual comparison and, once the
   release's alert rules arrive, `HostScriptsCheckStale` firing on the **absent** metric — which is
   exactly what a host without the check looks like.
2. **The guard cannot block its own fix.** The fix is installed by the role, never by `deploy.sh`;
   the refusal keeps no marker and no backoff, so the first tick after the role run applies the
   release. A guard that wrote a backoff, or that the role depended on, would deadlock the very run
   that repairs it. The break-glass is for a manifest that is wrong, not for the normal fix.
3. **A release ships its manifest before any host reads it.** An older `deploy.sh` applies the new
   bundle and ignores the extra file; the check starts at the first apply by a `deploy.sh` that has
   it. `deploy.sh --reapply` delivers the manifest of the deployed release to a host that missed it.

## Consequences

- **Easier:** a stale script is a log line and a series the tick after a release, an alert within an
  hour, and a refusal before the one case that costs an outage. The incident's class — the release is
  applied by older code than it was built with — cannot reach the stack silently.
- **Harder / costs accepted:** a release that changes an apply-path script now needs a role run
  **before** its tick applies it, or `--force`; the role must be run from the release's tag, not from
  `main`, or the hashes differ. Rolling back by deploying an older tag with newer apply-path scripts
  is refused the same way and needs `--force`. The testing host, which follows `main`, will warn
  whenever a release touched a script the role has not re-installed.
- **Not covered:** the units, drop-ins, logrotate and tmpfiles the role installs outside
  `/opt/basetool/scripts`; a follow-up can extend the list with their installed paths.
- **Costs:** about 14 small hashes per tick; one new file in the config bundle.

## Alternatives considered

- **Ship the scripts in the bundle and let the host install them** — rejected: it makes the
  unprivileged deploy account the writer of the root-owned scripts it runs, the self-update hazard
  ADR-0188 avoids, and a compromised bundle would become root code.
- **A committed manifest checked by a gate** — rejected: every script change in every parallel PR
  would conflict on one generated file; generating it from the commit at release time cannot drift.
- **A separate timer-driven checker** — rejected for the first cut: deploy.sh already runs every five
  minutes with the manifest in hand, and a new unit is one more thing that only the role can install.
- **Guard only on "the edge is in the restart window and `RT_STACK_SERVICES` differs"** — rejected:
  the release's order is not readable from a hash, and the real invariant is that the code that applies
  a release is the code it was built with, whatever it changed.
- **Refuse on any stale script** — rejected: a changed collector or restore drill must not hold up a
  release; it warns and alerts instead.
- **Rely on the runbook alone** — rejected: it was optional once and the incident followed; a
  mandatory step with no signal behind it fails the same way the next time it is skipped.
