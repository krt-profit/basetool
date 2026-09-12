# ADR-0162 — The edge is native nginx, configured from git, with ACME in a separate container

- **Status:** Proposed
- **Date:** 2026-09-12
- **Deciders:** @greluc (pending)
- **Related:** [ADR-0072](0072-monitoring-stack-decoupled-from-the-app-deploy.md) ·
  [ADR-0112](0112-edge-per-ip-limit-keys-on-the-ipv6-64-prefix.md) ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) ·
  [ADR-0139](0139-shared-committed-tls-material-for-the-test-stack.md) ·
  specs `REQ-SEC-023`, `REQ-SEC-031`, `REQ-SEC-032`, `REQ-SEC-033`, `REQ-OBS-012`,
  `REQ-OPS-014` · [`API_VHOST_ROLLOUT_RUNBOOK.md`](../API_VHOST_ROLLOUT_RUNBOOK.md)

## Context

The edge is Nginx Proxy Manager: a Node admin UI and a SQLite database that generate nginx
configuration. Three things about that arrangement have become untenable, and none of them is a
matter of taste.

**The component is abandoned.** NPM 2.15.1 — the pinned, newest release — carries
CVE-2026-40519 (CVSS 7.7): an authenticated remote code execution **as root inside the container**,
in the certbot DNS-credentials path. The fix was merged four days after that release and has sat on
`develop` for over three months; there is no fixed image. The v3 branch is named `v3-abandoned`.
A second advisory, CVE-2026-50892, discloses the TLS private key through the certificate download.
Our posture survives both only because the admin UI is published on `127.0.0.1:10081` and no DNS
provider is configured — that is, the mitigation is *deployment shape*, not patch level, and it has
been that way for months rather than as an exception.

**Half of the security posture is not in git.** Force SSL, HSTS and Block-Common-Exploits are
per-host toggles in NPM's SQLite database on the production host. A UI misclick or a proxy-host
recreate undoes them with no diff and no review — and that has already happened: the move to
`profit-base.online` silently dropped all three on the Keycloak vhost. `REQ-OBS-012` asserts the
posture continuously precisely because it cannot be reviewed.

**The most security-critical configuration we own is deployed by copy-paste.** The API vhost is a
**deny-by-default allow-list** over 75 path families, 221 directive lines, and it reaches production
only when a human pastes the block out of a 2614-line runbook into a web form. The runbook says so
itself: *"there is no reconcile job and no drift alarm for it"*. On 2026-09-12 the live config and
the runbook block were compared directly for the first time — 221 lines against 221, and the sorted
multiset difference was empty in both directions. **The discipline has held so far.** That is not
an argument for keeping the arrangement; it is the last moment at which the two copies can be
collapsed into one without reconciling a divergence first.

An evaluation of Caddy was carried out with a sibling homelab that migrated NPM → Caddy on
2026-09-12 and answered from measurement. Their findings are recorded in the Consequences below;
their own recommendation was *not* to copy them for a publicly exposed edge with real user data.

## Decision

**The edge becomes native nginx, its entire configuration lives in this repository, and ACME moves
into a separate container.**

1. **Native nginx**, not Caddy and not Traefik. The decisive reason is that our configuration *is*
   nginx and carries nginx-specific semantics that were bought with incidents: the anchored-regex
   allow-list, `$krt_limit_key` keying an IPv6 client on its `/64` prefix (ADR-0112), rejections
   answering `429` rather than falling into the maintenance page's `error_page` intercept, and the
   `/actuator` deny answering **404** — which two independent probes assert. Translating those into
   another proxy's matcher language means re-deriving each one and proving it again; a family that
   becomes one character too broad is internet-reachable the moment it ships. Native nginx moves
   them **verbatim**.
2. **The configuration is the source of truth**, as real files under `docker/edge/`, validated by
   `nginx -t` in CI and applied by the existing deploy reconcile. The runbook stops being a thing to
   paste and becomes a thing that explains the file.
3. **ACME runs in its own container.** It holds the account key and the challenge credentials and
   writes certificates into a volume that nginx mounts **read-only**. The internet-facing process
   never holds an ACME credential and never executes a renewal hook — structurally removing the
   class that CVE-2026-40519 lives in.
4. **The edge gets no outbound network path.** With ACME separated, nginx needs no egress, so its
   proxy networks are marked `internal: true`. A compromised edge cannot fetch a second stage or
   call home.
5. **The container runs unprivileged.** `nginx` listens on 8080/8443 *inside* and the host publishes
   80 and 443 onto them, so not even `NET_BIND_SERVICE` is required: `cap_drop: [ALL]` with no
   `cap_add`, `user: 101:101`, `read_only: true` with tmpfs for the writable paths, and a real
   `HEALTHCHECK` so the deploy gate stops being blind to the one container whose failure takes
   everything down.

