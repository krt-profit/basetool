# ADR-0413 — Every export section names its processing, and the export carries the Art. 15(1) and (2) information

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-058 · ADR-0185 · ADR-0412 ·
  [`docs/privacy/processing-activities.md`](../privacy/processing-activities.md) ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

Art. 15(1) GDPR gives the member the copy of their data **and** the purposes, the categories, the
recipients, the retention period, their rights, the right to complain, the source of data they did
not provide and the existence of automated decisions; Art. 15(2) adds the safeguards of a
third-country transfer. The EDPB guidelines on the right of access (01/2022, para. 113) require
this information to be cut to the person; a pointer to the privacy policy is not enough.

The export carried the data and an Art. 15 / Art. 20 marker per section, and nothing else. The
information existed only in the privacy policy and the record of processing activities, and the
"not included" texts of the PDF contradicted the policy on logs and the sign-in service's data.

## Decision

1. **Every section names its processing**: `Section.processedAs(activity, retention, sources…)`.
   The activity is one of the record of processing activities (A1 account, A2 operations, A3
   banking, A4 audit, A5 notifications, A6 terms, A9 data-subject requests, A11 connected
   applications, A12 purchasing) and fixes purpose and lawful basis; the retention rule is one row
   of the record's retention table and of section 18 of the privacy policy; the sources say who
   entered the rows (the member, other members, the tool, Discord, a connected application).
   `PersonalDataExportRegistry` refuses to start with a section that names none.
2. **The texts live in the backend bundles**, one key per activity's name, purpose and basis, per
   retention rule and per source (`pdf.export.activity.*`, `pdf.export.retention.*`,
   `pdf.export.source.*`), next to the general information (`pdf.export.info.*`). They restate the
   record and the policy and change with them.
3. **The export carries the information, cut to the member** (`DataExportInformation`, built by
   `DataExportInformationService`): only the activities, rules and sources its sections use; the
   recipients of section 18 of the policy, Discord only for an account linked to Discord; the
   rights; the competent supervisory authority; the statement on Art. 22; the third-country
   transfer only for an account linked to Discord; and the surfaces the export does not cover.
4. **Both documents show it.** The JSON carries the codes per section and the information with
   its texts; the PDF states the information first and then lists the sections grouped by
   activity, each group with its purpose and lawful basis, each section with its row count,
   source, retention and right.
5. **The "not included" texts agree with the privacy policy**: logs carry a pseudonymous account
   id and are compiled on request, metrics and traces carry none; the sign-in service's data,
   including its 30-day event log, is compiled on request; the e-mails the delivery service keeps
   for 28 days are compiled on request; backups are not searched.

## Consequences

- A new section cannot reach a running export without its purpose, retention and source, and a
  new activity, rule or source fails the build until all three bundles carry its text
  (`DataExportInformationLabelCoverageTest`).
- The section catalogue pins each section's processing, so a change of purpose, retention or
  source shows in review.
- A change of a purpose, basis, retention period or recipient in the privacy policy or the record
  of processing activities changes the bundle texts in the same pull request.

## Alternatives considered

- **A fixed block copied from the privacy policy.** Less work, but not cut to the person, which
  the EDPB guidelines ask for, and it would list recipients and transfers a member does not have.
- **A lawful basis per section instead of per activity.** It would restate decisions the record
  takes per activity and could drift from it; the activity keeps the record as the single source.
