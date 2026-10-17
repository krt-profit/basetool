# 9. Architecture decisions

## 9.1 How decisions are recorded

Every architecturally significant decision is an **ADR** in [`docs/adr/`](../adr/README.md), written
before or with the change that implements it. There are **274** of them (ADR-0001 to ADR-0274,
as of 2026-10-17). They are numbered
sequentially, carry a status, and are amended in place with a dated `## Amendment N` section rather
than silently rewritten — a decision log that edits its own history teaches its readers
not to trust it.

Two rules make the collection usable rather than merely large:

- **A decision that is reversed is recorded as reversed**, with what was measured. Several ADRs here
  exist mainly to say *"we believed X, we measured, X was wrong in both directions"* — that is the
  most valuable kind of entry and the easiest to omit.
- **Claiming a number is done at push time**, against `origin/main` *and* open PRs. A number
  reserved only in prose does not exist as far as the numbering gate is concerned.

The ADRs are the *why*. The [`docs/specs/`](../specs/INDEX.md) registry is the *what must hold*.

## 9.2 The decisions that shape the whole system

Not a ranking of importance — a reading list. Someone who understands these can place almost any
other decision in context.

| ADR | Decision | What it determines |
| --- | --- | --- |
| **0049** | Host configuration is delivered as a promotable, signed OCI artifact | Configuration travels the same pull-only, digest-pinned, deliberately-promoted channel as the images. There is no "edit the config on the box". |
| **0055** | The Keycloak provider JAR is a *separate* promotable OCI artifact | Provider JARs are barred from the config bundle, so a provider change cannot ride in on a configuration promotion. |
| **0088** | Two-tier session idle timeout — and the redis ACL incident | Short idle for anonymous, 30 days after login. Its record also carries the `default`-user defect: an `aclfile` that omits `default` makes redis reset that user to `nopass`, and the compose comment that said otherwise was backwards. |
| **0139** | Shared, committed TLS material for the test stack | Why a deliberately worthless, published throwaway is an *application* of the no-real-credentials rule and not an exception to it. |
| **0159** | No anonymous or guest surface; every account is a member | The public surface is the landing page and the legal pages. This is the single decision most of the probe fleet exists to defend. |
| **0162** | The edge is native nginx, configured from git, with ACME in a separate container | Replaces the previous proxy whose configuration lived in a host database nobody could review. The edge is now diffable. |
| **0163** | The container runtime becomes rootless Podman on Rocky Linux 10, on a rebuilt host | The whole of §7. The platform choice was re-ruled twice on 2026-09-16 (Debian 13, then CentOS Stream 10, then Rocky); the file name keeps the first spelling. |
| **0166** | Identity moves onto the app origin, so the installed app can sign in | Keycloak is served under a path on the frontend host rather than its own vhost — which is why a fifth certificate directory exists that nothing serves. |
| **0187** | The edge learns the client address from a host-level PROXY-protocol front end | Why haproxy binds the public ports and the edge publishes on loopback only: a rootless port forwarder hides the client address, which the rate limiter and the admin allow-list depend on. |
| **0188** | The host bootstrap is an Ansible role; provisioning stays separate from delivery | A rebuilt host is reproducible, and the deploy path does not have to double as a provisioner. |
| **0189** | Stateful containers run as their own uid, not as root that steps down | Measured against the assumption: the capability set REQ-OPS-014 assumed was wrong in *both* directions, and reducing redis's capabilities would have **raised** its privilege. |
| **0190** | Every container runs on a read-only root filesystem | Also measured against the assumption that the JVM and the databases write all over the filesystem. They do not — Keycloak included, after a second pass corrected the first version's exception. |
| **0196** | A rootless host aliases its own public names to the container gateway | A container cannot reach the host through its public address, so the names it dials resolve to `host-gateway`; what that costs the external probes is §11.5a. |
| **0197** | Shipped dependencies pass a GPL-compatible licence gate and are listed on a public page | The build refuses a library it may not redistribute under GPL-3.0-only — AspectJ was excluded for exactly that — and the footer's „Open-Source-Lizenzen“ page is generated from the same reports. |
| **0192 / 0193** | Lombok and the JetBrains annotations everywhere; one logging facade, enforced | Style rules turned into compile errors and CI gates, so they stop depending on review attention. |
| **0231 / 0232 / 0233** | The backend becomes a modular monolith; modules interact through commands, observers and after-commit events; ArchUnit and Spring Modulith enforce the boundaries | The target every backend change now moves towards (§8.14, §11.9): a package per domain with a published API, audit always in the business transaction, and a frozen module baseline that may only shrink. Decided 2026-09-29; being built. |
| **0234** | The API is re-cut by hard cut, with a forced app update and a release-bound floor | No `/api/v2` beside `/api/v1`: contract tiers decide what may break, a declared-break ledger and the app's call list say what does, and the minimum app version rides the release. The per-domain cut stands; the machinery around it ends with ADR-0269, because the app it protected is discontinued. |
| **0246** | Pull requests merge through a merge queue, not the strict up-to-date policy | `main` plus the PR is built before the merge, one PR per group, with real CodeQL in the group but no SARIF upload, shims for DCO and gitleaks, and the standing condition that the repository stays public. |
| **0268** | The backend is internal-only; the ingest gateway is the single public API entry point | The system boundary (§3): with the Android app discontinued, the only API reachable from the internet is the exchange on the gateway, and the backend answers the frontend, the gateway and the Keycloak SPI alone. Six further decisions follow from it — ADR-0269 to ADR-0274 — and a probe asserts the absence, because a surface that was closed produces no signal of its own. |

## 9.3 Where a decision is *not* an ADR

- **Feature behaviour** is a spec (`REQ-<AREA>-NNN`), not an ADR. "Which role may clear a payout" is
  a requirement; "authorisation is centralised on `@PreAuthorize`" is a decision.
- **Operational values** — a host, a port, a retention window, a threshold — belong in a runbook or the
  configuration, where they can be corrected the day they change without rewriting a decision record.
- **Plan documents** (`docs/*_PLAN.md`, `docs/DESIGN_*.md`) carry a `Doc type:` header marking
  them *living spec* or *historical plan*. Once a plan ships — or a one-time runbook has been
  executed — it is frozen and moved to [`docs/archive/`](../archive/README.md), pointing at the
  living truth, rather than being left to read as current.
