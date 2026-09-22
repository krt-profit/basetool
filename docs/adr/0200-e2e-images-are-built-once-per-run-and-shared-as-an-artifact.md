# ADR-0199 — The E2E images are built once per run and shared as an artifact

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc (improvement audit 2026-09, item CI-06)
- **Related:** [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) (no
  BuildKit layer cache) · [ADR-0169](0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md)
  (the E2E gate) · [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md)
  (`REQ-OPS-027`) · [`docs/e2e-test/README.md`](../e2e-test/README.md)

## Context

`e2e.yml` runs the destructive Playwright suite as a matrix of three browser engines × five device
classes — fifteen cells, one runner each (owner decision 2026-09-13). Every cell brought its own
stack up with `docker compose … up --build --wait`: the same backend and frontend images, from the
same commit, built fifteen times in parallel. Each build is a full multi-stage Gradle compile inside
Docker, and each is its own chance at the transient Maven Central failure that kills a cell before
a single test runs — every test class then reports `initializationError`, and the real cause sits in
`compose-up.log` inside the artifact.

Each cell also ran `playwrightInstall` for all three engines, and on CI with `--with-deps`, which
installs the OS libraries of all three through apt — for a cell that launches exactly one.

ADR-0137 already rejected one way of sharing build work: the GitHub Actions layer cache
(`cache-to: type=gha`), because its multi-gigabyte writes into the repository's shared 10 GB cache
store were evicted before the next run could read them.

## Decision

- A `build-stack` job builds the two images **once per run** (`docker build` with the Dockerfiles
  and context `docker-compose.build.yml` uses, tagged with the names compose resolves for
  `IRI_BASETOOL_VERSION=e2e-local`), saves them with `docker save | zstd` and uploads them as a
  **run-scoped artifact** kept for one day.
- The matrix job `needs: build-stack`, downloads and `docker load`s the archive, and runs the suite
  with `-Pe2e.prebuilt=true`. `E2eStackExtension` then boots the stack with `up --no-build` and,
  before that, refuses to start unless both images are in the local store — so a naming drift fails
  with a message instead of a pull of `:e2e-local` from GHCR.
- `build-stack` carries the matrix job's gate expression verbatim, so a run whose gate is false
  builds nothing (the gate/concurrency mirror of ADR-0169 is unaffected).
- `playwrightInstall` honours `-Pe2e.browser` and installs only that engine; the Playwright cache
  key includes the browser. Without the property (a local run) all three are installed, and without
  `-Pe2e.prebuilt` the extension builds the images itself, exactly as before.

## Consequences

- One image build per run instead of fifteen; one exposure to a Maven Central blip instead of
  fifteen. The matrix cells start later by the length of one build, and then skip theirs.
- The artifact is **not** the layer cache ADR-0137 rejected: it never enters the 10 GB cache store,
  is read only by the cells of the run that wrote it, and expires after a day.
- Three strings must agree on the image names — the workflow's `BACKEND_IMAGE`/`FRONTEND_IMAGE`,
  `docker-compose.build.yml`'s `image:` template and the extension's `IMAGE_TAG`.
  `E2ePrebuiltImageParityTest` (frontend unit tests) pins them together, and the extension's
  prebuilt check catches the rest at runtime.
- A matrix cell can no longer be re-run on its own after the one-day retention if the artifact is
  gone; re-run the whole workflow instead.

## Alternatives considered

- **Keep building per cell.** Rejected: fifteen identical builds and fifteen failure chances for
  nothing.
- **Push the images to GHCR from the build job.** Rejected: it would give an unlabelled, PR-controlled
  job `packages: write` and put unsigned `e2e-local` tags into the release registry next to the
  signed ones.
- **The GitHub Actions layer cache.** Rejected by ADR-0137 for reasons that still hold.
