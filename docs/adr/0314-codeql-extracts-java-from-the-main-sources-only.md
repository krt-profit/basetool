# ADR-0314 — CodeQL extracts Java from the main sources only

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-044 ([`deployment-delivery.md`](../specs/deployment-delivery.md)) · ADR-0313 (CodeQL job layout) · ADR-0310 (runner budget)

## Context

The `Analyze (java-kotlin)` job ran `./gradlew compileJava compileTestJava --no-build-cache` so that
CodeQL could observe every compiler invocation. On 2026-10-23 that step took **6.9 of the job's 10.8
minutes**, making it the longest job of a pull request. `--no-build-cache` is required: a task that is
restored from the cache does not run `javac`, and CodeQL extracts nothing from it.

The test sources do not ship. A finding in them describes code that never runs in production.

## Decision

**The Java extraction compiles the main source sets only: `./gradlew compileJava --no-build-cache`.**
Test sources are no longer analysed. `build-mode` stays `manual`.

## Consequences

- The job no longer compiles the test source sets; its compile time is expected to drop by roughly
  their share, which the first runs on `main` will show.
- **Code scanning no longer covers test code.** A vulnerable pattern in a test — a hard-coded
  credential in a fixture, for example — is no longer reported by CodeQL; gitleaks still scans every
  commit for secrets (ADR-0315).
- Alerts that CodeQL had raised in test sources are no longer reported by new analyses.

## Alternatives considered

- **`build-mode: none` for Java.** No Gradle and no compilation at all, but GitHub documents the
  buildless Java analysis as somewhat less precise. Rejected by the owner on 2026-10-23.
- **Keep compiling the tests.** Rejected for the time it costs.
