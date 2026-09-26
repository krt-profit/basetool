# 1. Introduction and goals

## 1.1 What the system is

The **Profit Basetool** is a squadron-management web application for the *Star Citizen*
organisation **DAS KARTELL / IRIDIUM**. It is the organisation's operational tool: missions are
planned in it, materials and ships are tracked in it, job orders are queued and worked in it, the
organisation's bank is kept in it, and members administer each other's roles through it.

It is a private tool for one organisation, not a product with customers. That single fact explains
a great deal of the architecture: there is one deployment, one identity provider, one database per
concern, and the tenancy model divides *the same organisation* rather than separate clients.

The feature surface is listed in [`README.md` → *What the application provides*](../../README.md)
and specified per feature under [`docs/specs/`](../specs/INDEX.md). This section does not repeat it.

## 1.2 Quality goals

These are the qualities the architecture is actually shaped by, in the order they win when two of
them conflict. Each one names where it is enforced, because a quality goal nothing measures is a
preference.

| # | Quality goal | What it means concretely | Where it bites |
| --- | --- | --- | --- |
| 1 | **Confidentiality of member data** | Real people's names, Discord handles, e-mail addresses and finances are in here. The public surface is the landing page and the legal pages; everything else needs a login and at least member rights. | ADR-0159, [`security-and-access.md`](../specs/security-and-access.md), the `blackbox-members-only` and `blackbox-public-surface` probes |
| 2 | **Correctness of shared, concurrent state** | Several people edit the same mission, the same stock, the same order. A lost update is a wrong number in a ledger, not a cosmetic glitch. | Optimistic locking with per-section counters (§8), `ObjectOptimisticLockingFailureException` → HTTP 409 |
| 3 | **Recoverability** | The organisation's records exist in one place. Losing them is not survivable by re-entering them. | [`backup-recovery.md`](../specs/backup-recovery.md) `REQ-OPS-008…011`, the nightly restic backup and the weekly restore drill that proves it |
| 4 | **Operability by one person** | There is one maintainer. Anything that needs a second pair of hands at 03:40 is a design fault. | Runbooks under `docs/`, a host rebuilt by the Ansible role, the conformance suite, alerting that names the artefact rather than the symptom |
| 5 | **Auditability** | Who changed what, in the areas where that question is asked after the fact. | `REQ-AUDIT-001`, [`audit.md`](../specs/audit.md) — an append-only trail across twelve audited areas |
| 6 | **Responsiveness of shared surfaces** | A change a peer makes appears without a reload; a page never blanks because one backend call was slow. | `REQ-FE-001…010`, Resilience4j around every backend call, the `/ws/sync` fanout |

**Performance at scale is deliberately not on this list.** The organisation is in the hundreds of
members, not the millions. The architecture spends its complexity budget on confidentiality,
correctness and recoverability instead, and the one place performance is treated as a hard rule —
no N+1 queries — is there because an N+1 on a members list is a *correctness-shaped* problem for
page timeouts, not a scaling ambition.

## 1.3 Stakeholders

| Stakeholder | What they expect from the architecture |
| --- | --- |
| **Members** of the organisation | That the tool is available, that their own data is visible only where it should be, and that the German UI says what it means |
| **Unit leadership** (Staffel- and SK-Leitung, Bereichsleitung, Organisationsleitung) | Scoped visibility that matches the real command structure, and delegated administration that does not require the maintainer |
| **Bank officers** | A ledger that cannot silently lose or double a booking, with an approval ladder and statements |
| **@greluc — maintainer, operator, data controller** | That one person can deploy it, diagnose it, restore it and answer a GDPR request with it |
| **Data subjects** (including former members) | Export and erasure that actually reach the free-text surfaces no foreign key points at |
| **The desktop extractor** and the Android app | A stable, restricted contract that does not break under them |

## 1.4 How to read this alongside the rest

arc42 asks *what the architecture is*. This repository already answers *what a feature must do*
(specs), *why a decision was taken* (ADRs) and *what the running system is actually doing right
now* (the knowledge base). Those three are the authorities. Where this folder and one of them
disagree, **they are right and this folder is stale** — fix it in the same session, the way
`CLAUDE.md` requires of everything else.
