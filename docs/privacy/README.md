# Data protection (GDPR) — documentation index

> **Doc type:** Living documents — kept in sync with `main`. Last reviewed: 2026-09-16.

This folder holds the **organisational** half of the project's data-protection obligations: the
documents the GDPR requires a controller to *have*, as opposed to the behaviour it requires the
software to *implement*. The implementing requirements live where every other requirement lives, in
[`docs/specs/`](../specs/INDEX.md) under the `SEC` area.

|                                    Document                                    |   Obligation   |                                       What it answers                                        |
|--------------------------------------------------------------------------------|----------------|----------------------------------------------------------------------------------------------|
| [`processing-activities.md`](processing-activities.md)                         | Art. 30        | What personal data is processed, why, on what legal basis, for how long, and who receives it |
| [`technical-organisational-measures.md`](technical-organisational-measures.md) | Art. 32        | How the data is protected, and where each measure is enforced                                |
| [`processors.md`](processors.md)                                               | Art. 28        | Which third parties touch personal data, in what role, under what contract                   |
| [`data-subject-requests.md`](data-subject-requests.md)                         | Art. 12, 15–21 | What to do when someone exercises a right, with the deadline and the exact steps             |
| [`data-breach-runbook.md`](data-breach-runbook.md)                             | Art. 33, 34    | What to do in the first 72 hours of a personal-data breach                                   |
| [`dpia-threshold-assessment.md`](dpia-threshold-assessment.md)                 | Art. 35, 37    | Why no data-protection impact assessment and no data-protection officer are required         |

## The implementing requirements

The behaviour these records describe is specified, gated and tested like everything else. When a
reader of one of these documents wants to know *how* something is enforced, it is here:

| Requirement                                      | What it implements                                                                     |
|:-------------------------------------------------|:---------------------------------------------------------------------------------------|
| [`REQ-SEC-058`](../specs/security-and-access.md) | Art. 15 / Art. 20 export, self-service and admin, third parties excluded by projection |
| [`REQ-SEC-059`](../specs/security-and-access.md) | A half-finished account deletion is observable                                         |
| [`REQ-SEC-060`](../specs/security-and-access.md) | The admin Personensuche across every free-text surface                                 |
| [`REQ-SEC-061`](../specs/security-and-access.md) | Art. 17 erasure as a request an admin decides                                          |
| [`REQ-SEC-062`](../specs/security-and-access.md) | A granted request anonymises the surviving handle snapshots                            |
| [`REQ-SEC-057`](../specs/security-and-access.md) | A refused registration is purged after 90 days                                         |
| [`REQ-AUDIT-006`](../specs/audit.md)             | Both audit trails are swept on a 24-month ceiling                                      |
| [`REQ-NOTIF-009`](../specs/notifications.md)     | The two notification retention windows                                                 |

Decisions: [ADR-0178](../adr/0178-a-refused-registration-is-purged-on-a-retention-window.md) …
[ADR-0185](../adr/0185-the-data-export-excludes-third-parties-by-projection.md).

## The three surfaces that must agree

A data-protection statement is only worth what the system behind it does. Three surfaces describe
the same facts and drift apart silently, so a change to any one of them is incomplete until the
other two match:

1. **The privacy policy** — `privacy.*` in `frontend/src/main/resources/messages{,_de,_en}.properties`,
   rendered at `/privacy`. This is the published promise.
2. **The code** — retention sweeps, deletion paths, scope gates, log masking.
3. **These documents** — the internal record of what the other two do.

When they disagree, the **code is right** and the other two are defects. A privacy policy that
promises more than the code delivers is the worst of the three failure modes, because it is the one
a supervisory authority reads first.

> [!important] Retention periods appear in prose in the privacy policy
> The policy names the numbers (90 days, 180 days, 31 days, 14 days, 180 days for metrics) in
> sentences, while the code reads them from configuration. Changing a configured window without
> changing the sentence makes the published policy false. The windows and their configuration keys
> are tabulated in [`processing-activities.md`](processing-activities.md#retention).

## Scope

These documents cover the Profit Basetool as deployed: backend, frontend, ingest gateway, the
Keycloak SPI and theme, the Android app and the desktop extractor, plus the platform they run on.
They describe **shapes and locations**, never values: no credentials, no internal hostnames, no
member names, no production subject ids. This is a public repository.

## Nothing here is legal advice

These are engineering records written by the people who built the system. They are structured to be
useful to a lawyer or a supervisory authority, not to replace one.
