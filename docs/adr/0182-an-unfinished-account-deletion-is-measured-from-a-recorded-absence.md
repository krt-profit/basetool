# ADR-0182 — An unfinished account deletion is measured from a recorded absence, not inferred from an existing timestamp

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc
- **Related:** spec `REQ-SEC-059` (new) ·
  [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-043` the roster sync's
  soft-delete rule) · [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`, the
  deletion cascade whose second half this watches) ·
  [`observability.md`](../specs/observability.md) (`REQ-OBS-011`, the business-gauge rules) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the other cached
  Keycloak fact on `app_user`, `enabled_in_keycloak`) ·
  [ADR-0181](0181-self-service-deletion-is-a-request-an-admin-executes.md) (the front door; this is
  the back-stop) · migration `V241`

## Context

Deleting a member is two acts, performed by a human, in order:

1. Remove the account in the Keycloak console.
2. Delete the local `app_user` row in Administration → Mitglieder.

The second is gated on the first having happened *and having been observed*: `members.html` renders
the delete action only for `!user.inKeycloak`, and `UserDeletionService` refuses an account the flag
still claims is present (REQ-DATA-008 verifies the precondition twice, against the flag and against
Keycloak itself). The flag is flipped by the nightly roster sync
(`UserRepository#markMissingUsers`).

So the second act is **not offered until the sync has run, and not performed unless somebody comes
back for it.** Nothing prompts them to. When nobody does, the row keeps — indefinitely — the e-mail
address, the display name, the Discord snowflake, the guild nickname and the free-text profile
description of a person who has already left.

Two things about that are worth stating precisely, because they decide how loudly this should be
handled:

- **It is not an access defect.** The Keycloak account is gone, so nobody can sign in as that
  member; nothing is exposed that was not exposed before.
- **It is a retention defect.** The data is being kept with no basis, and — unlike every other
  retention question in this codebase — with no window at all, because there was no mechanism, not
  because a window was chosen.

And nothing in the system said so. A half-deleted account is indistinguishable from one whose admin
simply is not finished yet, which is why this needed a *measurement* rather than a rule: the
distinguishing fact is **how long** it has been in that state.

That is the fact nothing recorded. Neither candidate timestamp carries it:

|    Column    |                                                                                                       Why not                                                                                                        |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `created_at` | When the **account** was created. For a member who joined in April and left in September it overstates the wait by five months.                                                                                      |
| `updated_at` | `@UpdateTimestamp`, and the flag is flipped by a **bulk JPQL update**, for which Hibernate does not run the entity lifecycle — so it is not even written by the flip. It also moves on every unrelated profile edit. |

## Decision

**`app_user.keycloak_absent_since` (V241), nullable, assigned explicitly.** `NULL` means "present as
far as we know", which is the state of every account in normal service.
`UserRepository#markMissingUsers` writes it in the same statement that flips the flag;
`UserReconciliationService#syncUser` clears it together with the flag when the account reappears.

Two gauges, sampled by `BusinessMetricsCollector` on its existing minute cadence:
`basetool_users_pending_deletion_count` and
`basetool_users_pending_deletion_oldest_age_seconds`. One alert, `UserDeletionUnfinished`, at seven
days.

**It is a first-observation stamp.** The update's pre-existing `in_keycloak = true` predicate already
restricted it to rows that actually flip — that predicate was added so the returned count would be
"newly disappeared" rather than a running total — and it now does a second job: a row already flagged
is never rewritten, so the instant stays the moment the absence was *first* noticed. Without that the
value would be refreshed on every nightly run and the age would report the sync's cadence, never
older than a day, which is the one value that could not possibly answer the question.

**The alert compares age, not count.** A count above zero held for seven days also describes a
stream of accounts each cleared within a day, because the count never reaches zero in between — an
alert that fires although no single account ever waited. Seven days, not the 48 hours the approval
queues use, because a wait *is* legitimate while an admin is mid-task and the roster sync is
nightly; and `severity: warning` rather than critical, because this is hygiene, not an incident.

**Rows already flagged when V241 shipped are backfilled with the migration's own timestamp.**
Nothing recorded when they disappeared — that is the defect being fixed — so there is no value to
recover. The deploy time is the only honest stand-in, it keeps the gauge meaningful, and it is
documented in the migration, in the column comment, in the requirement and in the alert's comment as
a **lower bound rather than a measurement**.

**Only the roster sync stamps it.** Three other call sites set `inKeycloak = false`
(`AccountConsolidationService`, `UserRegistrationService`, `RejectedRegistrationRetentionService`),
and all three do it solely to satisfy the delete precondition and then delete the row in the same
transaction. Stamping there would write a column nobody can ever read.

## Consequences

- A deletion somebody started and abandoned now surfaces within a week, on a dashboard panel and as
  an alert, instead of never.
- `app_user` gains one nullable column and one partial index (on the orphan set, which is empty or
  near it in normal service, rather than on the user base).
- `markMissingUsers` gains a parameter. Its two repository-level test verifications were updated;
  `UserReconciliationService#markMissingUsers(Collection)` keeps its signature, so the sync's own
  callers and their tests are untouched.
- **The instant is the application's, not the database's.** `Instant.now()` is passed in rather than
  using JPQL `CURRENT_TIMESTAMP`, matching how every other age gauge computes its age
  (`BusinessMetricsCollector#ageSeconds` also reads the app clock), so the two halves of the
  subtraction come from the same clock.
- For a short period after deploy, the alert reports a lower bound for historical rows. If any were
  already older than seven days, it fires 30 minutes after the first sample — which is the correct
  outcome, since those are exactly the accounts this exists to surface.
- This watches the **back** of the deletion lifecycle.
  [ADR-0181](0181-self-service-deletion-is-a-request-an-admin-executes.md) gives it a front door. A
  deletion request that an admin never executes shows up in *that* queue; a Keycloak removal nobody
  finished shows up here. They are different states and neither guard sees the other's.

## Alternatives considered

- **Count gauge only, duration from Prometheus' `for:` clause.** The cheap option — no migration, no
  column. Rejected by @greluc: `count > 0 for: 7d` asserts "the count has been non-zero for a week",
  which is not "an account has waited a week". A steady trickle of accounts each cleared within a
  day never lets the count reach zero and fires the alert with nothing actually waiting.
- **Infer the age from `updated_at`.** Rejected on the facts: the bulk update does not write it, and
  it moves on unrelated edits.
- **Infer the age from the audit trail** (find the row's last `USER_*` event). Rejected: the flag
  flip writes no audit event, the sync's summary event is per-run rather than per-user, and
  REQ-AUDIT-006 now bounds the trail at 24 months — an orphan older than that would lose its own
  origin story.
- **Make the deletion automatic** — let the sync delete the local row once Keycloak no longer has
  the account. Rejected, and it is the tempting one: it would remove the state rather than measure
  it. But REQ-DATA-008's deletion purges warehouse stock and a hangar and reassigns missions and
  refinery orders, and the precondition that guards it exists because a **stale flag from a swallowed
  sync error was once enough to hard-delete an active member**. Driving that off a nightly job would
  hand the most destructive operation in the system to the component whose failure mode is exactly a
  wrong answer about presence. The human click stays; only its absence becomes visible.
- **A notification to admins instead of a metric.** Rejected: the condition persists for days, and a
  notification models an event. It would either arrive once and be forgotten or repeat nightly. An
  alert with a `for:` clause is the right shape for a standing condition, and the dashboard panel
  answers "how many" at a glance.
