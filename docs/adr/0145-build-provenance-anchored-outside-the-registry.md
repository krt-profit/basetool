# ADR-0145 — Build provenance is anchored outside the registry, and release assets get provenance at all

- **Status:** Accepted
- **Date:** 2026-08-26
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [REQ-OPS-023](../specs/deployment-delivery.md) · REQ-OPS-015 (host-side signature verification) · REQ-OPS-001 (pull-only delivery) · REQ-OPS-021 (one build per commit) · [ADR-0075](0075-host-side-cosign-signature-verification.md) · [ADR-0049](0049-config-as-promotable-oci-artifact.md) · [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md) · runbook [`.github/SECURITY.md`](../../.github/SECURITY.md)

## Context

The published images were already well covered on paper. `release-images.yml` builds them from a
clean checkout, attaches a `mode=max` BuildKit SLSA provenance attestation and an SPDX SBOM, signs
the manifest digest with cosign keyless, and `deploy.sh` verifies that signature fail-closed against
a pinned workflow identity before the host applies anything (REQ-OPS-015, ADR-0075). Two independent
questions turned out to sit underneath that coverage.

### Everything the image can prove about itself is stored where the image is

The buildx provenance manifest and the cosign signature layer are not privileged objects. They are
ordinary manifests in the same GHCR repository as the image, discoverable as referrers of its
digest. Anything holding `packages: write` on the organisation can delete a package version and push
a replacement — and the provenance and the signature are deleted and replaced with it, because they
are siblings in the repository that credential controls.

REQ-OPS-015 is not the answer to this. It closes a genuinely different hole: `:stable` being
**moved** to point at some other digest, which the host catches because it re-resolves the tag and
re-verifies the digest on every tick. It does not close a package being **rewritten as a coherent
set** — new image, new provenance, new signature, all internally consistent, all produced by the
same credential. The verification would pass, because every artifact it consults came from the
attacker.

The scope of the credential matters here: `packages: write` is held by every workflow in the
repository that pushes an image, and by any PAT ever minted with that scope. It is not a
particularly exotic thing to obtain.

### The release assets had no provenance at all

`release-publish.yml` attaches four CycloneDX SBOMs to every GitHub Release. They carried no
signature and no attestation of any kind. A release asset is a bare file behind a URL: a consumer
downloading `backend-bom.json` to audit what the release ships cannot distinguish it from a file
uploaded by anyone who ever held `contents: write` on the repository.

An SBOM is a bad artifact to leave unattested, specifically. It is what a consumer reads *instead
of* unpacking the image — the whole point of shipping one is that people trust it rather than verify
the thing it describes. A doctored SBOM is therefore read, believed and acted on, which is more than
can be said for most tampered files.

### The host cannot be part of the fix

The obvious extension — have `deploy.sh` verify a GitHub attestation too — is unavailable, and
deliberately so. Verifying one means querying the GitHub attestations API, which means the
production host holds a GitHub credential. REQ-OPS-001 gives the host a **read-only GHCR pull
token** and nothing else, precisely so that a compromised host yields no ability to read or drive
the repository. Trading that away to strengthen a verification the host already performs with cosign
is a bad exchange.

## Decision

We will attest build provenance to **GitHub's attestation store**, via
`actions/attest-build-provenance`, for every artifact the project publishes: the three app images
(in `merge`, per module), the `basetool-config` and `basetool-keycloak-spi` bundles, and the four
SBOM release assets (in `release-publish.yml`, before they are uploaded).

Three properties make this worth doing rather than redundant:

1. **A different credential scope.** The store is written under `attestations: write` and read
   through `/repos/{owner}/{repo}/attestations`. `packages: write` does not reach it, so the
   registry-side rewrite above leaves it standing.
