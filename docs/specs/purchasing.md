> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-14.
> **Owner area:** PUR · **Related ADRs:** [ADR-0253](../adr/0253-material-supply-and-purchasing-are-two-modules.md),
> [ADR-0254](../adr/0254-a-bank-booking-request-may-carry-an-opaque-purpose.md),
> [ADR-0255](../adr/0255-trade-delegates-are-an-in-app-appointment.md)

# Ankauf (purchasing)

## Context & goal

The second half of [`SUPPLY_AND_PURCHASING_PLAN.md`](../archive/SUPPLY_AND_PURCHASING_PLAN.md): the module
`purchasing` (rank 12 of the domain map) lets appointed members — the Handelsbeauftragten (HB) — buy
material from external partners for a need that a raising unit released for purchase. The money is the
Kartellbank's: every tranche is an ordinary bank withdrawal request to the HB, and the remainder returns
as a deposit request, both carrying an opaque purpose reference to the deal (REQ-BANK-061). The bank
never reads a deal; the deal learns of the request's end through the bank's observer (REQ-BANK-063).
Goods come into the raising unit's pool through `StockCommands`, accepted by the unit and not by the HB.
Tracked in krt-profit/basetool#2573.

## Requirements

### REQ-PUR-001 — Feature switch

The purchasing surface is enabled by the runtime system setting `purchasing.enabled`, default off. While
it is off, every purchasing endpoint except `GET /api/v1/purchasing/state` and the admin endpoints
answers `404` (`PURCHASING_DISABLED`), the sidebar shows no purchasing entry and no timed notice is
produced. The state answers with the caller's capabilities only while the switch is on.

**Enforced by:** `PurchasingSettingsServiceTest`, `PurchasingStateServiceTest` · **Code:**
`purchasing/internal/PurchasingSettingsService`, `purchasing/web/PurchasingStateController`, frontend
`PurchasingFeatureState`

### REQ-PUR-002 — Settings

An admin sets the governing Bereiche (any number; the Bereich with the department Profit, when one exists as the migration runs, until an admin changes the choice), the funding account (an active `CARTEL` or `SPECIAL` bank account),
the default monthly limit per HB, the budget cap and its period (`MONTH`, `QUARTER`, `YEAR`), the
settlement due days (1 … 90) and the quality tiers purchasing may buy (none until set). The settings row
carries its own version; every change is audited (`PURCHASING_SETTINGS_CHANGED`).

**Enforced by:** `PurchasingSettingsServiceTest` · **Code:** `purchasing/internal/PurchasingSettingsService`,
`purchasing/web/PurchasingAdminController`

### REQ-PUR-003 — Appointment

The Bereichsleiter and the Bereichskoordinatoren of a governing Bereich (never a Bereichsoperator), the
members of the Organisationsleitung and an admin appoint a member as HB with an optional end date and an optional personal monthly limit, and revoke the
appointment. Only an active — not revoked, not expired — appointment confers the HB capability. A former
HB keeps read access to and the settlement duty for their running deals and may not create deals or
request money. The appointment and its revocation are audited and noticed to the member (N8, N9).

**Enforced by:** `PurchasingDelegateServiceTest`, `PurchasingAccessPolicyTest` · **Code:**
`purchasing/internal/PurchasingDelegateService`, `purchasing/internal/PurchasingAccessPolicy`

### REQ-PUR-004 — Partners

An HB, the governing leadership and an admin create, edit, block and unblock partners of kind `ORG` or
`INDIVIDUAL` with a name or handle, a channel and the way the contact came about. A blocked partner takes
no new deal or tranche. The history of a partner — deals and deals settled without loss — is computed,
never stored.

**Enforced by:** `PurchasingPartnerServiceTest` · **Code:** `purchasing/internal/PurchasingPartnerService`

### REQ-PUR-005 — External only

A deal's counterparty is not a member: an `INDIVIDUAL` partner whose handle equals the user name, display
name or RSI handle of any account, case-insensitively, is refused at deal creation and at every tranche
with `PURCHASING_COUNTERPARTY_IS_MEMBER`.

**Enforced by:** `PurchasingDealServiceTest`, `PurchasingTrancheServiceTest` · **Code:**
`purchasing/internal/PurchasingDealService#requireExternal`

