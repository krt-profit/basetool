# ADR-0171 — An ADR number is claimed against the base branch, and a gate says so

- **Status:** Proposed
- **Date:** 2026-09-13
- **Deciders:** @greluc (pending)
- **Related:** [`README.md`](README.md) > *Numbering* · the Flyway twin
  [`check-flyway-migrations.sh`](../../scripts/check-flyway-migrations.sh) and its
  [`flyway-migrations.yml`](../../.github/workflows/flyway-migrations.yml) gate ·
  [`repo-lint.yml`](../../.github/workflows/repo-lint.yml) (`adr-numbering`) ·
  renumbered by this decision:
  [ADR-0170](0170-self-enrolment-carries-the-sign-up-sheets-answers.md)

## Context

An ADR's number is its identity. Specs, Javadoc, CHANGELOG entries, other ADRs, the knowledge base
and the commit log all refer to a decision as `ADR-NNNN` and nothing else, so a number that names
two decisions makes every one of those references ambiguous — and renumbering later invalidates all
of them at once.

The number is nonetheless picked the way a branch can pick it: take the highest `NNNN` the working
copy can see and add one. That is a per-branch view of a repository-wide counter, so two branches
developed in parallel reach the same answer independently. Neither is wrong on its own; the
duplicate exists only in the union, which no branch's build ever evaluates.

It has now happened four times, and they differ only in how far they got:

