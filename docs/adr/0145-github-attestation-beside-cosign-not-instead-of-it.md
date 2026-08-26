# ADR-0145 — A GitHub build-provenance attestation beside the cosign gate, not instead of it

> **Status:** Accepted · **Date:** 2026-08-26 · **Deciders:** @greluc
> **Related:** `REQ-OPS-023`, `REQ-OPS-024`, `REQ-OPS-015` (the host-side cosign gate),
> `REQ-OPS-021` (one build per commit), ADR-0075 (host-side signature verification)

## Context

Everything this repository publishes was already signed, and none of it was verifiable by a person
without prior knowledge.

The five OCI artifacts — `basetool-backend`, `-frontend`, `-ingest`, `-config` and `-keycloak-spi` —
carry a cosign keyless signature over the index digest, plus the SLSA provenance and SPDX SBOM that
buildx attaches as OCI attestations. `deploy.sh` verifies the signature on the host before it pulls
anything, fail-closed, against a pinned workflow identity (ADR-0075). That gate is sound and is not
in question here.

What it does not do is answer a question a **reader** has. Verifying an image by hand means knowing
that the identity to expect is `…/release-images.yml@refs/(heads/main|tags/v.+)`, that the issuer is
`token.actions.githubusercontent.com`, and that the host's cosign has to be a major at least as new
as the signer's. Somebody who does not already know all three cannot check anything — which is most
people who might want to, including a future maintainer reading this repository cold.

And the four SBOM files attached to every GitHub Release — the only artifacts a release hands out as
plain downloads, and the ones most likely to be forwarded on — had no provenance record of any kind.

## Decision

Every published artifact additionally carries an `actions/attest-build-provenance` attestation,
stored in this repository's GitHub attestation store: the three app images and the two bundles by
digest in `release-images.yml`, and the four SBOM assets in `release-publish.yml` before they are
uploaded.

`gh attestation verify <artifact> --repo krt-profit/basetool` is then the whole procedure.

Three things this deliberately is **not**:

- **Not a replacement for cosign.** The host gate stays cosign-only. `deploy.sh` runs as a user with
  no GitHub token and must not acquire one to deploy; a gate that needs an API token to a third
  party is a gate that fails when that party is unreachable. The attestation is evidence, and its
  absence never stops a deploy.
- **Not pushed to the registry.** `push-to-registry: true` would need `packages: write` on jobs that
  otherwise only read the registry, and the registry-side view is already covered by the OCI SLSA
  provenance buildx attaches.
- **Not skipped on a reuse run.** REQ-OPS-021 lets a release tag re-tag the digest main already
  built, and the cosign signature is applied again anyway to keep one invariant: every digest a
  release tag points at was signed by the run that applied the tag. The attestation keeps the same
  invariant for the same reason — it is idempotent, it costs seconds, and it means a release never
  rests on a record that some earlier run is merely believed to have made.

## Consequences

A member can check a downloaded artifact with one command and no briefing. An auditor can establish
that an image came from this repository without being told the identity regexp first. The SBOM
files stop being the one published artifact with nothing behind it.

The cost is a second provenance system to keep working: two records that could in principle
disagree, and one more action pinned in the release path. The disagreement case is not ambiguous —
cosign is the gate, so a conflict means the artifact does not deploy — and both records are minted
by the same job from the same digest, so a divergence would mean the job itself was tampered with,
which neither system claims to survive.

The alternative considered and rejected was documenting the cosign invocation in the README instead.
It is cheaper, and it is what the situation already amounted to: the identity **is** documented, in
`deploy.sh --help` and in REQ-OPS-015, and it still left verification as something you had to be
told how to do. A check nobody can run without instructions is not much of a check.
