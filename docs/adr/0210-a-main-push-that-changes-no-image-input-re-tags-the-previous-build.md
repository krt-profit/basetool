# ADR-0210 — A main push that changes no image input re-tags the previous build

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** @greluc (CI-07 approved with the improvement audit of 2026-09-22: "amends ADR-0137 —
  needs your approval", given)
- **Amends:** [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) — "one image
  build per commit" becomes "one image build per change of what goes into the image"
- **Related:** `REQ-OPS-021` ([`deployment-delivery.md`](../specs/deployment-delivery.md)) ·
  `.github/workflows/release-images.yml` (`plan`) · `.github/scripts/image_reuse_plan.py` ·
  [ADR-0209](0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md) (the
  one Dockerfile whose `COPY` lines define the inputs)

## Context

ADR-0137 built each commit once and let the release-tag run re-tag what the `main` run of the same
commit had produced. Every `main` push still built all three app images on six runners, whatever it
changed. Most of what lands on `main` changes no byte of an image — documentation, specs, ADRs,
tests, monitoring configuration, compose files, other workflows, the Ansible role — and each such
push paid the full pipeline anyway: six build jobs of three to four minutes, six Trivy scans, three
merges.

Replayed over the last 30 first-parent commits of `main` on 2026-09-23 (a code-heavy day), **7 of 30**
change no image input; over the last 100, **38**. The audit's own count (19 of 30, 2026-09-22) was
taken over a docs-heavier stretch. The replay is `image_reuse_plan.py --dry-run 30` (each commit against its first parent, the input set of `docker/app/Dockerfile` plus the three per-module Dockerfiles and `test-support/src/main` they copied until ADR-0209); the re-tags it found were documentation, the Keycloak provisioner (twice), a monitoring fix, the operational scripts, the E2E suite and an edge-proxy change. At 7 of 30 it saves 42 of 180 build jobs; at 38 of 100, 228 of 600.

The tag path already shows how to re-tag safely: resolve a candidate digest, prove it carries both
architectures and this workflow's main-branch signature, and hand exactly that digest to `merge`.

## Decision

1. **A `main` push re-tags the previous `main` build when no image input changed.** The `plan` job
   gains a second reuse path beside the tag path. It applies to a `push` event on `refs/heads/main`
   only; `workflow_dispatch` still always builds, and the tag path is unchanged.
2. **An image input is defined by the Dockerfile, not by a list.** `.github/scripts/image_reuse_plan.py`
   parses every build-context path a `COPY` in `docker/app/Dockerfile` reads (`${MODULE}` expanded to
   all three modules, `COPY --from` skipped) and adds what shapes the build without being copied: the
   Dockerfile itself (it pins both base-image digests), the root `.dockerignore`, `release-images.yml`
   (build arguments, labels, platforms), `.github/actions/setup-buildx/` (the BuildKit that runs the
   build) and `.github/scripts/app_version.py` (the version string the frontend bakes). A `COPY`
   added to the Dockerfile is an input without anyone remembering to say so. Today that is: the Gradle
   wrapper and `gradle/` (catalog, wrapper, `verification-metadata.xml`), the root build scripts and
   `gradle.properties`, all six module build scripts, the `src/main` of backend, frontend, ingest and
   logging-support, and `frontend/oss-bundled-components.json`.
3. **The comparison is between the previous `main` tip and this push** (`github.event.before`
   against `github.sha`), so a push of several commits is judged as a whole. A base that is missing,
   all-zero or not an ancestor (a force push) means "build".
4. **A release commit is always built.** The script recognises it by a new dated CHANGELOG section
   between base and head — the section `release-prepare.yml` writes — rather than by "is the tag
   there yet?", which would depend on how fast `release-publish.yml` runs. The release commit is
   the one whose image the tag run re-tags as the release, whose footer must name the release
   (`app_version.py`), and which deserves images built on its own day.
5. **The registry half repeats the tag path's gates** for `:sha-<short of the base>` of all three
   images: the tag resolves, the index carries `linux/amd64` and `linux/arm64`, and the digest
   cosign-verifies against this workflow's identity pinned to `refs/heads/main`, anchored like every
   copy `scripts/check-cosign-identity.py` guards. One more gate is new: **the candidate must have
   been built within the last 7 days** (its `org.opencontainers.image.created` label, falling back
   to the config's `created`). The runtime stage runs `apk upgrade`, so an image is only as patched
   as the day it was built, and a quiet week of documentation must not stretch that indefinitely;
   seven days matches the Dependabot cadence the owner chose for the base image (2026-09-03).
6. **All three images or none.** Reuse is decided for the three together, as on the tag path: the
   `build` matrix is either skipped or run whole, and `merge` re-tags or assembles.
7. `merge` applies this push's tags (`:edge`, `:sha-<short>`) to the verified digest and signs it,
   exactly as the tag path does; `scan` is skipped because that digest was scanned when it was built.

## Consequences

- **Documentation-only and test-only pushes publish in about a minute** instead of four, and free six
  runners each.
- **A re-tagged image says it was built from its source commit.** Its
  `org.opencontainers.image.revision` label, its buildx provenance and the frontend's version chip
  (`vX.Y.Z-n-g<sha>`) name the commit that built it, not the one whose `:sha-<short>` tag now points
  at it. The bytes are what that later commit would have built — that is the reuse condition — so
  the chip is accurate about the code, and the tag is accurate about the commit. `promote.yml`'s
  `sync-testing` ancestry check reads the revision label and stays correct: the building commit is
  an ancestor of the tagged one. Release images are never re-tags of an earlier commit (decision 4).
- **The re-signature and the GitHub attestation name this run**, as on the tag path: "every digest a
  tag points at was signed by the run that applied the tag" (ADR-0137) keeps holding.
- **Security-tab coverage stays per digest.** A re-tag uploads no SARIF; the digest's scan is the one
  from the run that built it, at most seven days old. `promote.yml` rescans before any promotion
  anyway (REQ-OPS-024).
- **The input definition can only err towards building.** A path wrongly counted as an input costs
  one unnecessary build; a path wrongly left out would ship a stale image, which is why the list is
  derived from the Dockerfile and why `image_reuse_plan.py --selftest` (in `repo-lint.yml`) fails
  when the parser stops seeing the real Dockerfile's `COPY` lines.

## Alternatives considered

- **Per-module reuse** (rebuild only the images whose own inputs changed). Measured on the same
  replay it would save 82 of 180 build jobs over the last 30 commits (backend 10, frontend 14, ingest 17 re-tags) and 312 of 600 over the last 100, against 42 and 228 for the all-or-nothing rule — but the `build` matrix would have to be computed from the plan,
  `merge` would mix reused and fresh digests, and the three images would stop being one build of one
  commit, which ADR-0180 and the release notes both assume. Left for the owner to decide separately.
- **Hash the build context instead of diffing paths** (e.g. a digest of every `COPY` source). Exact,
  but it needs the hash recorded somewhere the next run can read — a label on the image, i.e. one
  more thing to trust from the registry — for no difference in the answer.
- **Diff against the parent commit (`HEAD^1`) instead of the pushed range.** Wrong for a push of
  several commits: an input change in an intermediate commit that has no run of its own would be
  missed.
- **No age limit.** Simpler, and lets a documentation streak keep an image on last month's Alpine
  packages.
- **Reuse on `workflow_dispatch`.** Rejected for the same reason ADR-0137 gives: "Run workflow" is
  the manual rebuild.
