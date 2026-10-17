# ADR-0267 — A size ratchet caps Java files and methods at their current length

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-048) ·
  ADR-0266 · issue #2662

## Context

Issue #2662 asked to split the largest classes. ADR-0266 split two of them, but on `main` 27
production Java files are longer than 1,000 lines, the longest at 1,820, and seven methods are longer
than 150 lines, the longest at 186. Nothing stopped a file or a method from growing further, or a new
one from starting out that large. Splitting everything at once was not the aim; stopping the growth
was.

Two shapes were open: one global ceiling per check set to today's maximum, or a tighter global
ceiling with a per-file allow-list at each listed file's current size. A global ceiling of 1,820
lines per file would let every other file grow by 80 percent before the check speaks, so it does not
stop growth at all.

## Decision

1. **Checkstyle carries the ratchet in `config/checkstyle/google_checks.xml`**, so it runs in
   `checkstyleMain` of every module, fails with `maxWarnings = 0` and needs no build-script change.
   The test and e2e sources keep their own configuration and are not capped.
2. **A global ceiling of 1,000 lines per file and 150 lines per method** (`FileLength` with id
   `fileLength`, `MethodLength` with id `methodLength`; Checkstyle's own counting, blank lines and
   Javadoc included).
3. **Every file over a global ceiling is allow-listed at its current size.** An entry is a check
   instance with its own id (`fileLength<Class>`, `methodLength<Class>`) and that file's current
   maximum, plus two `SuppressionSingleFilter`s: one lifts the global check from that file, one
   confines the entry to it. The file is matched by its path from the `basetool` package root.
4. **The numbers only go down.** An entry is never raised. A change that shrinks a listed file or
   method lowers its entry to the new size, or removes the entry once the file is under the global
   ceiling. A new entry is not added.

## Consequences

- A file or method that is already too large can no longer grow, and no new one can pass the global
  ceilings.
- The allow-list is the visible size debt: 27 files and 7 methods today.
- Lowering an entry after a split is a manual step; nothing fails when an entry is looser than its
  file. A stale entry only allows regrowth up to the old size, never beyond it.
- A rename or a move of a listed file drops its entry's match, so the renamed file falls under the
  global ceiling and fails until the entry follows it.
