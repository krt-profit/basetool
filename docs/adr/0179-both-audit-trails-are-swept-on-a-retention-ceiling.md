# ADR-0179 — Both audit trails are swept on a retention ceiling, which is not the same act as a purge

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc
- **Related:** spec `REQ-AUDIT-006` (new) · [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001` the
  append-only invariant and the denormalised handle snapshot, `REQ-AUDIT-003` the export,
  `REQ-AUDIT-004` the manual purge) · **amends**
  [ADR-0038](0038-admin-retention-purge-of-audit-logs.md) (which rejected an automatic sweep) ·
  [ADR-0037](0037-shared-multi-domain-activity-audit-log.md) (which accepted unbounded growth) ·
  [ADR-0178](0178-a-refused-registration-is-purged-on-a-retention-window.md) (the sibling retention
  window, decided in the same review) ·
  [`docs/privacy/processing-activities.md`](../privacy/processing-activities.md) (the Art. 30 record
  this number appears in)

## Context

[ADR-0038](0038-admin-retention-purge-of-audit-logs.md) asked how to bound the audit tables and
considered exactly this option. It rejected it, in these words: an automatic sweep "would silently
destroy audit history on a schedule (the opposite of an audit log's purpose) and demands a policy
decision (how long?) the owner did not want baked in."

That reasoning was sound for the question it answered, which was **table growth**. It is not the
question here.

A GDPR gap analysis of the tool asked a different one: for how long does this system hold personal
data about a named natural person, and on what basis. Both audit trails answered *indefinitely*, and
that answer is structural rather than incidental:

- Every row carries a **denormalised snapshot of the actor's handle**, and where the event had a
  target, of the target member's — on purpose. The user FK is `ON DELETE SET NULL`, so without the
  snapshot a deleted member's rows would record "an action by nobody, to nothing"
  (REQ-AUDIT-001).
- Therefore **deleting a member does not remove them from the trail.** REQ-DATA-008's deletion
  cascade reassigns or purges their data everywhere else; the audit rows keep the handle, by design,
  and that design is correct.
- The organisation is under **no retention obligation** for these rows. There is no statutory
  bookkeeping duty behind a warehouse rebooking or a mission check-in.

So "forever" was never a decision anybody took. It was the residue of there being nothing that
removed the rows: REQ-AUDIT-004's purge is an admin action, and **a purge nobody is required to run
is not a retention period.** ADR-0038 said as much without noticing the consequence — it called its
lever "opt-in and manual, not a policy."

The distinction this ADR turns on is that a **ceiling** and a **purge** are not the same act:

|                        |  Manual purge (REQ-AUDIT-004)   |     Retention ceiling (this ADR)     |
|------------------------|---------------------------------|--------------------------------------|
| Who decides the cutoff | An admin, per run, any date     | Configuration, one outer bound       |
| What it is for         | Compacting an oversized log now | Answering "how long do we keep this" |
| What ADR-0038 feared   | —                               | Silent destruction of recent history |

ADR-0038's fear is a fear of the *first* column applied automatically — a schedule that eats history
an admin would still want. A ceiling at two years does not compete with that; it only stops
"indefinitely" from being the default answer. Both can exist, and the manual purge keeps every
property ADR-0038 gave it.

## Decision

A scheduled sweep deletes rows older than a configured maximum age from **every** activity domain
and from the bank trail. Default **730 days**; `app.audit.retention.*`
(`enabled` / `max-age` / `interval`), disabled under the `test` profile.

**Two years is a judgement, and it is configuration because it is one.** No statute sets it. It is
chosen to outlast the organisation's own operating cycles, so an old dispute stays reconstructible,
and to stop there. ADR-0038 declined to bake in a policy decision; this does not bake one in either
— it ships a default and a property, and names the default as a judgement in the spec rather than
implying it was derived.

**The sweep calls the manual purge.** `AuditRetentionService` invokes
`AuditService#purgeBefore(domain, cutoff)` and `BankAuditService#purgeBefore(cutoff)` — it issues no
deletes of its own. Two consequences follow, and both are the reason for doing it this way:

1. The two paths **cannot diverge** in what they remove. There is one definition of "older than".
2. An automatic purge leaves the same `*_AUDIT_PURGED` marker (count + cutoff) a deliberate one
   does. ADR-0038's "you cannot silently erase history, only compact it and leave the receipt" holds
   for the sweep too — which is precisely the property its rejection of an automatic sweep assumed
   would be lost.

**A domain that holds nothing older than the cutoff is asked first and then skipped.** This guard is
load-bearing and is documented as such in the service, the spec and here, because it looks like an
optimisation and is not one. `purgeBefore` writes its marker **unconditionally** — right for an admin
who purged deliberately and found nothing, wrong for a daily job, which would otherwise mint ten
marker rows a day forever and grow the very table the sweep exists to bound. Asking first leaves the
manual purge's semantics completely untouched; changing `purgeBefore` to skip an empty purge would
have altered an admin-visible behaviour to serve a background job.

**Failure is isolated per domain.** `purgeBefore` opens its own transaction per call, and each call
is wrapped: a domain that deadlocks is logged and left for the next sweep while the others commit.
A sweep interrupted halfway keeps its committed work.

**The admin purge modal says the ceiling exists.** An admin who sees rows gone that they did not
purge must not have to guess why.

## Consequences

- ADR-0038's rejection of an automatic sweep is **superseded for the retention question and left
  standing for the growth question**. Its manual purge is unchanged in every respect — endpoints,
  gating, marker, warning, irreversibility.
- ADR-0037's "grows unbounded (no retention)" consequence, already amended by ADR-0038 into a manual
  lever, now has an actual bound.
- **The trail's usefulness after a member leaves is now time-limited.** The denormalised handle
  snapshot still outlives the account — for two years, not forever. An investigation of something
  older than the window is no longer possible from the trail, which is the cost of the decision and
  not a defect in it.
- REQ-AUDIT-001's append-only invariant is relaxed the same way ADR-0038 relaxed it, and no further:
  rows are still never *updated*, and the only delete path is still `purgeBefore` — now with two
  callers instead of one.
- **First run after deploy is destructive.** Enabling this on the existing deployment removes every
  audit row older than two years in one sweep. `IRI_AUDIT_RETENTION_ENABLED=false` before deploying
  is the lever if any of it must be kept, and REQ-AUDIT-003's export is the way to keep it.
- The sweep appears in `ScheduledJobStale` (REQ-OBS-008) as `audit_retention`, so a silently dead
  sweep — a retention promise that stopped being kept — alerts rather than passing unnoticed.
- The 730-day figure is now stated in **four** places that must agree: the property default, the
  spec, the privacy policy (`privacy.p_2_2`, all three bundles) and the Art. 30 record's retention
  table. Changing the window means changing all four.

## Alternatives considered

- **Leave it manual, as ADR-0038 decided.** Rejected: it answers a different question. A cleanup
  nobody is obliged to perform cannot be cited as a retention period, and the state this ADR found —
  rows from the tool's first day still naming people, some of whom no longer have accounts — is what
  "manual" produced in practice.
- **Anonymise the handle instead of deleting the row.** Rejected, and it was the closest call. It
  would keep the shape of the history (what happened, when, in which area) while removing the person
  — attractive for an audit trail. But the handle snapshot exists *because* the FK nulls out: a row
  whose actor is scrubbed is exactly the "action by nobody" REQ-AUDIT-001 added the snapshot to
  avoid, so the result is a row that satisfies neither purpose. It also needs a second mutation path
  on a table whose worth rests on being append-only, and the details payload of some events
  references the same person by id.
- **Per-domain windows.** Rejected as unjustified precision: no domain has a different basis for
  retention than any other, and nine knobs invite nine inconsistent answers to one question.
- **Partition-drop or external archival at the database layer.** Rejected for the same reason
  ADR-0038 rejected it — it moves a product decision into manual DB surgery, with no in-app trace —
  and it does not satisfy the requirement anyway: archiving personal data elsewhere is still
  retaining it.
- **A shorter window (e.g. 12 months).** Rejected by @greluc: the trail's value in reconstructing a
  dispute falls off sharply below the organisation's own operating cycle, and nothing about the data
  makes a shorter window necessary.
- **Changing `purgeBefore` to skip writing a marker when it deleted nothing.** Rejected: it changes
  an admin-facing behaviour (a deliberate purge that found nothing is a fact worth recording) in
  order to simplify a background job. The exists-check belongs to the caller that needs it.

