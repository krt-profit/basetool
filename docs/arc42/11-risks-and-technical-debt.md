# 11. Risks and technical debt

Each item says what it costs and what closing it involves. Nothing here is a vague "could be
cleaner" — an entry earns its place by naming a failure that can actually happen.

## 11.1 The cleanup rename is carried by two names for a while — **transitional, with a removal condition**

The weekly cleanup job was `iri-docker-cleanup` and called `docker` directly; on this runtime that
failed at its first command while the timer stayed enabled, and the alert fired on `absent()` with
no way to satisfy it. Fixed in ADR-0194 — §7.4a has what did and did not translate, including the
step that would have destroyed the edge's certificates if it had.

What remains is the *shape of the rename*, and it is debt with a deadline. **The alert rules ride
the config bundle; the scripts are installed by the Ansible role.** The two halves therefore reach a
host independently and in either order, so for the length of that window the alert accepts **both**
metric names and Alloy watches **both** log paths:

- `basetool_container_cleanup_last_success_timestamp` **or** `basetool_docker_cleanup_last_success_timestamp`
- `/hostlog/iri-container-cleanup.log` **and** `/hostlog/iri-docker-cleanup.log`

`container_cleanup_rename_test.yml` locks all four combinations, including the one that matters
most: old metric stale, new metric fresh, alert silent.

The Ansible role **retires** the old unit rather than leaving it beside the new one — it stops and
disables `iri-docker-cleanup.timer`, removes its units, its logrotate entry and the old script, and
deliberately keeps the old **log file** so Alloy's `{app="ops-cleanup"}` stream has no gap. That
had to be added: the role installs and has no general removal pass, so before it a renamed unit
simply gained a sibling, and the old broken timer would have gone on failing every Saturday next to
the new one that works.

**Remove both halves once every host has run the role.** A rule that accepts a name nothing writes
is how a rename quietly never finishes — and while it stands, a host that somehow kept writing only
the old name would look healthy forever, which is precisely the state the alert exists to report.

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

## 11.5a The external probes no longer leave the machine, and the IPv6 ones cannot run

A container on this host cannot reach the machine through its own public address — the container
networks live in the service user's network namespace and outbound leaves through pasta, which does
not fold such a connection back into the host. Measured 2026-09-22: its own public IPv4 on 22, 80
and 443 all refused, its own public IPv6 on 443 refused, `host-gateway` open on all three, and
Cloudflare DNS over IPv6 open — so egress works and it is specifically the turn-around that does
not. [ADR-0196](../adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)
records the decision that follows: every public name a container dials is aliased to `host-gateway`.

**What that costs the blackbox probes is worth stating precisely, because it is smaller than it
sounds and larger in one place.**

*Smaller:* a probe still traverses TLS, the certificate, haproxy, the PROXY header, the edge, vhost
routing and the application. What it no longer traverses is public DNS and internet routing — and it
never did. The Docker host's hairpin kept the packet on the machine too. What these probes proved
was *"the name has a record and the edge answers"*, and that is what they still prove.

*Larger, and **solved on 2026-09-22** — this paragraph said the IPv6 modules "cannot work here at
all", and that was true of the configuration, not of the platform.* `host-gateway` is IPv4-only
because podman implements it with a single `--map-guest-addr 169.254.1.2` on the rootless netns's
pasta, so an ip6-pinned probe had no address and failed with *"no suitable address found"* — while
the edge served IPv6 perfectly well, measured from the host itself at `302`/`302`/`401`.

The first of the two ways out below was measured on the testing host and taken:
`containers.conf` now sets `pasta_options` with **both** a v4 and a v6 `--map-guest-addr`, and the
alias drop-in points the public names at the v6 one for `blackbox-exporter`, the only container with
an IPv6 route. A real named HTTPS request over the mapped address answers **200**, byte-identical to
its IPv4 twin. [ADR-0196 Amendment 1](../adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)
has the measurement table.

Two corrections to what stood here, because both would mislead the next reader:

- **It does not make the IPv4 aliases unnecessary.** Podman *replaces* its own `--map-guest-addr`
  rather than appending to it, so the v4 address has to be restated in `pasta_options` and the
  aliases are what carry v4. Setting only the v6 mapping takes `host-gateway` from open to
  unreachable — measured.
- **The second way out was not needed.** *"Move the IPv6 assertion to a host-level probe"* stays
  recorded as the simpler fallback if a future podman drops `pasta_options` support for the rootless
  namespace; it is not the current design.

What remains true is the paragraph above it: the probe still does not leave the machine. It reaches
haproxy through a mapping instead of through public DNS and internet routing — which is exactly what
the IPv4 probe does, so the two families are now equal rather than one being blind.

## 11.5b The alias drop-ins were hand-written before the role could write them

The cutover placed `10-host-alias.conf` for five services by hand, because the Ansible variable that
generates them did not exist in the shape the host needed. The role now writes them from
`basetool_host_public_name_aliases`, **and removes them when that variable is empty** — so a play
run against an inventory that has not been updated takes the aliases away and stops the backend,
Grafana and every probe.

