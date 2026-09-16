# ADR-0055 — Deliver the Keycloak provider JAR as a separate promotable OCI artifact

- **Status:** Accepted
- **Date:** 2026-06-30
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-007 · REQ-OPS-002/004/005/006 · ADR-0049 · ADR-0030 · ADR-0051 · runbook `docs/deployment.md` → *Keycloak custom providers*

## Context

The Keycloak custom provider JAR (`keycloak-spi` — the Discord federation SPI plus the first-login
membership gate and the account-existence gate from ADR-0051) is loaded by Keycloak from
`/opt/keycloak/providers`, bind-mounted from the host. Until now it was the **only** production
artifact with no automated delivery: an operator built it locally and `scp`'d it onto the host. This
bit us — a host ran a months-old JAR after several app releases, so a shipped feature (the
account-existence gate) silently did nothing because the SPI half was stale.

The constraints:

- **The existing delivery model is pull-only, digest-pinned, deliberately-promoted OCI artifacts**
  (`deploy.sh` + `release-images.yml` + `promote.yml`, ADR-0049, REQ-OPS-001..004). Anything new
  should ride that channel, not invent a push path or a second credential.
- **REQ-OPS-005 bars `keycloak/providers/` JARs from the `basetool-config` bundle** — deliberately, to
  keep that bundle a lean, secret-free config tree. So the JAR cannot simply be added to it.
- **The JAR must match the app/Keycloak version it ships with** (the SPI talks to the backend's
  internal precheck endpoint and runs inside the Keycloak runtime). Its version must move with the rest.
- **The JAR is architecture-independent Java-21 bytecode** (`--release 21` so it loads under Keycloak's
  JDK), so it needs no per-architecture compilation.
- A provider-JAR swap requires a **Keycloak restart** (to re-run the provider build), but **not** a
  Keycloak image change — distinct from the stateful image-pin upgrade REQ-OPS-006 gates.

## Decision

We will deliver the provider JAR as its **own** signed `basetool-keycloak-spi` OCI artifact — a
`FROM scratch` image carrying only `keycloak-spi.jar` — built and signed by `release-images.yml`,
promoted to `:stable` **in lock-step** with the app images and the config bundle by `promote.yml`
(a 5th matrix entry), and staged by `deploy.sh` into `keycloak/providers/keycloak-spi.jar`. The JAR is
compiled once natively (no QEMU Gradle), copied to the build-context root, and packaged via a trivial
scratch Dockerfile.

On a moved `keycloak-spi` digest, `deploy.sh` — after the app stack is healthy — stages the JAR and
recreates **only** keycloak (`up -d --no-deps --force-recreate keycloak`, health-gated); on failure it
restores the previous JAR, brings keycloak back, and records the failure for backoff. A
provider-JAR-only change **auto-applies**; a combined Keycloak-image + provider-JAR change stays
operator-gated by the existing REQ-OPS-006 carve-out (the image change, carried in the config bundle's
compose, blocks the tick until `--force`). The idempotence marker gains a 5th positional field. This is
codified as REQ-OPS-007; REQ-OPS-005 is **unchanged** — the ban is on the config bundle, not on a
separate artifact.

## Consequences

- **Easier:** the SPI JAR can never again drift from the app version — it is promoted in lock-step,
  cryptographically verified before it touches the provider dir, and applied automatically; no manual
  `scp`. The same tooling/credential as the other artifacts; no new host dependency.
- **Harder / costs accepted:** one more artifact to build, sign and promote; `deploy.sh` (the
  safety-critical delivery script) grows a provider-JAR stage + a second, keycloak-only health-gated
  apply + a JAR rollback path. A provider-JAR change now restarts Keycloak unattended (a brief login
  blip), accepted as the cost of automation and bounded by the health gate. The health gate catches a
  JAR that fails to **load** (e.g. a bytecode-version mismatch) but not a JAR that loads yet is
  logically wrong — that stays the job of tests + the manual login check.
- **Follow-up:** the manual-staging steps remain documented as a bootstrap/fallback path; the
  operator still configures the Discord OAuth app + realm mappers + the account-existence env vars.

## Alternatives considered

- **Add the JAR to the `basetool-config` bundle** — rejected: REQ-OPS-005 bars provider JARs from it
  by design (keeps the config bundle lean and secret-free). A separate artifact respects that.
- **Bake the JAR into a custom Keycloak image** (`FROM quay.io/keycloak/keycloak` + COPY + `kc.sh
  build`) — rejected: it replaces the digest-pinned upstream Keycloak image with a bespoke one, and
  every JAR change would become a Keycloak **image-pin** change — which REQ-OPS-006 (rightly)
  operator-gates as a stateful upgrade. That turns a routine provider bump into a choreographed,
  gated event.
- **Independent versioning / never `:stable`** for the JAR (force explicit digest pinning) — rejected:
  it breaks the lock-step guarantee (the JAR must match the app/Keycloak version) and would force a
  parallel pin-management path in `deploy.sh`. Lock-step `:stable` promotion is exactly the
  REQ-OPS-002 principle the config bundle already follows.
- **Operator-gate every JAR change** (no auto-restart of Keycloak) — rejected by the owner: the point
  is automation. A provider-JAR-only swap is not a stateful choreography; it auto-applies, health-gated.
  The combined image+JAR case is still gated.
- **Build the JAR multi-arch inside the Dockerfile** (Gradle per platform) — rejected: the JAR is
  architecture-independent bytecode, so per-arch compilation (emulated under QEMU for the non-native
  arch) is pure waste. Build once natively, package the identical layer.