### REQ-PUR-006 — Partner erasure

An admin erases an individual partner's handle and notes; the deals stay and show the placeholder `—`.
The erased partner is blocked.

**Enforced by:** `PurchasingPartnerServiceTest` · **Code:** `purchasing/internal/PurchasingPartnerService#erase`

### REQ-PUR-007 — Deal

An active HB creates a draft deal for exactly one need: a partner, the amount (whole units for pieces and
game items), the unit price in whole aUEC, the payment model (`ADVANCE`, `ON_DELIVERY`, `PARTIAL`), an
escort wish and an optional Einsatz. The catalogue entry and the quality tier come from the need and
cannot differ. The draft may be edited and cancelled by its HB; the deal is never deleted
(REQ-PUR-022). Every change is audited and carries the deal's own version.

**Enforced by:** `PurchasingDealServiceTest` · **Code:** `purchasing/internal/PurchasingDealService`

### REQ-PUR-008 — Price ceiling

The unit price may not exceed the need's maximum unit price (`PURCHASING_PRICE_ABOVE_MAXIMUM`). The UEX
reference price is shown beside it, without a gate.

**Enforced by:** `PurchasingDealServiceTest` · **Code:** `purchasing/internal/PurchasingDealService#validate`

### REQ-PUR-009 — Need remainder

The amount of a deal may not exceed what the need still asks for after the undelivered part of the
other deals that hold the need (`SUBMITTED`, `FUNDED`, `IN_DELIVERY`) is set aside
(`PURCHASING_AMOUNT_EXCEEDS_NEED`).

**Enforced by:** `PurchasingDealServiceTest` · **Code:** `purchasing/internal/PurchasingDealService#validate`

### REQ-PUR-010 — Need state

A deal is created and funded only for an `OPEN`, purchasable need whose quality tier purchasing may buy,
and a tranche only while purchasing is neither paused for the whole organisation nor for the need's unit.

**Enforced by:** `PurchasingDealServiceTest`, `PurchasingTrancheServiceTest` · **Code:**
`purchasing/internal/PurchasingDealService`, `purchasing/internal/PurchasingPauseService#requireNotPaused`

### REQ-PUR-011 — Risk class

The risk class is the smallest class of the table whose maximum total covers the deal's total; the HB may
raise it, never lower it. The class's maximum total is enforced at creation and at every funding, its
maximum advance share on every `ADVANCE` tranche, and a class that requires a known partner refuses a
partner without a deal settled without loss. The table (`A`, `B`, `C`) is edited by an admin and audited
(`RISK_CLASSES_CHANGED`); each class carries its own version.

**Enforced by:** `PurchasingDealServiceTest`, `PurchasingTrancheServiceTest`,
`PurchasingSettingsServiceTest` · **Code:** `purchasing/internal/PurchasingDealService#classFor`

### REQ-PUR-012 — Tranche request

Each tranche is a bank `WITHDRAWAL` request from the funding account to the HB, raised through
`PurposeBoundRequests` with the purpose `PURCHASING_DEAL` and the deal's id and a generated German
justification (deal number, entry, amount — no partner name). The first tranche submits the deal. The
request then follows the bank's approval ladder and queue unchanged; the HB, being the requester, can
neither grant nor confirm it (REQ-BANK-062). A tranche's kind (`ADVANCE`, `ON_DELIVERY`, `FINAL`) must
fit the deal's payment model and the sum of live tranches may not exceed the deal's total
(`PURCHASING_TRANCHE_EXCEEDS_TOTAL`).

**Enforced by:** `PurchasingTrancheServiceTest` · **Code:** `purchasing/internal/PurchasingTrancheService#request`

### REQ-PUR-013 — Limits

A tranche is refused when the HB's requested-and-not-refused money in the current calendar month plus the
tranche exceeds the HB's limit (personal, else the default; `PURCHASING_LIMIT_EXCEEDED`) or the
organisation's money in the budget period plus the tranche exceeds the budget cap
(`PURCHASING_BUDGET_EXCEEDED`).

**Enforced by:** `PurchasingTrancheServiceTest` · **Code:** `purchasing/internal/PurchasingTrancheService#requireLimits`

### REQ-PUR-014 — Bank state mirror

