# ADR-0414 — The portability marker follows the lawful basis and who provided the rows

- **Status:** Accepted
- **Date:** 2026-11-26
- **Deciders:** @greluc
- **Related:** spec REQ-SEC-058 · ADR-0185 · ADR-0413 ·
  [`docs/privacy/data-subject-requests.md`](../privacy/data-subject-requests.md)

## Context

Every export section is marked `ART_15` (access) or `ART_15_20` (access and data portability).
Art. 20(1) GDPR grants portability for data the person **provided** and only where the processing
rests on **consent or a contract** (Art. 6(1)(a) or (b)). The WP29 guidelines on data portability
(WP242 rev.01, endorsed by the EDPB) count data the person actively gave and data observed from
their use of the service, not data others recorded about them.

The markers had been set section by section, by hand, and several broke one of the conditions:
- the purchasing appointment, decided by the leadership, was marked portable;
- the member's own bank requests were marked portable, although the privacy policy keeps the
  bank's records under Art. 6(1)(f);
- the aUEC money sections were marked portable, although they mix rows other members recorded
  about the member;
- the account section was marked portable, although it holds rank, join date and approval set by
  the organisation and the nickname Discord reports.

The marker was also called `legalBasis` in the code and the JSON, and "Rechtsgrundlage" in the
PDF. Since ADR-0413 the export names the real lawful basis of each activity, so that name now
misleads twice.

## Decision

1. **The marker is the data subject's right**: the enum is `PersonalDataExportSection.Right`, the
   JSON property `right`, the PDF column "DEIN RECHT".
2. **Each processing activity says whether its lawful basis includes consent or a contract**
   (`Activity.isPortable()`). Banking, audit and data-subject requests do not.
3. **A section may be marked `ART_15_20` only when its activity is portable and the member provided
   its rows**, that is, the member or their connected application is among its sources
   (`Processing.allowsPortability()`). `PersonalDataExportRegistry` refuses any other section
   marked portable, so the application does not start with one.
4. **A section that mixes the member's rows with rows others recorded is `ART_15`**, and the
   rationale says so. Where the member's provided part matters on its own, the section is split
   instead. The account is split: `account` holds what the member provided and is portable;
   `accountStatus` holds what the organisation, Discord or the tool set and is not.

## Consequences

- The marker can no longer contradict the privacy policy's lawful basis or the sources a section
  declares. A wrong marker stops the application at startup and fails the registry tests.
- A mixed section is marked by its strictest part. Its rows are still in the machine-readable JSON,
  so the member loses no data, only a portability claim the law does not give for those rows.
- The JSON property `legalBasis` is now `right`. A tool reading the export follows the rename.

## Alternatives considered

- **Split every mixed section by author.** The aUEC money tables do not record a single author per
  row in a way the export can filter on without restating the aUEC contract, and the split would
  double those sections. Marking them `ART_15` is accurate and keeps their content whole.
- **Keep the markers set by hand.** That is how the four contradictions arose.