**NPM is not removed.** Its service definition, data and volumes stay in place and unreferenced, so
`docker compose up -d npm` restores the previous edge in seconds. It is removed in a later, separate
change, and deliberately later than feels necessary.

## Consequences

**Capabilities go from seven to zero.** NPM boots as root under s6-overlay and needs
`NET_BIND_SERVICE`, `CHOWN`, `SETUID`, `SETGID`, `FOWNER`, `DAC_OVERRIDE` and `KILL` — a set that is
empirically derived per image digest and has to be re-verified on every bump, `KILL` being the one
whose absence would not surface until a certificate renewal months later. None of that survives.

**`read_only: true` becomes possible.** NPM's boot rewrites `/etc/passwd`, `sed`-edits every `*.conf`
under `/data/nginx/` and pip-installs certbot plugins, so a read-only root filesystem makes the
container refuse to start. Native nginx needs `/var/cache/nginx`, `/var/run` and `/tmp` writable and
nothing else.

**The admin attack surface disappears entirely** — port 81, the Node application, the SQLite
database and `keys.json` (found world-readable at `0644` in the 2026-07-03 audit). Both NPM
advisories require an authenticated admin session; there is no longer a session to authenticate.

**An agent can change the edge.** Today a path opening is prepared in a PR and then applied by hand
in a form. After this, it is a diff, a CI gate and a deploy tick — the same mechanism that already
carries the Prometheus alert rules, which are a directory mount and reconcile on their own.

**What this costs.**

- **ACME becomes ours to operate.** NPM at least showed certificate expiry dates in its UI. A
  separate client with no alerting is worse than that, so renewal failure must be observable —
  the sibling homelab's honest admission was that they built the separation and *not* the alerting,
  and would only have noticed a failure when a browser complained. The ACME container therefore
  reports through the existing scheduled-task metric shape, and a stale certificate alerts.
- **The vhost skeletons are ours to write.** NPM generated them; roughly 300 lines of `server`
  blocks now live in the repository. That is a one-time cost and a permanent readability gain.
- **`internal: true` must be proven, not assumed.** Published-port ingress and Docker's embedded
  resolver both have to keep working on a network with no egress, and OCSP stapling — which wants
  outbound — is switched off rather than left to fail silently. Verified in the test stack before it
  ships.

**Why not Caddy.** Two reasons, both from the sibling homelab's measurements rather than from
documentation. Rate limiting is **not in Caddy**: `REQ-SEC-023` would depend on `mholt/caddy-ratelimit`
built in via `xcaddy`, making our most exposed component a custom build we own and update by hand —
which is precisely how NPM went stale there (image from 2026-06-03, container from 2026-08-10,
`:latest` without a pull). And Caddy's own release cadence has published nothing since 2026-06-03,
with an open Moderate advisory waiting on a 2.11.5 that does not exist. Swapping an unmaintained
edge for a slowly-maintained one buys less than it appears to.

Caddy would also have hit us immediately in a way nginx does not: its `reverse_proxy` negotiates
HTTP/2 with an HTTPS upstream via ALPN, and Go then sets `:authority` from the target URL rather
than the Host header, so an nginx upstream sees `SNI != :authority` and answers **421 Misdirected
Request**. All four of the sibling's Basetool hosts died on it instantly. Our edge proxies to
`backend` and `keycloak` over HTTPS — the identical shape. nginx sets `Host $host` and speaks
HTTP/1.1 upstream by default and is simply not exposed to it.

**Why not Traefik**, which was the sibling's own suggestion: it has rate limiting built in and the
best patch cadence of the three. It is the strongest alternative and the reason it loses is narrow —
it still requires re-expressing 221 verbatim nginx directives in middleware, which is the one risk
this decision exists to avoid. If the allow-list ever stops being the dominant consideration,
Traefik deserves re-evaluation on its merits.

**Rootless Podman is deliberately deferred** to a separate decision. It protects against a rarer
class (kernel/runtime escape) than the one being closed here (application RCE in an abandoned
component), and it would rewrite the whole delivery path: 77 `docker` invocations across 2232 lines
of operations scripting, 2678 lines of compose into Quadlet units, a test harness of ~118 assertions
that stubs `docker`, and the container-metrics plane — cAdvisor reads the Docker/containerd socket,
and six files under `monitoring/` key on its labels, including inhibit rules that join on `name`.
Its gating unknown is whether rootless port forwarding preserves the client source address for IPv4
**and** IPv6; if it does not, the per-IP limiter collapses into a single bucket, which is exactly the
2026-07-20 outage. That measurement is the precondition for even opening the question, and this
change makes it cheaper rather than more expensive: an edge already running as uid 101 with no
capabilities, a read-only filesystem and no egress is the container that gains least from rootless.

**If the migration is wrong, the way back is one command.** `docker compose up -d npm` with the
service definition and data untouched.