2. **`push-to-registry: false`, everywhere.** Pushing the attestation back to GHCR would put the
   independent copy in the one location whose mutability is the entire reason for creating it, and
   would add a referrer manifest to an index whose composition `plan` and `merge` reason about
   explicitly (REQ-OPS-021). Keeping it out of the registry is the feature, not a limitation.
3. **The subject is the digest.** As with the cosign signature, one attestation covers every tag
   pointing at that digest, including a later `:stable` promotion.

The attestation runs unconditionally, reuse runs included, on the same reasoning the cosign sign
step already documents: every digest a release tag points at should carry a record from the run that
applied the tag, not only one it inherited.

**The host gate is unchanged.** `deploy.sh` still verifies with cosign, fail-closed, and acquires no
GitHub credential. This decision addresses the **auditor half** of the supply-chain seam — anyone
verifying a published artifact after the fact, with `gh attestation verify` and nothing else.

## Consequences

**Easier.** Verifying a published artifact is now one command with no local trust configuration:
`gh attestation verify oci://ghcr.io/krt-profit/basetool-backend:1.6.3 --repo krt-profit/basetool`,
or the same against a downloaded SBOM file. Compare the cosign path, which requires the caller to
reproduce the `…/release-images.yml@refs/(heads/main|tags/v.+)` identity regexp — a string currently
duplicated across `deploy.sh`, `promote.yml` and this workflow, and which a third-party auditor has
to read the source to discover. A registry-side rewrite of a package now has a surviving witness,
and the release assets go from unverifiable to verifiable.

**Harder / accepted costs.** Provenance for an image now exists in two places that can disagree, and
a future reader has to know which one answers which question — REQ-OPS-023 and the workflow comments
carry that. One more third-party action in the release path, pinned by SHA and covered by
Dependabot's `github-actions` ecosystem. Roughly a few seconds per job. A GitHub attestation-API
outage now fails the release where previously it could not; that is the correct direction (fail
rather than publish unattested), but it is a new failure mode on the release path — the same trade
the cosign sign step already makes against Sigstore's availability, and unlike that step this one is
not wrapped in a retry, because the action does not expose one and a GitHub-API outage during a
GitHub-hosted run is not the kind of blip a local retry rides out.

**Not created by this decision.** No host change, no `deploy.sh` change, no new secret, and no change
to the artifacts themselves — the images and SBOMs are byte-identical, since the attestation lives
outside them.

**Follow-up left open.** Should the host ever need registry-independent verification, the answer is
not to give it a GitHub token but to have the release workflow `cosign attest` a second signed
statement, which the host can verify with the trust root it already uses. Not done now, because the
host gate is not the gap this ADR found.

## Alternatives considered

- **Do nothing; the buildx provenance is already SLSA.** It is, and it remains the primary record.
  The objection is not to its content but to its custody: it is stored, and can be replaced, by the
  very credential it is meant to constrain.
- **`push-to-registry: true`, so the attestation is verifiable offline.** Rejected: it re-creates
  the custody problem it was added to solve, and perturbs the index composition that REQ-OPS-021's
  reuse path inspects. Offline verification is a real want, but not worth buying by putting the
  independent copy back into the mutable store.
- **`cosign attest` a custom in-toto statement instead.** Same trust root and same storage location
  as the signature it would sit beside, so it inherits the exact weakness that motivated the change,
  while adding a bespoke predicate no standard tool verifies. Kept in reserve for the host-side
  follow-up above, where the shared trust root is precisely the point.
- **Sign the release assets with cosign `sign-blob`.** Solves the SBOM half only, and hands the
  verifier the identity-regexp problem again. It also drags in `verify-blob`, whose identity pinning
  carried GHSA-fx35-mq7g-6g98 in cosign ≤ 3.1.2 — a bypass this project's OCI-image gate was never
  exposed to, and there is no reason to walk into it now.
- **Teach `deploy.sh` to verify GitHub attestations.** Rejected on REQ-OPS-001: it puts a GitHub
  credential on the production host in order to improve a check the host already performs.