The inventory is gitignored, so this cannot be enforced by review of this repository. What guards it
instead is that the role **refuses to run** when it finds the old singular variable name, which an
un-migrated inventory still carries. That converts the silent breakage into a failed play with a
message naming the replacement. **Remove this entry once a full role run has been made against both
hosts and the drop-ins survived it.**

## 11.5c Podman decides which of the edge's addresses the client sees, and we only bound it

Under rootless Podman the PROXY header the edge receives comes from the edge's **own** address:
`rootlessport` dials the container's published port from inside its netns, so the peer nginx sees is
whichever of the container's addresses podman chose. **Which one is not ours to choose**, and it is
not stable — measured on the production host 2026-09-22, three recreations with nothing else
changed, the peer appeared on `net-proxy-frontend`, then `net-proxy-grafana`, then `net-proxy-api`.

The mitigation does not remove the choice, it **bounds** it: every network the edge joins is pinned
with `ip=`, so the candidate set is finite and fixed, and `EDGE_TRUSTED_PROXY` names all six of
those addresses. That keeps ADR-0187's rule intact — name the address, never a prefix — because six
named addresses are no more a range than one is.

**Why this is an entry here and not a closed item.** A future podman could present an address that
is not in the set — an IPv6 one, say, or a new interface — and the failure mode is silent: nginx
does not reject a `set_real_ip_from` that never matches, it drops the header and falls back to the
TCP peer. That is exactly what happened while only the ingress address was pinned: 2340 requests in
ten minutes logged from one bridge address, one rate-limit bucket for the whole internet, with a
valid configuration and a green build. It was not a recreate that caused it — the first deploy after
the cutover happened to produce a matching peer, so the next release would have done it unattended.

Three guards stand against it, and their division of labour is the point:

- `.github/scripts/check_edge_trust_pins.py` (CI) — the generator's pins, the emitted unit and the
  Ansible list must name the same set, **and every network the edge joins must carry a pin**. That
  last clause is what keeps the set finite: adding a network without one fails the build.
- `render-and-run.sh` (start) — each entry is validated separately, so a prefix appended to a
  working list is refused rather than accepted alongside it.
- `check-conformance.py`'s `client-address-visible` (running system) — reads what the edge actually
  logs. It is the only one of the three that can see a peer nobody predicted, and it is how this was
  found.

**Remove this entry** if podman ever gains a way to pin the forwarder's source address explicitly;
until then the risk is bounded, named and watched, which is the most this layer allows.

## 11.6 Post-cutover follow-ups that are not yet closed

- ~~**The new host's first own backup.**~~ **Closed 2026-09-22.** The edge certificates, the ACME
  account and the redis ACL were in exactly one place — that host's disk — because every snapshot in
  the repository had been written by the old host, whose deployed `backup.sh` captured none of the
  three. The new host's own backup now carries all three and the drill restores them: **all seven
  artifacts read `1`**.

  It took three attempts, and the two failures are the entry's real lesson. The first two backups
  skipped `keystore.p12` and `realm-export.json` — one `WARN` line each and a successful exit —
  because the restore gives those files ownership chosen for the *containers*, and `backup.sh` reads
  them through a helper that runs as the *service user*. And the drill then aborted before its
  verification step on a `podman cp -` that reports failure for a copy it completed, writing four
  artifacts as `0` that it had never got as far as testing. **A backup that exits 0 and a drill that
  scores an artifact are two different claims**, and only the second is worth anything.
- ~~**Two prerequisites are verified after the restore, not before it**~~ (`basetool-ca.crt`,
  `KC_METRICS_ENABLED`). **Both checked 2026-09-22** and both green: the CA is in place with a valid
  subject and expiry, and the `.env` carries `KC_METRICS_ENABLED=true`. Their inputs arrive with the
  restore itself, so the ordering stays as it is; the entry remains here because the asymmetry is
  the part to remember. A missing CA takes out all four application scrape targets loudly; a missing
  `KC_METRICS_ENABLED` is silent — Keycloak stays healthy and simply answers `404` on `/metrics`.
- **Container stdout does not reach Loki until the release that states the log driver lands.** Fixed
  in the repository — every `.container` now carries `LogDriver=journald` and the role gives the
  host a persistent journal — but the units arrive with the config bundle, so a host stays blind
  until it has taken that release. Both halves are needed and each is silent on its own; §7.3 has
  the measurement.

- **The three host-pressure alerts cannot fire until the host is rebooted.** Rocky compiles PSI
  in and switches it off (`CONFIG_PSI=y` with `CONFIG_PSI_DEFAULT_DISABLED=y`), so `/proc/pressure`
  does not exist without `psi=1` on the kernel command line — measured on the production host
  2026-09-22: no `/proc/pressure`, and node_exporter emitting **zero** `node_pressure_*` series.
  `HostMemoryPressureStalled`, `HostCpuPressure` and `HostIoPressure` are therefore dead, and five
  panels on dashboard 01-host read **No data**. The first of the three is the earliest saturation
  signal this deployment has, on a host whose *designed* failure mode is memory pressure. The role
  now sets the parameter and deliberately does **not** reboot; the entry closes when a window
  allows one and `ls /proc/pressure` answers.

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