When the bank confirms, rejects or cancels a purpose-bound request, the tranche and the deal change in
the bank's transaction: a confirmed tranche funds a `SUBMITTED` deal; a refused or cancelled tranche
returns a `SUBMITTED` deal without a live tranche to `DRAFT`; the confirmed deposit of the remainder
settles the deal; a refused deposit lets the HB record the settlement again.

**Enforced by:** `PurchasingRequestObserverTest`, `PurchasingTrancheServiceTest`,
`PurchasingSettlementServiceTest` · **Code:** `purchasing/internal/PurchasingRequestObserver`

### REQ-PUR-015 — Deliveries

The deal's HB records deliveries of a `FUNDED` or `IN_DELIVERY` deal; each is at most the deal's
undelivered amount (`PURCHASING_DELIVERY_EXCEEDS_UNDELIVERED`). The first moves the deal to
`IN_DELIVERY`; the raising unit's lead and Logisticians are noticed (N5).

**Enforced by:** `PurchasingDeliveryServiceTest` · **Code:** `purchasing/internal/PurchasingDeliveryService#record`

### REQ-PUR-016 — Acceptance

The lead or a Logistician of the need's raising unit, or an admin, decides a delivery with an accepted
amount, a rejected amount (with a reason code) and the measured quality; accepted plus rejected equal the
delivery. The accepted amount must meet the need's tier (`PURCHASING_QUALITY_BELOW_TIER`). In the same
transaction it is booked into the unit's pool through `StockCommands.bookIntoPool` (reason `PURCHASE`) at
the location the acceptor names and credited to the need. The HB never accepts their own delivery
(`PURCHASING_SELF_ACCEPTANCE`) and never writes stock. When the accepted amount covers the deal the
delivery closes by itself, otherwise the HB closes it; a close with nothing accepted fails the deal.

**Enforced by:** `PurchasingDeliveryServiceTest` · **Code:** `purchasing/internal/PurchasingDeliveryService#accept`

### REQ-PUR-017 — Settlement

After `DELIVERED` or `FAILED` the HB records the settlement: the money paid to the counterparty and the
loss with a reason code (`FRAUD`, `NON_DELIVERY`, `QUALITY`, `OTHER`). The money received is the sum of
the confirmed tranches; the paid money may not exceed it, the loss may not exceed the paid money, and a
failed deal's loss equals the paid money (`PURCHASING_SETTLEMENT_INVALID`). A positive remainder
returns as a purpose-bound `DEPOSIT` request into the funding account; the deal becomes `SETTLED` when it
is confirmed, or at once when nothing is left. The settlement carries its own version and may be recorded
again while its remainder is not back.

**Enforced by:** `PurchasingSettlementServiceTest` · **Code:** `purchasing/internal/PurchasingSettlementService`

### REQ-PUR-018 — Overdue settlement

A deal whose settlement is not recorded within the configured days after its delivery closed is overdue:
marked on every deal list and in the report, counted by the gauge
`basetool_purchasing_settlements_overdue`, and announced once per deal to the HB, the governing
leadership and the bank management (N7) by a `TimedNoticeProducer` that marks the deal with an atomic
update that leaves its version alone.

**Enforced by:** `SettlementOverdueNoticeProducerTest`, `PurchasingDealAssemblerTest` · **Code:**
`purchasing/internal/SettlementOverdueNoticeProducer`

### REQ-PUR-019 — Pause

The raising unit's lead, the governing leadership and an admin pause and resume purchasing for the whole
organisation or for one raising unit with a reason code. While paused no tranche is requested (so no
deal is submitted); deliveries, acceptance and settlement go on. A pause is audited and noticed (N6).

**Enforced by:** `PurchasingPauseServiceTest`, `PurchasingTrancheServiceTest` · **Code:**
`purchasing/internal/PurchasingPauseService`

### REQ-PUR-020 — Need withdrawn

When a need is withdrawn, its `DRAFT` and `SUBMITTED` deals are cancelled and their open tranche requests
cancelled in the bank in the same transaction; deals with confirmed money run on to their settlement.

**Enforced by:** `PurchasingNeedObserverTest`, `PurchasingDealServiceTest` · **Code:**
`purchasing/internal/PurchasingNeedObserver#onWithdrawn`

