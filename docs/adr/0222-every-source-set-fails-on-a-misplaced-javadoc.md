# ADR-0222 — Every source set fails on a misplaced Javadoc

- **Status:** Accepted
- **Date:** 2026-09-26
- **Deciders:** @greluc (owner)
- **Related:** [ADR-0214](0214-code-carries-no-comments-besides-javadoc.md) (no comments besides
  Javadoc) · [ADR-0193](0193-one-logging-facade-enforced-with-keycloak-spi-excepted.md) (why the
  vendored Checkstyle file stays verbatim) · `CLAUDE.md` → *Documentation*

## Context

Five Javadoc blocks on `main` were orphaned: each sat directly above a second Javadoc block and
described a different member than the declaration below both. They appear when code is inserted
between a Javadoc and the member it documents: a new method goes in above an existing helper, or a
constant goes in between a method's Javadoc and the method. The javadoc tool ignores the first block, so
the member it was written for is left undocumented, and a reader takes the orphaned text for a
description of the declaration below. One of the five had also been reworded to fit the wrong
member.

All five were in `src/test` and `src/e2e`. Checkstyle's `InvalidJavadocPosition`, part of the
vendored `google_checks.xml`, reports exactly this, but only `checkstyleMain` ran:
`checkstyleTest` was disabled in the root build script and `checkstyleE2e` in the frontend's,
because the full Google rule set produces too much noise on test code.

## Decision

Every Checkstyle task other than `checkstyleMain` runs with
`config/checkstyle/javadoc_position.xml`, a local configuration holding only
`InvalidJavadocPosition` at severity `error`. This covers `checkstyleTest` in every module and
`:frontend:checkstyleE2e`, which are all part of `check`, and therefore part of the required
`Build, Test & Lint` CI job. `checkstyleMain` keeps the full `google_checks.xml`, which already
includes the rule.

## Consequences

- A Javadoc that is not directly above a type, member or package declaration fails the build in
  every source set, whatever caused it: an orphan left by an insertion, or a Javadoc inside a
  method body.
- `google_checks.xml` stays an unmodified copy of the release file, so a Checkstyle upgrade is still
  a plain re-copy. The second file uses only one long-standing check, so an upgrade does not need to
  touch it.
- The test and e2e Checkstyle tasks now parse those sources on every `check` (about a minute across
  all modules on a warm daemon).

## Alternatives considered

- **A `scripts/check-*.sh` regex step in `repo-lint.yml`** (the logging-facade pattern) — rejected.
  A regex over `*/` followed by `/**` misses a Javadoc misplaced in any other way. It also misreads
  a `*/` inside a string or text block. And it would run only in CI, not in the local `check`.
  Checkstyle parses the source and already carries the rule.
- **The full `google_checks.xml` on test and e2e sources** — rejected, for the same reason those
  tasks were disabled: naming and line-length rules that test code deliberately breaks would drown
  the one finding that matters.
- **Adding the rule to `google_checks.xml` with a suppression for everything else in tests** —
  rejected. It edits the vendored file, and the suppression list would grow with every rule a
  Checkstyle release adds.
