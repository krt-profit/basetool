# ADR-0316 — The Trivy scan runs inside the image build job

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-021 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · amends [ADR-0137](0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md) · [ADR-0155](0155-vulnerability-findings-are-advisory-at-build-and-blocking-at-promotion.md) · ADR-0310 (runner budget)

## Context

ADR-0137 moved Trivy out of `build` into a `scan` matrix of its own, so that a hard failure of the
scan action or of the SARIF upload could not skip `merge` and leave a release without tags or a
signature. A building `main` push therefore runs six `Scan (<module> / <platform>)` jobs. On
2026-10-23 they ran **0.5 to 1.6 minutes** each, and every one held one of the organisation's 20
concurrent runners while pull requests queued for up to 19 minutes (ADR-0310).

Findings stay advisory at build time (`exit-code: 0`, ADR-0155); they block at promotion.

## Decision

**The scan is the last two steps of each `Build (<module> / <platform>)` job.** Trivy scans the digest
the job has just pushed (`steps.build.outputs.digest`) and uploads its SARIF under the unchanged
category `trivy-<module>-<platform>`. The build job gains `security-events: write`. The `scan` job is
removed.

## Consequences

- Six jobs fewer per building run; the digest no longer travels through an artifact to reach the
  scan.
- **A failure of the scan action or of the SARIF upload now fails its build cell, and a failed build
  cell stops `merge` for all three images** (REQ-OPS-021). The release or `main` push then publishes
  no tags and no signature at all until the run is re-run. This reverses ADR-0137's "Trivy's failure can no longer cost a release";
  the owner accepted it on 2026-10-23 and lifted the requirement that kept the scan in its own job.
- The build job, which pushes images, can now also upload code-scanning results.

## Alternatives considered

- **Keep the scan jobs.** Rejected for the runner cost above.
- **Scan inside the build job with `continue-on-error`.** `merge` would still run, but a failed scan
  would show only as a warning on a green job. The owner chose the failing variant on 2026-10-23.
