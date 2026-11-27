# ADR-0415 — The export carries the member's own acts, without the other person

- **Status:** Accepted
- **Date:** 2026-11-27
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-058 · REQ-DATA-021 · ADR-0185 · ADR-0412 · ADR-0413 · ADR-0414 ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

Every column that references a member is exported by a section or listed as not exported with a
reason (REQ-DATA-021). Twenty-nine columns record the member as the one who acted on another
member's or the organisation's record: an admin deciding a registration or an erasure request, a
bank employee booking or deciding a request, an account owner approving one, a leader appointing a
trade delegate, a lead receiving a donation, an officer marking a payout, an admin triggering a
patch change or applying a catalogue import. They were listed as "names another person who acted on
the row, a third party", which is true for the member whose record it is and wrong for the member
who acted.

For the acting member, the column is information relating to them (Art. 4(1) GDPR): it records
what they did, when and in which role. Art. 15(1) and (3) give them access to it. The record also
holds the other member's data, which Art. 15(4) keeps out of the copy.

## Decision

1. **Every column that records the member as the actor has an own-acts section** in the module
   that owns its table, at positions 600 to 690 after the member's own records.
2. **The section selects the act, never the other person.** It returns what the member did (kind,
   amounts, status, when, and which of several roles on the row was theirs) and does not select the
   other member's id, name snapshot or free text, nor a name typed for somebody without an account.
   The member's own text on the act, such as a pause reason or a purchase reason, is selected and
   scrubbed like other free text.
3. **The sections are `ART_15`.** The acts are done in a role for the organisation and describe
   other people's affairs; the privacy policy bases the organisation's administration and the
   bank's records on Art. 6(1)(f), so Art. 20 does not reach them.
4. **Each section keeps its record's retention.** The account deletion nulls the member's link on
   most of the records, which then stay without it (`UNLINKED_ON_DELETION`); an act on the
   organisation's catalogue or every org unit's data is kept as evidence, after the deletion with
   the internal id only (`ADMIN_ACT_PERMANENT`); the catalogue import job is pruned seven days
   after it was started and the exchange bulk undo after 90 days.
5. **The organisation's records on which an own-acts section names the member are role-holder
   tables** of `PersonalDataExportColumnCoverageTest`: their other columns describe the record, not
   the member, and are not swept.
6. **The not-exported list keeps only mirrors of an exported record**: `app_user.approved_by_id`
   beside the approval events, and `mission_ownership.owner_id` beside `mission.owner_id`.

## Consequences

- The export answers for everything the member did in the tool, not only for what was done to their
  own records. It grows by 21 sections; the PDF shows them under their activity.
- A new column naming the member as the actor gets an own-acts section in the same change, not an
  entry in the not-exported list.
- `DataExportEndStateTest` seeds every act with a second member as the counterpart and proves the
  copy names neither that member nor their text.

## Alternatives considered

- **Keep the columns excluded as third-party data.** That reads the row only from the record
  owner's side and withholds from the acting member data about them.
- **Export the act with the other person.** The copy would hand over another member's data against
  Art. 15(4), and the scrubber cannot see a name typed for somebody without an account.
- **One cross-module "acts" section.** It would read the tables of ten modules from one place,
  against the section-per-owning-module rule (`PersonalDataExportOwnershipTest`), and could not name
  one processing activity.
