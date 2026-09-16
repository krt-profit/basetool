# ADR-0184 — The Personensuche is a written registry checked against the schema, not a schema sweep

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc (scope: every free-text column, 2026-09-15; case-insensitivity confirmed the
  same day)
- **Related:** spec `REQ-SEC-060` (new) ·
  [`security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-062`, the erasure this
  search feeds) · [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`, why the term is not in the
  payload) · [`observability.md`](../specs/observability.md) (`REQ-OBS-004`, why it is not in a log
  line) · [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`) ·
  [ADR-0181](0181-self-service-deletion-is-a-request-an-admin-executes.md) ·
  [ADR-0183](0183-a-granted-erasure-anonymises-the-handle-snapshots-in-place.md) ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

The GDPR gap analysis found a class of request the application could not serve **at all**: an
Art. 16 rectification or Art. 17 erasure from somebody whose name is in the system but who has no
account.

The names are really there. `mission_participant.guest_name` holds an external participant, typed
by hand. `mission.party_lead_guest_name` holds a party lead with no account.
`job_order_handover.recipient_handle` holds whoever collected the material, spelled however the
person writing the receipt spelled it. `org_chart_position.name` holds a placeholder for somebody
not yet in the roster. `user_approval_event.reason` holds an admin's written assessment of an
applicant. None of those has a foreign key to `app_user`, so no amount of walking the object graph
finds them.

For a member it was not much better. Their handle can appear in a note somebody else wrote, and a
rectification that fixes one of four occurrences is not a rectification.

`docs/privacy/data-subject-requests.md` was written during this same piece of work and already
points the reader at **Admin → Personensuche** for every Art. 16/17 request. When that document
landed, the screen did not exist. So the choice was to build it or to correct the record — and a
record that says "we cannot find all mentions of you" is not a better answer.

@greluc chose the broad scope: **every free-text column of the schema**, not just the handful the
plan had enumerated.

## Decision

**A written registry, `PersonSearchTargets.TARGETS`**, naming each searched
`(area, table, column, idColumn, linkKind)`.

**Not a reflection sweep over every text column.** The schema has ~270 `String` columns and the
large majority are catalogue rows synced from UEX and the SC wiki: planet and moon names,
manufacturer nicknames, ship-type brochure URLs, game-item slugs. Nobody types into them, they
describe places and things rather than people, and a handle matching one would be a coincidence of
spelling. Sweeping them would not merely cost time — it would **bury the real hits**, which is the
failure mode that matters when somebody is working through a legal request.

**But "every free-text column" is a promise, so it is gate-enforced.**
`PersonSearchCoverageTest` sweeps `information_schema` for every text column of every base table and
fails the build unless each is either searched or recorded in `EXEMPT_COLUMNS` / `EXEMPT_TABLES`.
This is the load-bearing half of the decision, and it is what makes a registry acceptable at all:
without it, the list would go quietly out of date the first time somebody added a notes field, the
privacy record's instruction would become false, and **the person whose data it is would have no way
to know.** The exemptions are grouped and each group carries its reason — synced catalogue data,
`@Enumerated` values stored as varchar, identifiers and slugs, authorship stamps holding a subject
id, machine-written payloads. Two `created_by` columns are *searched* rather than exempted, because
`MaterialExternalAliasService` writes the principal **name** there while the others write an id.

The test also asserts that every registered column and id column still exists, so a rename fails in
CI rather than during a real request; and it runs the assembled statement against the real schema,
because a 70-branch `UNION ALL` is exactly the kind of SQL that is valid in every unit test and
wrong in production.

**Case-insensitive, always** (`ILIKE`). Whoever typed the name was not copying from a roster. The
term's `%`, `_` and `\` are escaped: an unescaped pasted `%` matches every row of every searched
column, which is indistinguishable from a deliberate attempt to dump the database and is an
accident an admin could have by pasting.

**Bounded by construction:** at least 3 characters, 25 hits per column, 300 in total — and **the
response says when it was capped**, surfaced as a warning rather than a footnote. A capped list that
looked complete would make an erasure look complete when it is not.

**One `UNION ALL` statement** with a per-branch `LIMIT`: one plan, one round trip, no column able to
crowd out the others. Identifiers cannot be bound as parameters, so they are interpolated — and
validated against `^[a-z_][a-z0-9_]{0,62}$` first, even though they come from a compile-time
constant list, so a future edit that pastes something else into the registry fails loudly.

**The search is audit-logged; the term is not.** A read recorded in a mutation-only trail, because
its misuse would otherwise leave no trace at all. The payload carries the term's **length**, the hit
count and whether it was capped. Recording the term would build a second store of exactly the data
the search exists to help remove — a list of every name an admin has ever looked for — and
REQ-AUDIT-001 keeps user free text out of the payload regardless. The term is kept out of every log
line for the same reason (REQ-OBS-004).

**Hits are listed, not edited.** Area, `table.column`, the matched text clipped to 200 characters,
and a link where the row has a page. Rectification happens on the record's own screen.

## Consequences

- An erasure or rectification request from a non-member is answerable for the first time, and
  `docs/privacy/data-subject-requests.md` now describes a screen that exists.
- **Adding a free-text column now has a required step**: register it or exempt it with a reason.
  That is a deliberate tax on every future feature, and it is the cheapest available way to keep a
  promise of completeness true.
- The exemption list is long (~110 entries plus 23 whole tables) and reads as bureaucracy. It is the
  audit trail of the decision: each line is somebody having judged that a column cannot name a
  person.
- **The search can over-match, and must.** A handle is not a unique key, so two people can share a
  spelling. The admin reviews hits before acting, which is stated on the page and in the privacy
  record — and it is the reason REQ-SEC-062's text-matched erasure is gated behind a human reading
  this list.
- `history: true` on the results swap puts the searched name in the browser's history. Accepted: it
  is the same exposure as any search box, and the alternative (a POST) would make the page
  un-refreshable in the middle of handling a request.
- The page needs no JavaScript to work — the GET form is the fallback — which matters for a screen
  used while answering a legal request under a deadline.

## Alternatives considered

- **Only the five surfaces the handover plan enumerated** (`guest_name`, handover recipient,
  org-chart placeholder, notes, booking reasons). Rejected by @greluc: it is a list without a
  mechanism, so it would have drifted the same way, and it silently excludes mission descriptions,
  step titles, objective titles and material-exchange remarks — all places a name plainly goes.
- **Sweep every text column at runtime, with no registry.** Rejected: ~200 catalogue columns of
  place and product names would dominate every result set, and the search would be slowest and
  noisiest exactly when it is being used under a deadline. It also could not carry a per-area label
  or a link.
- **A registry with no coverage test.** Rejected, and this is the crux: an unchecked list plus a
  document telling admins to rely on it is worse than no feature, because the gap is invisible to
  everyone including the data subject.
- **Postgres full-text search (`tsvector`) with generated columns and GIN indexes.** Rejected for
  now: it needs a migration per column, it stems and tokenises (so a substring of a handle stops
  matching, which is the opposite of what this needs), and the query volume is an admin running it
  a handful of times a year.
- **Case-sensitive matching.** Rejected outright by the nature of the data.
- **Editing hits in place.** Rejected: it would add a second write path to a dozen aggregates,
  bypassing each one's own validation and optimistic locking.
- **Recording the search term in the audit event.** Rejected: it creates a permanent list of the
  names admins have searched for, inside the trail whose own retention this work just had to bound.

