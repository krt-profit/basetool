# 11. Risks and technical debt

Each item says what it costs and what closing it involves. Nothing here is a vague "could be
cleaner" — an entry earns its place by naming a failure that can actually happen.

## 11.1 The cleanup rename is carried by two names for a while — **transitional, with a removal condition**

The weekly cleanup job was `iri-docker-cleanup` and called `docker` directly; on a Podman host that
fails at its first command while the timer stays enabled, and the alert fires on `absent()` with no
way to satisfy it. Fixed in ADR-0194 — §7.4a has what did and did not translate, including the
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

**Remove both halves once both Rocky hosts have run the role** (the retired Docker host never
will, and no longer reports). A rule that accepts a name nothing writes is how a rename quietly
never finishes — and while it stands, a host that somehow kept writing only the old name would look
healthy forever, which is precisely the state the alert exists to report.

## 11.2 The frontend hand-mirrors the backend's DTOs

There is no shared module: the frontend declares its own records. A contract change has to be made
twice, and the second one can be forgotten.

**Mitigated, not solved.** `FrontendDtoContractTest` diffs the mirrors against `openapi.json`, and
`GeneratedDtoAgreementTest` compares them field by field against the models generated from the same
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

## 11.5a The external probes do not leave the machine

A container on this host cannot reach the machine through its own public address: the container
networks live in the service user's network namespace and outbound leaves through pasta, which does
not fold such a connection back into the host (measured 2026-09-22 on 22, 80 and 443, both
families). [ADR-0196](../adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)
therefore aliases every public name a container dials to `host-gateway`, and its Amendment 1 does
the same for IPv6: `containers.conf` restates the v4 `--map-guest-addr` and adds a v6 one in
`pasta_options`, and the blackbox exporter — the only container with an IPv6 route — gets the v6
aliases, so both address families can be probed.

**What that costs is smaller than it sounds.** A probe still traverses TLS, the certificate,
haproxy, the PROXY header, the edge, vhost routing and the application. What it does not traverse is
public DNS and internet routing — and it never did: the Docker host's hairpin kept the packet on the
machine too. The probes prove *"the name has a record and the edge answers"*, nothing more.

Two things to know before changing it. Podman **replaces** its own `--map-guest-addr` rather than
appending, so dropping the v4 entry from `pasta_options` takes `host-gateway` from open to
unreachable — measured. And if a future podman drops `pasta_options` for the rootless namespace,
the fallback on record is to move the IPv6 assertion to a host-level probe.

## 11.5b The alias drop-ins depend on an inventory this repository cannot see

The role writes the `10-host-alias.conf` drop-ins from `basetool_host_public_name_aliases` — **and
removes them when that variable is empty**, so a play run against an inventory that was not updated
takes the aliases away and stops the backend, Grafana and every probe. The inventory is gitignored,
so review of this repository cannot catch it. The guard is that the role **refuses to run** while it
finds the retired singular variable name, which an un-migrated inventory still carries
(`tasks/50-podman.yml`), turning a silent breakage into a failed play that names the replacement.
**Remove this entry once a full role run has been made against both Rocky hosts and the drop-ins
survived it.**

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

- `scripts/generate-quadlet.py` (CI, the `quadlet-drift` check) — the generator's pins, the emitted
  unit and the Ansible list must name the same set, **and every network the edge joins must carry a
  pin**. That last clause is what keeps the set finite: adding a network without one fails the
  build. `scripts/check-edge-nginx.sh` checks the list's shape end to end.
- `docker/edge/render-and-run.sh` (start) — each entry is validated separately, so a prefix appended
  to a working list is refused rather than accepted alongside it.
- `check-conformance.py`'s `client-address-visible` (running system) — reads what the edge actually
  logs. It is the only one of the three that can see a peer nobody predicted, and it is how this was
  found.

**Remove this entry** if podman ever gains a way to pin the forwarder's source address explicitly;
until then the risk is bounded, named and watched, which is the most this layer allows.

## 11.6 Open follow-ups from the cutover

