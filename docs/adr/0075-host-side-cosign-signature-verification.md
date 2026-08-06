# ADR-0075 — Host-side cosign signature verification before apply

- **Status:** Accepted
- **Date:** 2026-07-05
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-015 · REQ-OPS-001/-002/-007 · runbook `docs/deployment.md` → *Signature verification (cosign)* · ADR-0049 · ADR-0055

## Context

Production images and the config / keycloak-spi bundles are cosign keyless-signed by
`release-images.yml`, and `promote.yml` cosign-**verifies** a digest against that workflow's
identity before re-tagging it `:stable`. That CI-side verify was described throughout the runbook
and REQ-OPS-007 as the control that "makes the host's blind `:stable` pull safe" — and REQ-OPS-007's
acceptance and the runbook asserted the host *"resolves + cosign-trusts"* / *"cosign-verifies"* the
digest before staging it.

The host did **not**. `scripts/deploy.sh` resolved `:stable` to a digest with
`docker buildx imagetools inspect` and then pulled / `docker create` + `docker cp` / `up`'d it with
**no** signature check anywhere. The only `cosign verify` in the whole pipeline lived in
`promote.yml`, in CI.

Two facts make that a real gap, not a theoretical one:

1. **The host re-resolves `:stable` on every 5-minute tick**, independently of promotion. The
   artifact verified at promote time is not necessarily the artifact the host pulls later — a
   classic TOCTOU. `promote.yml`'s verify does not bind what `:stable` points at on the next tick.
2. **`:stable` can be moved out-of-band.** REQ-OPS-001 models a stolen host credential as read-only,
   but promotion necessarily wields a `packages:write` credential to re-tag `:stable`; a leaked or
   abused write token (or a registry-side tag manipulation) can point `:stable` at an arbitrary,
   attacker-built digest without ever running `promote.yml`. The next tick would pull and run it,
   and because the deploy user is in the `docker` group that is code execution as root-equivalent.

So the single control repeatedly credited with making the blind pull safe was absent exactly where
the pull happens, and the spec/runbook asserted a host-side control that did not exist.

## Decision

We will verify signatures **on the host**, before anything is applied. `deploy.sh` gains a
`verify_signature` / `verify_digest_or_die` pair that runs `cosign verify` against every resolved
`image@digest` — backend, frontend, ingest, and the config + keycloak-spi bundles when resolved —
immediately after the tick commits to applying (past the idempotence no-op, `--check-only`, and the
bad-digest backoff) and **before** the first `pull` / `docker create` / `docker cp` / `up`.

- **Same identity on both halves.** The trusted signer identity is
  `…/release-images.yml@refs/(heads/main|tags/v.+)`, and `promote.yml`'s regexp is tightened from the
  broad `@refs/.+` to the identical value, so a `workflow_dispatch` build off an arbitrary feature
  branch is not promotable **and** not host-trusted. The two halves share one identity by
  construction and cannot silently diverge.
- **Fail-closed.** A host with verification enabled but no `cosign` on `PATH` aborts the tick in
  pre-flight rather than falling back to trusting an unverified image. cosign is added to the manual
  host bootstrap (it rides the same manual channel as `deploy.sh` and the systemd units, which are
  deliberately not auto-delivered — the self-update hazard of REQ-OPS out-of-scope).
- **Break-glass.** `IRI_COSIGN_VERIFY=false` disables the gate for a tick, logged loudly on every
  skipped verification, for the sole purpose of riding out a Sigstore public-good outage.
- **Alarming.** A verification failure records a deploy-failure metric, so the existing
  `DeployFailed` alert fires — a moved `:stable` is a supply-chain incident, not a silent skip.

## Consequences

- The blind `:stable` pull is now genuinely safe: a moved-tag / stolen-write-token attack is
  rejected on the box before the image is pulled, extracted, or run. The spec (new REQ-OPS-015) and
  the runbook now describe a control that exists.
- The host takes on a new dependency (`cosign`) and a new outbound reach (Sigstore Fulcio/Rekor
  public-good roots, over the HTTPS the host already uses for GHCR). A Sigstore outage can block
  deploys — mitigated by the documented break-glass override.
- The host cosign is coupled to the CI's signing cosign by **major version**: cosign 2.x cannot
  verify 3.x keyless signatures, and the CI signs with 3.x (`cosign-installer@v4.1.2` → 3.0.6). The
  host must stay on cosign ≥ the CI's version (currently 3.1.3); the runbook pins this and documents
  the update path. A host stuck on 2.x would fail the fail-closed gate on every deploy.
- Steady state is unaffected: verification runs only on an actual apply, never on the ~every-tick
  idempotence no-op, so the timer's steady-state cost is unchanged.
- Keyless verify is used (no long-lived key to hold on the host), consistent with the signing side.

## Alternatives considered

- **Rely on `promote.yml`'s CI verify only (status quo).** Rejected: it does not bind what `:stable`
  points at when the host re-resolves it later, and it is bypassed entirely by an out-of-band tag
  move — the exact TOCTOU this ADR closes.
- **Pin the host to digests written by `promote.yml` instead of re-resolving `:stable`.** Rejected:
  it re-architects the pull-only, tag-polling delivery model (REQ-OPS-001/-002) and still needs a
  trust anchor for the digest it is handed; host-side cosign verify is the smaller, orthogonal fix.
- **Verify SLSA provenance / SBOM attestations too.** Deferred: the keyless *signature* identity is
  the load-bearing trust check; attestation verification is a possible later hardening on top of the
  same `cosign` dependency, not a blocker for closing this gap.
- **Fail-open with a warning when cosign is missing.** Rejected: a security gate that silently
  disables itself is not a gate. Fail-closed + a single explicit break-glass override is the correct
  posture; the host must be updated to get the new `deploy.sh` anyway, so installing cosign is part
  of that same manual step.

