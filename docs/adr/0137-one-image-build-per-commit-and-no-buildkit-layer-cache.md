# ADR-0137 — One image build per commit, and no BuildKit layer cache

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-021 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · REQ-OPS-002 (promotion gates) · REQ-OPS-015 (host-side signature verification) · ADR-0049 (config as a promotable OCI artifact) · ADR-0055 (keycloak-spi bundle)

## Context

`release-images.yml` took ~8 minutes for a `main` push and ~12:45 end-to-end for a release. Two
independent causes account for almost all of it, and both were measured on runs `32235454225`
(main, Keycloak bump) and `32227895058` (tag `v1.5.53`) rather than reasoned about.

### The build cache cost more than the build

The critical-path job is `Build (backend / linux/arm64)`. Its `Build & push per-arch image by
digest` step ran 6:32, and the BuildKit progress log attributes it as follows: the image finished
pushing to GHCR at `09:03:50`, and the step then spent **3:59 more** on `#42 exporting to GitHub
Actions Cache` (`sending cache export 229.8s done`), ending at `09:07:49`. The actual work —
`:backend:dependencies` 56 s, `:backend:build` 65 s, AppCDS + SBOM + push 21 s — was 2:22 of a 6:32
step. Cache export was 63 % of the critical path.

That would be a fair trade if the cache paid it back. It did not. Across the twelve build jobs of
those two runs, the Gradle dependency layer re-ran in **eleven**, and the one job that did hit the
cache exported in 4 s. The cost landed precisely where the benefit was absent:

|      run       |          job           | deps | build | cache export | CACHED steps |
|----------------|------------------------|-----:|------:|-------------:|-------------:|
| main (kc bump) | backend / linux/arm64  |   56 |    65 |      **246** |            0 |
| main (kc bump) | backend / linux/amd64  |   62 |    67 |      **148** |            0 |
| main (kc bump) | frontend / linux/amd64 |   69 |    54 |           87 |            0 |
| tag v1.5.53    | backend / linux/amd64  |   56 |    77 |           93 |           18 |
| tag v1.5.53    | ingest / linux/amd64   |    0 |     0 |            4 |           26 |

The cause is the repository-wide Actions-cache quota, not the cache keys. At the time of writing
`gh api repos/{owner}/{repo}/actions/cache/usage` reported **11.3 GB active against GitHub's 10 GB
per-repo hard limit**, of which the six `type=gha,mode=max` scopes of this workflow were **5.3 GB in
144 `buildkit-blob-*` entries (49 %)**; CodeQL held 2.6 GB, the `ci.yml` Gradle caches 1.8 GB and the
Trivy DB 0.95 GB. GitHub evicts LRU once over the limit, so every release evicted the blobs the next
release would have read — and starved the other workflows while doing it. Run `32235454225` imported
the cache manifest and reused **zero** layers: the manifest is small enough to survive, the blobs it
points at are not.

The steady state is structurally above the limit, so this does not converge. Nor is it fixed by
tighter scoping: the layer that *can* be cached is the Gradle dependency download (~55 s), because
`COPY <module>/src/` changes on every commit and invalidates the compile layer regardless. Even a
perfectly warm cache saves ~55 s and still costs ~50 s to export the changed layers.

### A release builds the same commit twice

`release-publish.yml` tags the release PR's merge commit, so `v1.5.53` sits on `c5c31fb21` — the same
commit `main` already carried. Both refs trigger this workflow, and the
`release-images-${github.sha}` concurrency group (added after the v1.5.36 GHCR contention incident)
serialises them deliberately. Serialised, they add up: the main run took 6:56, the tag run 11:40 of
which 5:53 was queueing behind the main run. The tag run's only distinct product is the semver tag
set. It rebuilt three multi-arch images, from the same source tree, to attach different strings to
them.

`promote.yml` has re-tagged an existing digest without rebuilding since ADR-0049 — the mechanism was
already in the repository, applied to `:stable` instead of to the release tags.

## Decision

**We will not cache image layers in this workflow, and we will build a commit once.**

`cache-from` / `cache-to` are removed from the `build` matrix. A build cache is semantically
transparent, so removing it cannot change the produced artifact — only make it fresher. The header
of `release-images.yml` carries the measurement and the instruction to check
`actions/cache/usage` against the 10 GB limit before any cache is reintroduced.