- **The retired Docker host is shut down, not decommissioned.** It is kept only as the way back
  (§7.7). While it exists it holds a complete copy of production — both databases, `.env`, the
  keystore, the realm export, the certificates — and a power-on would start whatever timers it
  still has enabled. **Closes** when the rollback window is
  declared over and the machine is wiped and deleted at the provider; whether any secret it holds
  needs rotating is part of that decision.
- **Container stdout reaches Loki only once the log-driver release runs.** Every `.container` now
  carries `LogDriver=journald` and the role gives the host a flushed, persistent journal (§7.3) —
  both shipped in v1.9.2, the units with the config bundle and the journal with the role. **Closes**
  when production runs a bundle from v1.9.2 or later, the role has been re-run, and Loki shows the
  `<svc>-stdout` streams.
- **The three host-pressure alerts cannot fire until the host is rebooted.** Rocky compiles PSI in
  and switches it off (`CONFIG_PSI_DEFAULT_DISABLED=y`), so `/proc/pressure` does not exist without
  `psi=1` on the kernel command line — measured on the production host 2026-09-22: zero
  `node_pressure_*` series. `HostMemoryPressureStalled`, `HostCpuPressure` and `HostIoPressure` are
  therefore dead, and five panels on dashboard 01-host read **No data** — on a host whose *designed*
  failure mode is memory pressure. The role sets the parameter and deliberately does **not** reboot;
  **closes** when a window allows one and `ls /proc/pressure` answers.

## 11.7 Security hardening decided but not yet carried out

Neither item is required for the members-only posture to be correct — that is enforced in the
application and asserted by the probes — but each is a decided reduction of blast radius that has
not happened.

- **The frontend is still a public OAuth2 client.** ADR-0001 (*Accepted — implementation pending*,
  2026-05-20) decided to make it confidential (PKCE **and** a client secret), closing security-audit
  finding M-6. The code still says `client-authentication-method: none`; the frontend client carries
  PKCE `S256` as the interim state. Procedure:
  [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md).
- **Three of the twelve Keycloak hardening steps are open** —
  [`KEYCLOAK_HARDENING_RUNBOOK.md`](../KEYCLOAK_HARDENING_RUNBOOK.md): step 2 (decide *Forgot
  password* on Keycloak's own SMTP; `resetPasswordAllowed` was still on at the last recorded
  export), step 11 (OTP for `Admin`, in the browser flow **and** the Discord post-login flow) and
  step 12 (the session windows). Every step is an owner-only write against the production realm;
  the runbook's status table is the record.
- **Two realms agree only while someone runs the provisioner.** `scripts/provision-keycloak-realm.py`
  (ADR-0202) encodes production's realm shape, but nothing runs it on a schedule and nothing compares
  the realms automatically: a hand edit on either side drifts until the next
  `keycloak-config-snapshot.sql` diff. It also encodes production **as it is**, so what it marks
  `PROD-AS-IS` travels into every realm it shapes. Three such entries were decided on 2026-09-22 and
  now converge away (the extractor's unused code flow, the ingest scopes on the app, the
  compose-internal frontend origin — ADR-0202 amendment 1), but **production keeps them until the
  provisioner is applied there**, which is an owner-gated write.

## 11.8 Smaller, known, and deliberately left

- **A fifth certificate directory** (`keycloak.<domain>`) is carried and served by nothing, left
  from before identity moved onto the app origin (ADR-0166). It is documented so the count does not
  read as a missing certificate; pruning it is a deliberate edge change, not a clean-up to do in
  passing.
- **`versions.properties` is vestigial** — zero entries, nothing reads it. It survives because
  deleting it has never been worth a commit, and it is recorded here because an earlier revision of
  `CLAUDE.md` pointed readers at it.
- **Sessions are not carried across a host move** unless somebody chooses to copy the Redis data.
  Skipping it logs everyone out at the moment of the move — a user-visible choice rather than a
  technical one. The archived cutover runbook has the copy, including the `--numeric-owner` trap.
