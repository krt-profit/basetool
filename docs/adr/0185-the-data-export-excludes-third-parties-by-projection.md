# ADR-0185 — The data export excludes third parties by projection, and scrubs only where a projection cannot reach

- **Status:** Proposed
- **Date:** 2026-09-15
- **Deciders:** @greluc (the export itself and the anonymisation requirement as decision 7 during
  the cloud session; the admin variant and the PDF's depth on 2026-09-15)
- **Related:** spec `REQ-SEC-058` (new) ·
  [`data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-008`, whose foreign-key
  enumeration the section list is derived from) ·
  [`audit.md`](../specs/audit.md) (`REQ-AUDIT-001`, `REQ-AUDIT-006`) ·
  [`observability.md`](../specs/observability.md) (`REQ-OBS-004`, why no handle is logged) ·
  [ADR-0181](0181-self-service-deletion-is-a-request-an-admin-executes.md) ·
  [ADR-0184](0184-the-person-search-is-a-checked-registry-not-a-schema-sweep.md) ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

An Art. 15 access request meant a person opening roughly twenty-five tables by hand. That is slow,
and — the part that matters — it is the kind of task that quietly produces an incomplete answer,
where neither the controller nor the data subject can tell that a section was forgotten.

@greluc decided the export should exist, be self-service for every member, come in JSON and PDF, and
carry a hard constraint (decision 7): **all personal data not relating to that member must be
anonymised.** That constraint is what makes the feature difficult, because the data is relational.
Almost every interesting row pairs the requester with somebody else:

- a **mission participation** sits in a mission with other participants;
- a **bank booking** has a counterparty and an initiating employee;
- a **material-exchange interest** has an offering member;
- an **audit row** has an actor and sometimes a target — and where the requester is the *target*, the
  actor is another person;
- **free text** the requester wrote may name anybody, anywhere in a sentence.

The obvious implementation — dump the rows, then redact — fails on exactly the cases that matter. A
redaction pass has to *find* other people in already-selected data, and when it misses one, nothing
says so.

## Decision

**The projections are the anonymisation.** `DataExportSections` holds one written SQL statement per
section, each listing the columns it returns, and **no statement selects another member's id or
handle.** A counterparty leak would have to be written into a visible `SELECT` list, where a reviewer
can see it — and where the integration test catches it.

Three sections exist specifically to make that concrete, and each says so in a comment beside its
SQL:

|           Section            |          What is deliberately not selected          |
|------------------------------|-----------------------------------------------------|
| `missionParticipations`      | the mission's other participants, entirely          |
| `bankBookingsAsCounterparty` | `initiated_by` — the bank employee who booked it    |
| `auditActionsOnMember`       | `actor_handle` — the person who acted on the member |
| both audit sections          | `subject_label` — which can itself be a person      |

**Scrubbing is used in exactly one place, because there a projection cannot reach.** A note the
member wrote is *their* data and must be in the export, and it may name somebody else mid-sentence.
`HandleScrubber` replaces other members' handles, case-insensitively, **longest match first** — or
"Val" leaves "kyrie" behind from "Valkyrie", which is simultaneously a leak and a corruption — and
skips handles under three characters, because a two-character handle occurs inside ordinary words
constantly and replacing it would shred every note in the document. Only the sections listed in
`FREE_TEXT_SECTIONS` are scrubbed: running the scrubber over structured values could corrupt a
material name or an account number that happened to contain a handle as a substring, and a corrupted
export is worse than a verbose one.

**The scrubber's reach bounds where it may be used at all — added 2026-09-16.** It is built from
`userRepository.findAll()`, so it knows **registered members and nothing else**. Two kinds of person
are therefore permanently invisible to it: somebody who never had an account, and somebody whose
account is already gone. A column that can hold either **cannot be protected by listing its
section** — the scrubber would pass it through unchanged *and* report
`thirdPartyHandlesRemoved = false`, which the privacy record reads as "nothing to look for". Such a
column must be left **unselected**. This is not a refinement of the decision but the decision's own
logic; the first implementation simply did not apply it to `audit_event.subject_label`, and the
consequence is recorded below.

**The subject's own handle is never scrubbed.** It is the one name the export is about.

**The residue is stated, not hidden, and is covered by a human.** The scrubber cannot recognise
somebody with no account, a nickname or a misspelling. So the export **reports whether it removed
anything**, which tells the reviewer whether there is something to look for, and
`docs/privacy/data-subject-requests.md` requires an admin to read the free text before release, per
Art. 15(4). The code makes that review short; it does not replace it. Any claim that it does would be
the most dangerous sentence in this document.

**Each section carries its legal basis** (`ART_15` / `ART_15_20`) plus a one-line reason, so the
Art. 20 portable subset is identifiable without re-deriving which sections qualify, and so the
document explains its own structure.

**The PDF is a summary and the JSON is the full disclosure** (@greluc, 2026-09-15). The PDF prints
the master data in full — it is short, and it is what a person actually wants to see — then an
**inventory of every section with its row count and legal basis**, so the document is complete about
*what* is held even where it does not print the rows, and names the out-of-scope surfaces. The note
explaining that it is a summary is in the document, because a reader must not have to guess whether a
short list means "that is all there is".

**An admin variant exists** (@greluc, 2026-09-15) for a request from somebody who cannot sign in — a
locked-out member, a disabled account. It uses the **same projections and the same anonymisation**:
an admin export is not a fuller one, because a third party's data is no more disclosable to an admin
serving an Art. 15 request than to the member.

**Both paths are audited** with `bySelf` distinguishing them, and neither the payload, the log line
nor the PDF filename carries the subject's handle — a filename reaches shells, logs and mail clients.

## Consequences

- An access request is now one click instead of an afternoon, and the same for every requester —
  which is the half of the right that the manual process could not guarantee.
- **The section list is hand-written and must be maintained.** A new table holding personal data
  needs a section, and nothing fails the build if it is forgotten. That is a real gap, and it is
  narrower than it looks: `DataExportSections` is derived from REQ-DATA-008's foreign-key
  enumeration, which *is* gate-enforced by `UserIdentityColumnForeignKeyTest` — every new
  user-identity column must declare a foreign key to `app_user(id)` or record an exemption. So a new
  store of personal data cannot appear silently; it appears as a new FK, and this ADR is where the
  reader is told to add a section when it does.
- The integration test runs all ~30 statements against the real schema. A registry of hand-written
  SQL is exactly the kind of thing that compiles while naming a column renamed two migrations ago,
  and the first person to find out must not be somebody answering a legal request.
- **The leak test asserts across the whole document, not per section.** A leak introduced by a future
  section would otherwise only be caught by somebody re-reading that section's SQL.
- Free text in the export is scrubbed but not guaranteed clean. The privacy record carries the
  review step, and the export tells the reviewer whether it matters.
- The scrubber loads every other member's handle per export. At this organisation's size that is a
  few hundred strings; it would want revisiting at a scale this application is not built for.

### Corrected 2026-09-16 — `subject_label` was selected and should never have been

Both audit sections shipped selecting `audit_event.subject_label`, and the acceptance criterion
"no other member's handle appears anywhere in an export" was not met.

The column looked safe because REQ-AUDIT-001 calls it a non-personal snapshot, and three code
comments repeated that the job-order variant was "a non-personal order title and is safe to
snapshot". It is not: `job_order.handle` is the order's **contact person**
(`orders.create.handle` — "Handle des Ansprechpartners"), usually an external customer, and
`ACCOUNT_DELETION_REQUEST_EXECUTED` snapshots a member's own effective name. The same PR that
shipped the export had already registered `audit_event.subject_label` as a person-name surface in
`PersonSearchTargets`, so the two halves of one change contradicted each other.

Fixed by **dropping the column from both sections**, not by listing them in `FREE_TEXT_SECTIONS` —
for the reason added to the Decision above: an external contact is exactly the person the scrubber
cannot see. The remaining `occurred_at` / `domain` / `event_type` carry the Art. 15 substance, and
`subject_id` was never selected, so the export did not identify the object in the first place.

Two further things were wrong with the guard rather than the code, and both are fixed:

- **The test could not have caught it.** `noOtherMembersHandleAppearsInTheExport` seeded only a
  `personal_inventory_item.note` — a listed free-text section — and never wrote an `audit_event`
  row, so it passed over the gap. It is now joined by a case that seeds audit rows in both
  directions, one labelled with a contact who has **no account** (which no scrubber could catch),
  and by a structural assertion that no section's SQL contains the column at all.
- **Four more sections selected a person-name surface without being scrubbed:** `hangar`
  (`ship.name`), `missionsManaged` (`mission.name` — already scrubbed in the two sibling sections
  that select the same column), `notificationRuleTargets` (`notification_rule.description`) and
  `bankAccountGrants` (`bank_account.name`). These *are* scrubber-reachable, since they name a
  thing rather than an outsider, so they were added to `FREE_TEXT_SECTIONS`.

## Corrected after review — 2026-09-16

Six things, and the first three were in the scrubber — the one part of this design that scrubs
rather than omits, and therefore the one part where a defect is a leak rather than a gap.

1. **The scrubber spliced the original text at offsets found in a lower-cased copy.**
   `String.toLowerCase` is not length-preserving — U+0130 lowercases to two characters — so a match
   after such a character leaked a prefix of the third party's handle while the export still
   reported the removal as complete, and a match near the end threw `IndexOutOfBoundsException` out
   of both export endpoints. Any member could put that character in their own note and then request
   their own export. It is one forward pass over the original text now, matching with
   `String.regionMatches`, which cannot drift.

2. **The scrubber rewrote its own placeholder.** Each handle scrubbed the previous handle's output,
   so a three-character handle that is a substring of the replacement text was substituted *inside*
   a placeholder an earlier pass had written — eleven such handles turned a 30-character note into
   1780 characters. `displayName` is self-service, so any member could pick one and corrupt the free
   text in every other member's export. The forward pass cannot re-read what it has emitted.

3. **The scrubber knew one spelling per member.** Built from `getEffectiveName()` alone, it never
   saw a third party's `username` (whenever they had set a display name) or anybody's
   `discord_guild_nickname`, while the document still told the reader that other members' names had
   been removed. All three columns are registered in `PersonSearchTargets` as places a person is
   named, so the registry and the scrubber were answering the same question differently. All three
   are loaded now.

4. **The scrub gate is the column, not the section.** This ADR argued the section-wide gate was an
   accepted trade — "a collision corrupts one value in one person's export, whereas the alternative
   discloses a name". The trade was real and it was not necessary. The `account` section selects
   `username`, `email`, `approval_status` and six more identity fields, all `String` on the wire,
   and another member's three-character handle inside the subject's own e-mail address was replaced
   — so the export handed the member a corrupted copy of their own identity, which is the failure
   this ADR's own note predicted and accepted. Naming the prose columns costs one line per column.

5. **The registry pair is gate-enforced.** `DataExportScrubCoverageTest` walks every section's
   `SELECT` list and fails the build unless each column that `PersonSearchTargets` registers as a
   person-name surface is either scrubbed or recorded in `UNSCRUBBED_PERSON_COLUMNS` with a reason
   — and unless every entry in either registry names a section and a column that exist. Without
   that, `notificationRuleTargets` could select an administrator's free text unscrubbed (it did),
   and a renamed key could drop out of the scrub set while the export kept reporting success.

   **Widened 2026-09-17:** the gate asked the *searched* half of `PersonSearchTargets` only,
   so a column the search exempts left its question entirely. The technical-payload exemptions
   rest on reachability rather than absence — `notification.params` holds a handle and is
   skipped because searching it returns one hit per admin inbox for the same event — so
   `notifications.params` was scrubbed by the export and required by nothing, the single entry
   in the registry whose deletion no test would have caught. `EXEMPT_BUT_MAY_HOLD_A_NAME` names
   that class and the gate reads it as well.

6. **The per-section legal prose is no longer shipped.** `Section.portableHint` was ~30 English
   sentences serialised into the member's JSON download, which the i18n rule forbids and which the
   PDF never rendered — user-visible text with no bundle key anywhere. The wire carries
   `legalBasis`, which is machine-readable and locale-free; what the two bases mean is explained in
   the PDF, in the reader's language. The prose stays beside the SQL as the recorded reason for the
   classification, and a test keeps every section carrying one.

Two runtime defects on the same endpoints were fixed with these and are worth recording because
neither was visible to any test: `produces = APPLICATION_PDF_VALUE` on the two PDF mappings is a
*mapping condition*, so both answered **406** against the frontend's `Accept: application/cbor,
application/json`; and the JSON export returned a POJO, so it negotiated its way to **CBOR** and was
served to the member as `datenauskunft.json`. Both content types are pinned on the `ResponseEntity`
now, which is what every other download endpoint in the codebase already did.

## Alternatives considered

- **Localise the ~30 per-section hints into the three bundles.** Rejected: it would put thirty
  sentences of legal prose into the translation surface to say something `legalBasis` already says
  machine-readably, and the human explanation of the two bases belongs in the PDF, which is
  localised already.
- **Keep the section-wide scrub gate and accept the corruption.** Rejected — see correction 4. The
  argument for it was that the alternative was expensive; the alternative is one line per column.
- **`Pattern.compile(quote(handle), CASE_INSENSITIVE)` for the scrubbing.** Considered again after
  the offset defect, and not needed: a single forward pass with a first-character bucket index is
  both correct and faster than compiling a pattern per handle per value, which is the cost the
  original hand-rolled loop existed to avoid.
- **Dump the rows and redact afterwards.** Rejected, and it is the crux of this ADR: a redaction has
  to find other people in selected data, and a miss is silent. Not selecting is verifiable by
  reading a `SELECT` list.
- **Withhold every row that has a counterparty.** Rejected: it would withhold most of the member's
  own history — their bookings, their mission participation — and Art. 15 is a right to *their* data,
  not to the intersection of nobody's.
- **Withhold free text entirely.** Rejected for the same reason, and because Art. 15(4) says the
  opposite: redact the third party's name, do not withhold the entry.
- **A full PDF of every row.** Rejected by @greluc: an active member's export is thousands of rows,
  and a document nobody reads serves the right of access worse than a short one that states exactly
  what exists.
- **Self-service only, no admin variant.** Rejected by @greluc: an access request does not always
  come from somebody who can log in, and serving those by hand is the work this feature exists to
  remove.
- **A richer admin export.** Rejected on principle: a third party's data is not more disclosable to
  an admin than to the data subject.
- **Generate the sections by reflection over the JPA model.** Rejected: it would select whole
  entities including their associations — which is precisely how another member's handle gets into
  the document — and it could not mark a legal basis or omit an actor column.
- **Recording the subject's handle in the audit payload or the filename.** Rejected: it would make
  every export a second place the name is written, including in shell histories and mail clients.
