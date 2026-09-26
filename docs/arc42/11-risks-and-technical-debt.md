# 11. Risks and technical debt

Each item says what it costs and what closing it involves. Nothing here is a vague "could be
cleaner" — an entry earns its place by naming a failure that can actually happen.

## 11.1 The cleanup rename — **closed 2026-09-22**

The weekly cleanup job was `iri-docker-cleanup` and called `docker` directly; ADR-0194 renamed and
fixed it (§7.4a). Because the alert rules ride the config bundle while the scripts come from the
Ansible role, the alert accepted both metric names and Alloy watched both log paths for the length
of the rename, with the stated removal condition *"once both Rocky hosts have run the role"*.

Both have, and the retired Docker host never will, so the second name and the second path were
removed on 2026-09-22 (OPS-SIMP-02). `container_cleanup_rename_test.yml` now locks the one name —
fresh is silent, stale and absent fire, and the retired name alone no longer satisfies the alert.
The role still stops and removes the old `iri-docker-cleanup` units wherever it finds them.

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
  **closes** when a window allows one and `ls /proc/pressure` answers. *(2026-09-25: production was
  rebooted at 15:56 UTC for kernel 6.12.0-211.58.1; whether `/proc/pressure` now answers was not
  recorded — re-check before closing this.)*
- **After a reboot, the four `iri-*` jobs fail once.** On the 2026-09-25 reboot, `iri-deploy`,
  `iri-backup`, `iri-restore-drill` and `iri-container-cleanup` all started at boot and failed
  within a second with "no lingering user could be found" — a failure shape §7.4b's boot-time wait
  does not cover. The stack is unaffected and the next regular tick succeeds, but the units sit
  `failed` until then. **Fixed in the repository by #2069** (2026-09-25): the timers no longer pull
  their service in, the four units are ordered after the service user's manager, and `rt_detect`
  waits for what a sandboxed job can actually see — §7.4b has both causes.
  **Closes** when the role's `--tags scripts` run has put it on production and a reboot, testing
  host first, leaves no `iri-*` unit `failed`
  ([`deployment.md` → host patching](../deployment.md#updating-the-operational-scripts-and-units)).
- **A Keycloak restart is a full-app restart.** `backend` `Requires=` keycloak and `frontend` /
  `ingest` require backend, so `systemctl --user restart keycloak.service` — by hand or by a
  provider-JAR delivery — takes the app down with it: about two minutes of maintenance page, measured
  on production 2026-09-25. Documented at every restart in the runbooks
  ([`deployment.md` → *Driving the stack*](../deployment.md#driving-the-stack)); whether those
  dependencies should stay `Requires=` is not decided. `systemctl restart` returns when keycloak is
  healthy, before its dependents are, and the provider-JAR step trusted exactly that: on 2026-09-25
  it logged success while frontend and ingest had no container. **Fixed in the repository** the same
  day — the step now waits for the whole stack (`REQ-OPS-007`); it reaches a host with the role's
  `--tags deploy,scripts` run. **The second outage is closed in the repository** (ADR-0213,
  2026-09-25): a release that moves the JAR used to take two full-app outages, the app apply's and
  the JAR's; the JAR now rides the release apply, which stops every re-defined unit once and starts
  the stack once, so it is one — and ingest and frontend are no longer restarted twice per release.
  It reaches a host with the role's `--tags deploy,scripts` run. **What remains, by decision:** a
  failed gate cannot prove whether the JAR or an app image broke it, and a bad JAR rolls back an app
  release that would have been healthy alone; the deploy log narrows what it can. The runtime-health
  restart (ADR-0083) had the same `Requires=` shape — a `restart` per unhealthy service, reported
  resolved while the dependents it had restarted were still stopped, and a second unhealthy
  dependent started twice. **Closed in the repository** the same day (ADR-0083 amended): the heal is
  the release apply's one stop and one ordered start, resolved only when every unit is up; it
  reaches a host with the same role run. **Still open, not decided:** keycloak, the databases and
  redis are outside the drift check, so an unhealthy keycloak is healed by nobody — and healing it
  automatically would be a full-app restart on one failed probe.
- **A configured Discord precheck can fail open with nobody noticing.** The account-existence
  precheck (REQ-SEC-022) is fail-open by design, and its only witness is a Keycloak `WARN`. On
  production the truststore `.env` named never existed and the warning repeated at every start for
  at least seven days before a rollout step found it (2026-09-25, fixed the same day). No alert reads
  that line — `KeycloakErrorRateHigh`, whose comment names this very path, counts `ERROR` lines, and
  this is one `WARN` per start. The runbook's verify step now reads it; an alert is not built.

## 11.7 Security hardening decided but not yet carried out

Neither item is required for the members-only posture to be correct — that is enforced in the
application and asserted by the probes — but each is a decided reduction of blast radius that has
not happened.

- ~~**The frontend is still a public OAuth2 client in production.**~~ — **closed 2026-09-25**: both
  rollout steps ran and the provisioner applied `--frontend-client confidential` at 16:15 UTC; the
  host's `realm-export.json` seed is still the public shape (an open host write). ADR-0001 decided to make it
  confidential (PKCE **and** a client secret), closing security-audit finding M-6. The code shipped
  on 2026-09-23 (REQ-SEC-069) and is inert until `KEYCLOAK_FRONTEND_CLIENT_SECRET` is set; the
  production switch is two owner steps with no login window —
  [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](../OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md). Until
  then the frontend client carries PKCE `S256` as the interim state. Closed when the provisioner
  reports `basetool-frontend` confidential in production.
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
  compose-internal frontend origin — ADR-0202 amendment 1). The provisioner was applied to
  **production on 2026-09-23**, so they are gone there; the **testing realm is not provisioned
  yet**, which is an owner-gated write (and, since 1.11.0's audience gate, a precondition for the
  testing backend to start).
- ~~**Redis still has one all-powerful user in production until the per-service rollout.**~~ —
  **closed 2026-09-25.** Backend, frontend and ingest shared `default` (`~* &* +@all`) and one
  password, so any one of them could read every session's OAuth2 tokens. REQ-SEC-068 / ADR-0207
  shipped the per-service users; the owner ran rollout steps 2–5 on production on 2026-09-25 and
  `REDIS_DEFAULT_USER` is `off` there
  ([`deployment.md` → *The Redis ACL*](../deployment.md#the-redis-acl)). Left behind: a release
  rollback to 1.10.0 or older now needs `default` switched back on first.
- ~~**One internal TLS key is every service's identity until the per-service rollout.**~~ —
  **closed on production 2026-09-25.** Backend, frontend, ingest and Keycloak served the same
  self-signed `keystore.p12`, which was also the anchor every client pinned — so the
  internet-facing ingest container held the backend's and Keycloak's key, and the relay and the
  frontend's backend client checked no hostname (ING-SEC-04). REQ-SEC-070 / ADR-0211 shipped the
  minter, the switches and the fallback mounts; the owner ran the four-step rollout
  ([`deployment.md` → *Internal TLS*](../deployment.md#internal-tls-per-service-certificates-from-a-private-ca)):
  steps 1–2 at ~15:40/~15:52 UTC, step 3 with v1.12.0 (deployed 17:38–17:44 UTC), step 4 at
  17:58–18:03 UTC. The units mount `/var/iri/secrets/tls/<service>.p12`,
  `INTERNAL_TLS_VERIFY_HOSTNAME=true`, and no anchor carries the old certificate. Left behind: a
  release rollback to 1.11.0 or older now needs step 4 undone first — above all the Discord
  precheck's truststore, or that guard fails open again without an outage to notice it by.

## 11.8 Smaller, known, and deliberately left

- **A fifth certificate directory** (`keycloak.<domain>`) is carried and served by nothing, left
  from before identity moved onto the app origin (ADR-0166). It is documented so the count does not
  read as a missing certificate; pruning it is a deliberate edge change, not a clean-up to do in
  passing.
- ~~**`versions.properties` is vestigial**~~ — **resolved 2026-09-23.** The entry said "nothing
  reads it", which was wrong: the three application Dockerfiles copied it, and the refreshVersions
  settings plugin — applied on every build — recreated it when missing, which is why deleting it
  was never a one-line change. It went together with that plugin's unconditional application
  (audit items BLD-PERF-04, DOC-20): refreshVersions now runs only under `-PrefreshVersions`, its
  recreated file is gitignored, and the configuration cache it was blocking is on in CI.
- **The Discord login leaves the app origin for one hop, and cannot be made not to.** ADR-0166 put
  Keycloak on the app origin, but Discord's authorize page stays on `discord.com`. A callback that
  comes back in another browser context — the Discord app, an installed iOS web app's outside view,
  a reopened history entry — finds no Keycloak cookies and ends in `cookie_not_found`. Accepted as
  a client-side condition; `REQ-SEC-071` only makes it recoverable (message + link back), and
  `KeycloakLoginErrorSpike` catches it if it ever becomes the norm rather than the exception.
- **Sessions are not carried across a host move** unless somebody chooses to copy the Redis data.
  Skipping it logs everyone out at the moment of the move — a user-visible choice rather than a
  technical one. The archived cutover runbook has the copy, including the `--numeric-owner` trap.
