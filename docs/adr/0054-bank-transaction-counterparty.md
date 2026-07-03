# ADR-0054 — Bank: deposit/withdrawal counterparty (Einzahler / Empfänger + org unit)

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** @greluc
- **Related:** spec REQ-BANK-044 (amends the recording of REQ-BANK-004 bookings, REQ-BANK-012 audit,
  REQ-BANK-014/-015 PDFs, REQ-BANK-023 request confirmation, REQ-BANK-038 redaction) · builds on
  ADR-0039 (decoupled holder ledger) · concurrency & org-unit-blindness rules in `CLAUDE.md`,
  ADR-0011/0020

> **Amended (2026-07-03, owner decision):** the counterparty is **no longer redacted** on the
> member-facing org-unit surfaces. Decision point 6 below redacted the Einzahler/Empfänger alongside
> the holder; the owner has since decided members should see "von wem / an wen" on their own org
> unit's account, so **only the aUEC-custody Halter stays redacted** (REQ-BANK-038). The org-unit
> read-only history keeps `counterpartyHandle`/`counterpartyOrgUnitName`, and the member-facing
> redacted Kontoauszug **keeps** the "other side" column (dropping only the Halter column).
> Separately, that unified column is **renamed** "Gegenseite"/"Gegenpartei" → **"Quell-/Zielkonto"**
> in both account-detail tables and both PDFs, and the Begründung + Notiz move into an expandable
> per-booking sub-row (REQ-BANK-045).

## Context

A bank booking records only the **holder** — the bank custodian who physically received a deposit
or paid out a withdrawal (`bank_holder_posting`, ADR-0039). It does not record the *external party*
on the far side: who handed the money in (the **Einzahler**) or who received the payout (the
**Empfänger**), nor their org unit. The owner wants every deposit/withdrawal to capture this so the
account history, the Kontoauszug PDF and the admin audit log answer "von wem / an wen" the money
went. The counterparty is a genuinely new dimension, distinct from the holder (a custodian receives
a member's deposit; the depositing member is the counterparty).

Constraints: (1) the ledger is **append-only** (REQ-BANK-004) — a booking is written once, never
updated; (2) bank **authorization** must stay org-unit-blind (REQ-BANK-008, ArchUnit
`bankClassesMustNotConsultOrgUnitScope`); (3) the trail must **survive user/org-unit deletion**
(deletion-proof snapshots, like the audit actor handle); (4) org-unit membership is **multi**, so
"the org unit" of a member is ambiguous and cannot be auto-derived unambiguously.

## Decision

We will:

1. **Store the counterparty on the transaction header**, not on a leg (V196: `counterparty_user_id`
   FK `app_user` `ON DELETE SET NULL`, `counterparty_handle` snapshot, `counterparty_org_unit_id` FK
   `org_unit` `ON DELETE SET NULL`, `counterparty_org_unit_name` snapshot). A deposit/withdrawal has
   exactly one counterparty; the header is set once at insert, so the append-only contract holds and
   no `@Version` trap is introduced. Recorded only for `DEPOSIT`/`WITHDRAWAL`; transfers,
   holder-transfers, reversals and the wipe reset leave it null. A CHECK pins the snapshot/FK pairing
   and that an org unit only accompanies a user.

2. **Make the counterparty a tool user, not free text.** It is selected from the existing
   `GET /api/v1/users/lookup` picker (widened to admit `BANK_EMPLOYEE`). The handle is snapshotted
   from `User.getEffectiveName()`, mirroring the audit actor snapshot.

3. **Pick the org unit at booking, from the user's own direct memberships across all four kinds**
   (Staffel + SK + Bereich + OL). A dependent dropdown is filled from
   `GET /api/v1/users/{id}/memberships?allKinds=true` (widened to `BANK_EMPLOYEE`), auto-preselected
   when the user has exactly one membership, blank when none. The backend validates the chosen org
   unit is one of that user's memberships and snapshots its name through a new kind-safe
   `OrgUnitMembershipService.listDirectMembershipOptions` seam (resolving names via
   `OrgUnitRepository.findAllById`, no SQUADRON polymorphic-load trap) — distinct from the
   Staffel/SK-only `listOptionsForUser` the owner picker uses, so a Bereich/OL member's unit is
   selectable too. Both fields are **optional**.

4. **Keep authorization org-unit-blind.** The org unit is used only to *record* a snapshot, never to
   gate a booking. `BankLedgerService` depends on `OrgUnitMembershipService` for data-recording only;
   `BankSecurityService` and every bank gate are untouched, and the `OrgUnitBankAccessService` seam
   stays the sole `OwnerScopeService` bridge (ArchUnit-pinned).

5. **Reuse the audit's structured slot + detail.** `DEPOSIT_BOOKED` / `WITHDRAWAL_BOOKED` set the
   existing `target_user_id` to the counterparty and append its handle + org-unit name to the
   free-form detail (both system identifiers, not user free text). No new `BankAuditEventType`, so the
   admin viewer, its filters and the event-type i18n labels are unchanged.

6. **Surface "the other side" uniformly.** The account-detail history gains a Gegenpartei column;
   both PDFs gain one **Gegenseite** column (counterparty for deposit/withdrawal, counter-account for
   a transfer — which also fixes the statement PDF's prior omission of the transfer counter-account).
   The counterparty is player-identifying, so it is **redacted** alongside the holder on the
   member-facing surfaces (REQ-BANK-038).

7. **Derive the counterparty for confirmed requests** from the requester (`requestedBy` is
   `ON DELETE SET NULL`, so a non-null id always resolves); for a deposit request the requester *is*
   the depositor (REQ-BANK-042). The requester is not present to pick a unit, so their **deterministic
   primary** membership (the first by the top-down kind order — a member's name-sorted primary
   Staffel, or a leader's Bereich/OL) is recorded, null when they have none.

## Consequences

- **Easier:** the bank now answers "von wem / an wen" for every deposit/withdrawal in history, the
  Kontoauszug and the audit log, without weakening the append-only ledger or the org-unit-blind
  authorization. Transfers needed no data change — their from/to account + holder were already
  recorded; they only became clearer in the PDF.
- **Harder / trade-offs:** a 4-column header widening and a small `/lookup` + `/memberships` access
  widening to `BANK_EMPLOYEE` (low-sensitivity reads). For a confirmed request the org unit is the
  requester's deterministic *primary* membership rather than an explicitly chosen one (the requester
  is absent at confirmation), and the counterparty user is the requester rather than an arbitrary
  third party — both deliberate, since the request models exactly that requester.
- **Rejected alternatives:** (a) a free-text counterparty — dropped because the owner wants a
  resolvable user whose org unit can be looked up; (b) auto-recording all of a multi-membership
  user's org units — dropped as ambiguous and noisy; (c) reusing the holder concept — wrong, the
  custodian and the external party are distinct; (d) a mandatory counterparty — dropped to avoid
  breaking internal corrections and to keep the common quick-booking flow light.

