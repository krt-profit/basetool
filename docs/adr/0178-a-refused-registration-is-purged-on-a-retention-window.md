# ADR-0178 — A refused registration is purged on a retention window, not kept forever

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc (pending)
- **Related:** specs `REQ-SEC-057` (new) · [`security-and-access.md`](../specs/security-and-access.md)
  (`REQ-SEC-017` the approval lifecycle, `REQ-SEC-026` the link flow, `REQ-SEC-034` the reopen) ·
  [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`, the deletion cascade) ·
  [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`) ·
  [ADR-0111](0111-admin-mediated-discord-registration-linking.md) (the commit ordering this
  reuses) · [ADR-0038](0038-admin-retention-purge-of-audit-logs.md) (the manual purge this is
  deliberately *not* modelled on)

## Context

A registration refused by an admin becomes `ApprovalStatus.REJECTED` and stops there. `rejectUser`
stamps the status, the deciding admin and the time, writes a `user_approval_event`, and returns.
Nothing else in the system ever touches the row again.

What it leaves behind, for a person who never became a member:

|         Where         |                                         What                                         |
|-----------------------|--------------------------------------------------------------------------------------|
| `app_user`            | e-mail address, username / display name, `discord_user_id`, `discord_guild_nickname` |
| `user_approval_event` | `decision`, the deciding admin, and `reason` — a free-text `TEXT` column             |
| Keycloak              | the full user, including the federated Discord identity                              |

`user_approval_event.reason` is the sharpest of these. It is where an admin writes *why* an
application was refused: an assessment of a named natural person, in prose, retained indefinitely,
about someone who has no account to read it with.

**There was no path to remove any of it.** The member list's delete action renders only for
`!user.inKeycloak` (`members.html`), and `UserDeletionService` refuses an account the flag still
claims is present. A rejection deliberately leaves the Keycloak user in place — so a refused
registration satisfied neither condition and was unreachable by every deletion affordance the
application had. The only remedy was a manual deletion in the Keycloak admin console followed by a
second click in the member list that nothing prompts anyone to make.

REQ-SEC-034 added a rejected-registrations list and a reopen action, so the rows are at least
*visible*. Visibility is not a lifecycle.

## Decision

**A rejected registration is purged automatically once the rejection is older than a configured
window** (`app.registrations.rejected-retention.max-age`, default `P90D`), by a daily scheduled
sweep. The purge removes the `app_user` row, its `user_approval_event` rows and the Keycloak user.

Four things follow from the design, each a deliberate choice:

### The window is not zero

REQ-SEC-034 exists because approval is fallible: an admin decides from a Discord handle and an
optional server nickname, and a member whose handle does not resemble their in-game name is exactly
the case the automatic collision check misses. Purging the row ends the ability to reopen it.

So the retention window and the recovery window are **the same window**. 90 days is the trade: long
enough that a mistakenly refused member who comes back weeks later can still be reinstated rather
than re-registered, short enough that an assessment of a stranger is not kept for years. It is
configuration precisely because that balance is a judgement, not a fact.

### It reuses the deletion path rather than writing its own

`UserDeletionService` owns the foreign-key ordering — a genuine landmine, rediscovered in production
more than once. A second set of deletes would be a second place for that ordering to be right, and
therefore a second place for it to drift.

`decide` admits `REJECTED` only from `PENDING`, so a rejected account never held authorities and
owns nothing to cascade. The reuse is still the right call: it costs nothing when there is nothing
to delete, and if such an account ever *does* arrive holding data, `UserDeletionService` reassigns
the shared aggregates to an admin instead of destroying them — which a purpose-built delete written
on the assumption "it owns nothing" would not.

### The database half commits before the Keycloak delete

The ordering from ADR-0111, for the same reason. A rolled-back database half leaves the Keycloak
user intact, so the next run re-reads a whole, consistent registration. The reverse order strands
the exact row this sweep exists to remove: an `app_user` record whose Keycloak account is already
gone, which no longer matches any deletion precondition and which the roster sync would then mark
`in_keycloak = false` forever.

Each registration commits in its own transaction, so one unpurgeable row cannot roll back the rows
already swept, and a run interrupted halfway keeps its committed work.

The candidate list is re-checked **inside** the transaction. Between the query and the purge an
admin may have reopened a registration; re-reading closes that race rather than narrowing it.

### It is automatic, unlike the audit-log purge

ADR-0038 made the audit-log retention purge a deliberate admin action, because an audit trail is
evidence and deleting it should be someone's decision. A refused registration is the opposite: its
purpose ended at the moment of refusal, nobody is served by the row's continued existence, and
leaving the cleanup to a human means it does not happen — which is precisely the state this ADR
found.

## Consequences

**Good**

- The data of people who were *refused* the service stops accumulating without limit.
- The free-text rejection reason acquires a lifetime.
- The application gains a deletion path for a state that had none, without a new admin surface to
  maintain.
- A stale `REJECTED` row can no longer block a Discord snowflake (`discord_user_id` is UNIQUE) for a
  person who reapplies years later.

**Costs, accepted**

- **A rejection older than the window can no longer be reopened.** The recovery of REQ-SEC-034
  becomes time-bounded. An admin who wants to keep a specific refusal recoverable must reopen it
  before the window expires, or raise `max-age`.
- **The organisation forgets that it refused someone.** After the window a reapplication looks like
  a first application. That is the intended consequence of not keeping a permanent file on
  non-members; an admin who needs the history of a specific decision must record it outside the
  tool.
- **The sweep deletes without a human in the loop.** Mitigated by the re-check inside the
  transaction, the per-row transaction boundary, the `USER_DELETED` audit row each purge writes, and
  the `enabled` kill-switch.

**Neutral**

- No new `AuditEventType`. The purge records `USER_DELETED` like any other deletion; running without
  a security context, the actor resolves to `null` / `"system"`, which is what distinguishes it from
  an admin's deletion. One real-world act, one event type.

## Alternatives considered

**An admin-triggered purge button, as in ADR-0038.** Rejected: it repeats the failure that produced
this ADR. The rows were already visible under REQ-SEC-034 and nobody removed them, because nothing
made it anyone's job at any particular moment. A retention window is a decision made once; a button
is a decision deferred forever.

**Purge immediately on rejection.** Rejected: it would delete REQ-SEC-034's reopen affordance
outright, and a rejection is the single most likely admin action to be wrong — it is decided from a
Discord handle alone.

**Anonymise instead of delete** (clear the identifying columns, keep the decision). Rejected: it
keeps a permanent marker on a person the organisation has no relationship with, and the marker's
whole value would be recognising them on a later application — which is the retention this ADR
concluded there is no basis for. It also would not reach the Keycloak user, which is where the
e-mail address and the Discord link actually live.
