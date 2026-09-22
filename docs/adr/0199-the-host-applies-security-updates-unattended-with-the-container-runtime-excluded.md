# ADR-0199 — The host applies security updates unattended, with the container runtime excluded

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc (runtime exclusion decided in chat, 2026-09-22)
- **Related:** [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md) ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-032`) ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-011`)

## Context

The retired Ubuntu host patched itself: `unattended-upgrades` installed security fixes every night.
The Rocky Linux host that replaced it on 2026-09-22 patched nothing. The bootstrap role
(ADR-0188) installed no `dnf-automatic`, and the bootstrap document's own list of what was still
unwritten named it and was never followed up. So from the cutover on, the kernel, openssh, haproxy,
glibc and the host-native `node_exporter` and `alloy` received a fix only when somebody remembered
to run `dnf upgrade` — a regression nobody chose, found by the operations audit the same day.

Two things make the obvious fix — "install everything, every night" — wrong for this host:

- **The container runtime is not ordinary software here.** podman, crun, conmon, netavark,
  aardvark-dns, containers-common and passt decide how every container starts, and this stack's
  behaviour has been measured against them release by release: the pasta port forwarder, the rootless
  log-driver default, the Quadlet keys the generator relies on. An unattended runtime update is an
  untested platform change on the only production host.
- **A kernel fix does nothing until a reboot**, and a reboot of the one production host is an
  outage of everything on it.

## Decision

**`dnf-automatic` applies security advisories daily, unattended, with the container runtime
excluded, and never reboots — and the host reports every run and every pending reboot.**

1. `upgrade_type = security`, `apply_updates = yes`, `reboot = never`. The whole
   `/etc/dnf/automatic.conf` is written by the role, so no packaged default decides production
   behaviour behind a review.
2. **Excluded:** `podman`, `podman-*`, `crun`, `conmon`, `netavark`, `aardvark-dns`,
   `containers-common`, `containers-common-*`, `passt`, `passt-*` (the owner's decision). They move
   on a deliberate maintenance, testing host first.
3. The timer runs at **07:00 host-local plus up to 15 minutes**, clear of the backup, the restore
   drill, the weekly cleanup and the certificate collector.
4. `scripts/host-updates-metrics.sh` runs as the service's `ExecStopPost=` and records the run's time
   and outcome from systemd's `$SERVICE_RESULT`, plus `needs-restarting -r`; a boot unit re-reads
   the reboot flag. `HostSecurityUpdatesFailing`, `HostSecurityUpdatesStale` and
   `HostRebootRequired` read them, and `check-conformance.py`'s `security-updates-enabled` asserts
   the configuration on the running host.

## Consequences

- The host is patched again, on the day an advisory ships rather than when someone looks.
- **The runtime can fall behind.** A podman CVE is not fixed by this; it needs the maintenance the
  exclusion exists for. That is the trade the owner took: an untested runtime change on production is
  the larger risk on a single host with measured behaviour.
- **A reboot is still manual**, but no longer silent: `HostRebootRequired` warns after a day.
- A security update of `node_exporter` or `alloy` moves the host package away from the compose pin;
  `check-conformance.py --only host-exporter-versions` reports that, and the pin is moved in a
  reviewed commit.

**Rejected:** all updates unattended (feature updates and the runtime on an unattended schedule),
`reboot = when-needed` (an automatic outage of the only host, at a time nobody chose), pinning every
package in the role (blocks the security fixes this exists to apply), and leaving patching manual (the
status quo that nobody had decided).
