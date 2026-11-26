# ADR-0412 — The data export decides every column of the member's tables and every table hanging off them

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-058 · REQ-DATA-021 · ADR-0185 · `PersonalDataExportColumnCoverageTest` ·
  `GdprParticipantCoverageTest` · [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

Art. 15 GDPR gives the member a copy of all personal data the controller processes about them, and
the EDPB guidelines on the right of access (01/2022) count observed and derived data, metadata and
pseudonymised records in. The export is assembled from one written statement per section
(ADR-0185), so whatever a statement does not select is missing from the copy, and nothing says so.

`GdprParticipantCoverageTest` (REQ-DATA-021) asks one question: does every column that references a
member have a section or a reason? It cannot see three other ways the export goes incomplete:

- **a new column in a table the export already reads**, such as the pledge details added to `ship`
  or the item a warehouse row names instead of a material;
- **a child table** reached only through a row the export already selects, such as the ores and
  yields of a refinery order or the crew places of a mission participation;
- **a member id stored as text**, such as the learned import aliases and the operation payout keys.

All three happened. A sweep of the schema found about 300 columns of the member's tables and some 30
child tables without a decision.

## Decision

1. **A member table is a table an export section filters by `:userId`**, plus every table that
   holds a foreign key to a member table and that a section reads. Every column of a member table
   is read by a section or listed in `COLUMNS_NOT_EXPORTED` with its reason; every child table of a
   member table is read by a section or listed in `CHILD_TABLES_NOT_EXPORTED`. A listed exclusion
   that no longer holds fails as well.
2. **What a section reads is what PostgreSQL resolves for its statement**
   (`information_schema.view_column_usage` of a temporary view), not a parse of the SQL text. A
   column counts when the statement selects it, joins on it or filters by it.
3. **Three exemptions, each with a reason in the test.** The row id and optimistic-lock counters
   carry no fact. The children of `app_user` are references to the member, which REQ-DATA-021
   decides column by column. `org_unit`, `mission` and `job_order` name the member as one
   role-holder of an organisation's aggregate (grand admiral, organiser, party lead, creating
   member), so their other columns and child tables describe the aggregate.
4. **An exclusion names one of five reasons**: another person who acted on the row; a name typed
   for somebody without an account; free text another person wrote that can name somebody the
   scrubber cannot see (ADR-0185); an internal link that identifies nothing by itself; a count
   over all members. Everything else is exported.
5. **A key condition may compare a text column with `CAST(:userId AS VARCHAR)`**, so a table that
   stores the member's id as text is selected by it and counts as a member table.

## Consequences

- The export gains what the sweep found: the hangar's pledge details, refinery yields, bank
  amounts per account and custodian postings, crew places, finance entries and payout records,
  deliveries, settlements and tranches of purchasing deals, warehouse reservations and item
  names, learned import names and the account's state in the sign-in service.
- A new column or child table of a member's table fails the build until it is exported or given a
  reason, in the same pull request that adds it.
- The test needs a migrated database and runs in the shared Spring test context.
- A table that stores a member id as text without a foreign key is still found only when a section
  selects it; the reference sweep of REQ-DATA-021 matches `uuid` columns.

## Alternatives considered

- **Parse the select lists.** The existing parser stops at the first `FROM` of a subquery and
  cannot follow a `LATERAL` join; PostgreSQL's own resolution has neither gap.
- **Exempt `created_at` and `updated_at`.** They record when the member wrote or changed a row,
  which is personal data like the row itself; they are exported instead.
- **Treat every key table as a member table.** The grand admiral of a unit would pull the unit's
  bank accounts, stock levels and settings into the sweep, none of which is about the member.