### REQ-PUR-021 — Visibility

A deal is readable by its HB (active or former), the governing leadership, bank staff, the raising unit's
lead and Logisticians and an admin; nobody else. The partner notes are returned to the HBs and the
governing leadership only, never to bank staff or the raising unit. The maximum price and the purchase
progress of a need are readable by active HBs through `NeedPriceAudience`.

**Enforced by:** `PurchasingAccessPolicyTest`, `PurchasingDealAssemblerTest` · **Code:**
`purchasing/internal/PurchasingAccessPolicy`, `purchasing/internal/PurchasingDealAssembler`

### REQ-PUR-022 — No deletion

Deals, tranches and deliveries cannot be deleted. `CANCELLED` is reachable only from `DRAFT` and
`SUBMITTED`, that is before any confirmed money.

**Enforced by:** `PurchasingDealServiceTest` · **Code:** `purchasing/internal/PurchasingDealService#cancel`

### REQ-PUR-023 — Report

An active HB, the governing leadership, bank staff, the leads and Logisticians of a raising unit and an
admin read the purchasing report for a period of at most 36 months: per month, per material or item and
per need — deals, amount bought, money funded, returned and spent, loss, overdue settlements, average
unit price against the UEX reference. It is also a CSV file with one line per deal and a forum-safe text
block. No figure, line or text names a member or a partner. A caller sees the deals they may read.

**Enforced by:** `PurchasingReportServiceTest` · **Code:** `purchasing/internal/PurchasingReportService`,
`purchasing/web/PurchasingReportController`

### REQ-PUR-024 — Notifications

The notices N4 … N9 of the plan, delivered after commit with the actor excluded: need released (N4), a
delivery recorded and decided (N5), pause and resume (N6), settlement overdue (N7), appointment and
revocation (N8, N9). N5 … N9 cannot be muted. Notices carry names, numbers and codes — never a partner,
a price or a note.

**Enforced by:** `PurchasingNoticesTest`, `NotificationEventTypeTest` · **Code:**
`purchasing/api/events/PurchasingNotices`

### REQ-PUR-025 — Audit

Every purchasing mutation records its `PURCHASING` audit event in the same transaction
(REQ-AUDIT-001). The details carry ids, enums, amounts and quality — never a partner name, handle or
note. Money movements stay in the bank's own trail.

**Enforced by:** `PurchasingDealServiceTest`, `AuditReportServiceTest` · **Code:** all purchasing services

### REQ-PUR-026 — Locking

The deal header, each tranche, each delivery, the settlement, the settings row and each risk class carry
their own version. Tranche, delivery, acceptance, close and settlement take the deal's row lock and do
not echo the header's version, so recording a delivery never conflicts with a tranche confirmation.

**Enforced by:** `PurchasingDeliveryServiceTest`, `PurchasingTrancheServiceTest` · **Code:**
`purchasing/internal/PurchasingDealService#lock`

### REQ-PUR-027 — Live update

Every purchasing write updates the page in place and propagates to peers on the live-sync topic
`purchasing/deals` without a reload.

**Enforced by:** `LiveSyncTopicRegistryParityTest`, `LiveSyncSectionMapParityTest` · **Code:**
`static/js/purchasing/purchasing-common.js`, `kernel/livesync/LiveSyncTopicClass`

### REQ-PUR-028 — Privacy

Deals are part of the HB's data export. On erasure the HB is detached by the database and the deals kept; the account
merge moves the deals, the appointments and the partner authorship to the surviving account. External
individual partners are a category of data subject of their own: their handle and notes are erasable by
an admin (REQ-PUR-006).

**Enforced by:** `GdprParticipantCoverageTest`, `PersonalDataExportCatalogueTest`,
`UserReassignmentOrderTest` · **Code:** `purchasing/internal/PurchasingExportSections`,
`purchasing/internal/PurchasingUserReassignment`; the foreign keys to the account are `ON DELETE SET NULL`

## Out of scope

Profit medals; Android screens beyond the rendering of the notices; any `/exchange/v1` surface; a
second funding account; automatic price discovery.

## Open questions

None; the decisions E-01 … E-12 and F-1 … F-4 are recorded in the plan.