| Number |                                                  What collided                                                  |                                                                           How it ended                                                                            |
|--------|-----------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0060` | the monitoring-stack ADR on `feat/monitoring-phase1b-tracing` (2026-07-02) against the write-DTO convention ADR | caught by hand before landing; the monitoring ADR shipped as **ADR-0072**. The stale branch still carries its `0060`, so a sweep across all refs still reports it |
| `0154` | the self-enrolment ADR (2026-09-02) against the forced-type-id ADR (2026-09-03)                                 | **not caught.** Both reached `main` in the same release commit (`e02b92e20`, v1.7.7, #1834) and `main` carried two ADR-0154s for eleven days                      |
| `0165` | the layout-model ADR, merged 2026-09-13 (#1871), against the phone-class ADR on the open #1870                  | standing at the time of writing; #1870 has to renumber before it can land                                                                                         |
| `0169` | the number this ADR's renumber first took, against an unpushed branch holding it                                | caught by the knowledge base before the push — see below                                                                                                          |

The `0154` case is the instructive one. Nothing in the repository failed, because nothing looks at
the ADR directory: not Gradle, not Checkstyle, not any CI job. Eleven days of references accumulated
against an ambiguous number, and by the time it was noticed the two ADRs had acquired very unequal
weight — the forced-type-id one is the subject of a follow-up decision chain that argues with it
**by number** eleven times in [ADR-0157](0157-a-dropped-session-value-is-repaired-on-the-request-that-found-it.md)
alone, plus five frontend source files, four spec passages, an alert-rule comment and eight notes in
the knowledge base. The self-enrolment one had five references here and three in `basetool-android`.

A fourth was caught while writing this ADR, and is the clearest demonstration of the mechanism.
The renumbered ADR was first given `0169`: `git for-each-ref` across every local and remote branch
showed the highest number anywhere was `0168`, so `0169` was free by every check this repository
can perform. It was not. A concurrent session held it on an unpushed local branch
(`fix/e2e-label-event-race`, the E2E concurrency-group ADR), invisible to the remote and to every
ref sweep at the moment the sweep ran. The only thing that knew was the knowledge base, whose
decision index lists numbers claimed by work in flight — and it is the reason this ADR is `0171`
and the self-enrolment one `0170` rather than `0169` and `0170`. Two things follow: a ref sweep is
a lower bound on what is claimed, never the answer; and the window is genuinely small — `main`
gained `0168` (#1875) between the start of this work and its commit.

The repository already solved the identical problem for Flyway. `scripts/check-flyway-migrations.sh`
checks for duplicate versions in the tree and for a new migration whose number the base branch
already holds, and `flyway-migrations.yml` runs it on every PR against a **freshly fetched** base
tip — deliberately not the PR event's `base.sha`, so a number that lands after the PR opens is
caught on the next run. ADRs have the same failure shape and none of the machinery.

One difference matters and decides the design. Flyway requires versions to be monotonic, so its
checker demands a new migration sort strictly after the base tip. ADRs have no such requirement, and
gaps are normal and transient: on 2026-09-13 `main` held `0163` and `0165` with `0164` sitting on an
open PR. A monotonic rule would have forced that PR to renumber an ADR colliding with nothing.

## Decision

**A number is claimed against the base branch, not against the local checkout, and CI asserts it.**

1. **`0154` stays with the forced-type-id ADR; the self-enrolment ADR becomes
   [ADR-0170](0170-self-enrolment-carries-the-sign-up-sheets-answers.md).** Both were merged, so
   "keep the merged one" does not decide it; the tiebreaker is inbound references, and it is not
   close (roughly 33 against 9, including a follow-up ADR that argues with ADR-0154 by number
   throughout). The moved ADR keeps its content, its date and its status, carries a dated note
   recording that it was published as ADR-0154 until 2026-09-13, and every inbound reference moves
   with it in the same change — including the `basetool-android` comments, which live in another
   repository and are listed as follow-up rather than silently left wrong.

2. **`0165` stays with the layout-model ADR**, which is merged. The phone-class ADR on #1870 is
   unmerged and renumbers to the next free number at push time. That change belongs to #1870 and is
   not made here.

3. **`scripts/check-adr-numbering.sh` gates it**, mirroring the Flyway script: no two files in
   `docs/adr/` share a four-digit prefix, and no ADR the branch adds *or renumbers into* takes a
   number a different file already holds on the base. The rename half matters here specifically:
   git reports a renumber as a rename rather than an addition, so the `--diff-filter=A` the Flyway
   checker uses (migrations are never renamed) would not look at it — and renumbering is exactly
   what fixing a collision does. It runs in `repo-lint.yml` as the `adr-numbering` job —
   on pull requests with the base tip fetched, and on push-to-`main` where only the duplicate check
   applies. `scripts/check-adr-numbering.test.sh` runs first, so the gate cannot pass vacuously.

4. **Gaps stay legal.** A number below the base tip that nothing holds is accepted, because an open
   PR legitimately holds one.

## Consequences

**A duplicate that has already landed now makes `main` red the same day.** That is the check `0154`
needed and did not have. It also means every open PR goes red while such a duplicate sits on `main`,
because a branch inherits it — deliberate, and pinned by a test so nobody mistakes it for a bug. The
way out is to rebase onto the fix, not to weaken the check.

**A colliding number is caught on the PR once the sibling lands.** The base-collision check fires as
soon as the other branch merges and this branch's CI re-runs, which is where `0165` will be caught.

**Two holes remain, and are stated rather than papered over.** Two PRs that each take a free number
and merge back to back *without a re-run in between* still produce a duplicate on `main`; the
duplicate check then reports it immediately, so the exposure is a day rather than eleven. And
nothing stops two *open* branches from picking the same free number in the first place. A branch
that has not been pushed is invisible to the gate and to any sweep over refs, which is precisely how
`0169` was claimed — so the habit stays load-bearing: **re-grep `origin/main` and the open PRs,
read the knowledge base's decision index for numbers held by work in flight, and claim the number at
push time.** The gate narrows the window; it does not remove the need to look.

**A renumbered ADR breaks any link held outside this repository.** A PR comment or an issue quoting
`docs/adr/0154-self-enrolment-...` now 404s. That cost is accepted once, here, and the gate exists
so it is not paid again; it is also the reason the tiebreaker is reference count rather than
chronology.

**Follow-up:** `basetool-android` carries three comments naming `backend ADR-0154` for the
self-enrolment decision (`MissionRepository.kt`, `MissionJoinRequestTest.kt`,
`MissionSeamDoubles.kt`). They are a separate repository and a separate PR.

## Alternatives considered

- **Leave both ADR-0154s and disambiguate in prose.** Rejected: the number is the identifier every
  reference uses, and "ADR-0154 (the session one)" is not something a grep, an index or a knowledge
  base link can act on. It also concedes that the sequence is advisory, which invites the next one.
- **Renumber the forced-type-id ADR instead.** Rejected on reference count: it would rewrite eleven
  in-prose references inside an accepted follow-up ADR whose whole argument is with ADR-0154 by
  number, plus the spec passages that quote it. Chronology favours it — the self-enrolment ADR is
  dated a day earlier — and it loses to the cost of the rewrite.
- **Leave a tombstone file at the old number.** Rejected: `0154-self-enrolment-...md` containing only
  a redirect re-creates the duplicate prefix the gate exists to forbid, and it would have to be
  special-cased in the very check being added.
- **Mirror the Flyway rule exactly (new number must exceed the base tip).** Rejected: ADRs are not
  applied in order and gaps are legitimate, so this would fail PRs that collide with nothing — #1870
  holding `0164` while `main` is at `0165` is the live example.
- **A committed registry file that branches append to.** Rejected: two branches appending to one file
  conflict on every parallel ADR, turning a rare silent collision into a routine merge conflict, and
  it still does not stop a branch from picking a number the registry has not yet seen.
- **Nothing; rely on review.** Rejected on the record: three collisions, one of which survived
  eleven days on `main` and was found by a reader, not a reviewer.
