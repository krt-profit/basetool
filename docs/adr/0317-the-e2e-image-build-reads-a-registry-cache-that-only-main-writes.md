# ADR-0317 — The E2E image build reads a registry layer cache that only `main` writes

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-027 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · amends [ADR-0200](0200-e2e-images-are-built-once-per-run-and-shared-as-an-artifact.md) and [ADR-0169](0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md) · [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) · ADR-0312 (runner budget)

## Context

`e2e.yml`'s `build-stack` job builds the backend, frontend, ingest and sandbox Keycloak images
without any layer cache; in the nightly run of 2026-10-23 it took 10.8 minutes before any matrix
cell could start. ADR-0137 rejected a BuildKit cache for the *release* images: the Actions cache was
over its 10 GB limit, and a registry cache was estimated to save only the ~55 s dependency layer. The
Actions cache still stands at 10.05 GB, so any cache here has to live elsewhere.

A probe on pull request #2925 (run `39945340325`) built the four images one after another on a
standard runner, with a GHCR registry cache (`type=registry`, `mode=max`):

| image | no cache | cold, export only | warm, same commit | warm, one source file changed per app module |
|---|---:|---:|---:|---:|
| backend | 274 s | 283 s | 15 s | 227 s |
| frontend | 197 s | 202 s | 10 s | 124 s |
| ingest | 172 s | 189 s | 7 s | 98 s |
| sandbox keycloak | 152 s | 166 s | 15 s | 13 s |

`build-stack` builds backend then frontend while ingest then Keycloak run beside them, so the
critical path is backend + frontend: about 471 s without a cache and about 351 s with a warm cache
after a source change — roughly two minutes per E2E run.

A run that writes the cache a later run reads can displace or poison it. The images only feed the
E2E suite, not a release, but a poisoned layer could still make the suite pass or fail for the
wrong reason. Job permissions are static, so "only `main` writes" cannot be a condition inside one
job.

## Decision

**The E2E images are built with `docker buildx` and a GHCR registry cache,
`ghcr.io/krt-profit/basetool-e2e-cache:<image>`. Every run reads it; only a run on `main` that is
not a pull request — the nightly schedule and a manual run on `main` — writes it.**

- Two job variants share one composite action, `.github/actions/build-e2e-images`:
  `build-stack` (`packages: read`, imports the cache) and `build-stack-main`
  (`Build E2E images (cache writer)`, `packages: write`, imports and exports with `mode=max`). Their
  conditions are the E2E gate `&&` not-writer and the gate `&&` writer, so exactly one of them runs
  when the gate admits the run, and a pull request never holds `packages: write`.
- The `e2e` matrix job needs both and runs when one of them succeeded:
  `!cancelled() && (<gate>) && (needs.build-stack.result == 'success' || needs.build-stack-main.result == 'success')`.
- `check_e2e_gate_mirror.py` now takes the gate from the `concurrency.group` selector and requires
  it verbatim as one `&&` conjunct of `e2e`, `build-stack` and `build-stack-main`; previously it
  required the whole `if` of `e2e` inside the group, which the status clause would break.
- The build images are named once, in the workflow's `env`.

## Consequences

- A pull request's `build-stack` shortens by about two minutes once `main` has written the cache; a
  cache miss costs nothing beyond the import attempt, and a cold export costs 5 to 17 s per image on
  `main`.
- **A pull request reads a cache up to a day old.** The nightly run refreshes it; between nights a
  pull request reuses only the layers that still match its tree, which is what the "changed
  sources" column measures.
- The cache is a GHCR package of its own; it does not touch the Actions cache quota.
- `build-stack` now boots a `docker-container` BuildKit builder and loads each image into Docker
  with `--load`, both included in the measured times.
- A fork pull request may not be able to read the cache, if the package is not public; the build
  then runs uncached.

## Alternatives considered

- **One shared tag that every run reads and writes**, as in the probe. Rejected by the owner on
  2026-10-23: a pull request could displace or poison the cache the nightly run reads.
- **A tag per branch with `main` as fallback.** Many tags to clean up.
- **A separate cache-writer job beside one reading `build-stack`.** Builds the images twice every
  night. Rejected by the owner on 2026-10-23 in favour of the two variants.
- **No cache.** The measured two minutes per run were judged worth it.
- **The Actions cache (`type=gha`).** At its quota; ADR-0137 records what that does.
