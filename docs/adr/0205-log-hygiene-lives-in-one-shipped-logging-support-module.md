# ADR-0205 — Log hygiene lives in one shipped `logging-support` module

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** @greluc (improvement audit 2026-09, finding XMOD-SIMP-01, approved with the audit's
  open questions on 2026-09-22)
- **Related:** supersedes the "no shared code module" clause of
  [ADR-0002](0002-whole-number-amounts.md) for log hygiene ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-003`, `REQ-OBS-004`) ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-025`) ·
  [ADR-0047](0047-backend-package-acyclic-dependencies.md) (the backend package DAG) ·
  [ADR-0197](0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md)

## Context

ADR-0002 recorded, as an accepted cost, that the modules share no code: `@WholeNumber` exists
once per module "because there is no shared code module". The convention spread from there to
every cross-cutting helper, and by September 2026 the most security-relevant case was the log
hygiene every line of every application passes through:

- `LogSafe` (strips the line-breaking characters that let a caller forge a second log line,
  CWE-117), `PiiMasker` (JWTs, e-mail addresses, token keywords), `PiiMaskingPatternLayout` (the
  text sinks) and `PiiMaskingLogstashEncoder` (the prod JSON sink) each existed **three times** —
  backend, frontend, ingest, twelve files.
- The copies were identical code under different Javadoc. What held them together was a test, not
  a structure: `LogSafeTest` read the two *other* modules' sources off the filesystem and compared a
  marked region byte for byte, and each of the three build files declared those six files as test
  inputs (`logSafeMirrorSources`), because without that declaration Gradle reported the test
  UP-TO-DATE after an edit in another module and the parity assertion silently did not run — which
  had happened.
- The maskers had no parity guard at all. A masking fix applied to one module left the other two
  logging what it stripped, with nothing to say so.
- The backend's `LogSafe` additionally had to live in the `support` leaf, not next to the maskers,
  purely so the ADR-0047 cycle rule would allow `integration` and `service` classes to call it.

## Decision

We will keep the log hygiene in **one Gradle module, `logging-support`**, next to `test-support`,
and delete the copies.

1. `logging-support` is a **plain library JAR** — no Spring Boot plugin, no beans — holding
   exactly the four classes above in `de.greluc.krt.profit.basetool.logging`. It resolves logback
   and the Logstash encoder through the Spring Boot BOM and the version catalog, so it runs on the
   versions its consumers run.
2. **It ships.** `backend`, `frontend` and `ingest` depend on it as `implementation`; their
   `logback-spring.xml` name the shared layout and encoder, and their code imports the shared
   `LogSafe`. The JAR lands in each boot JAR's `BOOT-INF/lib`, and each image build copies its
   sources (`COPY logging-support/src/main/`).
3. **It publishes no SBOM of its own.** It is a component of the three applications' SBOMs, which
   are generated from their `runtimeClasspath`. `check_sbom_coverage.py` lists it in a new
   `SHIPPED_INSIDE` map with its three carriers and asserts that each still declares the
   dependency, so the exemption cannot outlive its reason.
4. **Its scope is closed to domain meaning.** Only framework-level log hygiene belongs here — no
   DTO, no validation rule, no business constant, no Spring component. The frontend keeps
   hand-mirroring the backend's DTOs (arc42 §11.2 and ADR-0161 own that question), and
   `@WholeNumber` stays per module; moving either would be its own decision.
5. **Each application pins its wiring with a test** (`ProdLogMaskingTest`): it loads the module's
   real `logback-spring.xml` with the `prod` profile into a private logback context (the
   `ProfiledLogbackConfig` helper in `test-support`), sends an event carrying a bearer token and an
   e-mail address, and asserts that both the JSON file and the text file contain the placeholders
   and not the values. The masker's behaviour is tested once, in the module.

## Consequences

- **Easier:** one copy to fix; the parity test, its `LOGSAFE-MIRROR` markers and the three
  `logSafeMirrorSources` declarations are gone; the maskers, which were never compared, can no
  longer drift at all. The backend's `LogSafe` no longer needs the `support` leaf for ADR-0047's
  sake, because it lives outside the backend's package graph altogether.
- **The masking is now proven end to end** in each application — the previous tests exercised the
  masker and the appender classes in isolation, and nothing showed that the prod configuration
  still named them.
- **Costs we accept:**
  - A sixth Gradle module, and one more `COPY` pair in each application Dockerfile; forgetting the
    source `COPY` fails the image build at compile time, not silently.
  - A change to the module rebuilds and re-tests all three applications. That is the point, but it
    makes a masking change slightly more expensive to iterate on.
  - `test-support` gains a `logback-classic` dependency for `ProfiledLogbackConfig`; it stays
    test-only.

## Alternatives considered

- **Keep the three copies and extend the parity test to the maskers.** Rejected: it turns a
  structural problem into more test machinery that reads other modules' sources and needs
  hand-declared Gradle inputs to run at all — the exact failure mode that produced a live false
  green once already.
- **Put the classes in `test-support`.** Rejected: that module is test-only by contract and is
  absent from every runtime classpath, image and SBOM; production code there would break the one
  guarantee its name makes.
- **A general `common` module.** Rejected: a module named for no purpose accumulates everything,
  and the frontend/backend DTO separation is deliberate. A narrowly named module states what may
  enter it.