A new `plan` job decides build-vs-reuse before any build job starts. On the **push** of a version
tag it resolves `:sha-<short>` for the three app images and admits reuse only when every gate passes:
the tag has the expected `sha-<hex>` shape, all three images resolve in GHCR, each index carries both
`linux/amd64` and `linux/arm64` children, and each digest cosign-verifies against this workflow's
identity **pinned to `refs/heads/main`**. Anything else — a `workflow_dispatch`, a main push, a
missing tag, a one-armed index, a bad signature, a failure of the `plan` job itself — yields a full
build. `merge` then applies the semver tags to the digest `plan` verified, with the same
`imagetools create` call it uses for freshly built digests, and signs it.

Trivy moves out of `build` into its own `scan` matrix, running in parallel with `merge`.

## Consequences

**A release's images become strictly more coherent, not merely faster.** `:1.5.53` and
`:sha-c5c31fb` now point at one digest instead of at two independent builds of one source tree —
the thing `promote.yml` and `deploy.sh` have always assumed. The release tag's digest is the one
that was built, scanned and signed on `main`.

**Timing.** The critical-path build job drops from ~7:36 to ~3:30, a `main` run from ~8:08 to ~4:00.
A release, where the tag run re-tags instead of rebuilding, drops from ~12:45 to ~5:00. 5.3 GB of
Actions-cache quota returns to `ci.yml`, CodeQL and the Trivy DB, which were being evicted by this
workflow.

**The supply-chain seam is narrowed, not widened.** Reuse verifies against `refs/heads/main` only,
where `promote.yml` (REQ-OPS-002) and `deploy.sh` (REQ-OPS-015) accept
`(heads/main|tags/v.+)`. The digest is **re-signed** by the tag run even though the gate already
proved it signed, so "every digest a release tag points at was signed by the run that applied the
tag" holds independently of the gates — and keeps holding if a future edit loosens one.

**Trivy's failure can no longer cost a release.** `exit-code: 0` made *findings* advisory, but a hard
failure of the scan action or of the SARIF upload failed the whole `build` job, which skipped `merge`
for every module: no tags, no signature. In its own job it fails red without touching the images.

**Costs we accept.** Dependencies are fetched from Maven Central on every build rather than
occasionally from cache — measured at 92 % of jobs already, so the added flake surface is marginal.
Six extra runner jobs per building run for the scan (free on a public repository). A reuse run
publishes no new SARIF: coverage is per **digest**, and that digest was scanned by the main run under
the same categories — a claim REQ-OPS-021 states explicitly so it is not mistaken for a gap.
`basetool-config` and `basetool-keycloak-spi` are still rebuilt on both runs; they are seconds-scale
`FROM scratch` images off the critical path, and leaving them out keeps the reuse logic confined to
the three app images.

**Follow-up.** If the Gradle dependency download (~55 s × 6 jobs) becomes worth attacking, the shape
that pays is a pre-warmed base image keyed on a hash of the Gradle files — rebuilt only when those
change, so it costs nothing per run — not a layer cache re-exported on every build.

## Alternatives considered

- **Move the cache to a registry backend (`type=registry` on GHCR).** No 10 GB cap, and the cache
  would genuinely start hitting. But the arithmetic lands in the same place: a perfect hit saves the
  ~55 s dependency layer and still exports the changed layers, for ~140 s against ~146 s with no
  cache at all. It buys ~6 s for six new GHCR packages to manage and a new failure mode in the
  critical path.
- **`cache-to: mode=min`.** Cheap to export, but it only carries the final image layers, so the
  multi-stage `build` stage — the only expensive thing — is never cached. Pays a little, gains
  nothing.
- **Pruning the Actions cache.** Buys one fast run. The steady state is 10.65 GB against a 10 GB
  limit; it refills and thrashes again.
- **Building the JAR on the runner and `COPY`ing it into the image.** Would let
  `gradle/actions/setup-gradle` handle dependency caching as `ci.yml` does, but it gives up the
  hermetic, self-contained image build and changes what the SLSA provenance describes — a large
  blast radius for a ~55 s layer.
- **Dropping the concurrency serialisation so the two release runs overlap.** Wall-clock would
  improve without touching the build, but this is exactly what v1.5.36 cost: three concurrent runs
  against the same GHCR packages produced `error writing layer blob: not_found` and a `403` on a
  `pull` scope, and the tag run died between tagging and signing — leaving `:1.5.36` present,
  unsigned and therefore unpullable by a fail-closed `deploy.sh`.
- **Skipping the tag run entirely and having the main run apply the semver tags.** The tag does not
  exist when the main run executes; metadata-action would have nothing to derive them from, and the
  release would depend on guessing the version from the branch name.
- **Reusing on `workflow_dispatch` too.** "Run workflow" is the documented manual kick for a release
  whose images are missing or suspect (see `release-publish.yml`); an operator reaching for it wants
  a rebuild, and a re-tag would silently deny them one.

