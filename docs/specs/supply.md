> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-13.
> **Owner area:** SUP · **Related ADRs:** [ADR-0252](../adr/0252-stockcommands-gains-the-pool-and-donation-commands.md),
> [ADR-0253](../adr/0253-material-supply-and-purchasing-are-two-modules.md)

# Bedarf & Spenden (supply)

## Context & goal

The Bereich Profit's concept „Handelsbeauftragte" asks the tool to record what a unit needs and what
members donate, so the pilot's abort criterion — falling willingness to donate — can be measured. This
spec governs the first half of [`SUPPLY_AND_PURCHASING_PLAN.md`](../archive/SUPPLY_AND_PURCHASING_PLAN.md): the
module `supply` (rank 11 of the domain map) with the needs of the raising units and the material
donations of the members. It handles no money; the second half, `purchasing`, builds on it and lives in
[`purchasing.md`](purchasing.md). Tracked in krt-profit/basetool#2573.

## Requirements

### REQ-SUP-001 — Feature switch

The supply surface is enabled by the runtime system setting `supply.enabled`, default off. While it is
off, every supply endpoint except `GET /api/v1/supply/state` and the admin endpoints answers `404`
(`SUPPLY_DISABLED`), and the sidebar shows no supply entry. An admin switches it on the page
`/admin/supply`; the change is audited (`SUPPLY_SETTINGS_CHANGED`).

**Acceptance**

- [x] With the switch off a need list answers 404 `SUPPLY_DISABLED`.
- [x] The state endpoint answers while the switch is off.

**Enforced by:** `SupplyNeedServiceTest`, `SupplySettingsServiceTest` · **Code:**
`supply/internal/SupplySettingsService`, `supply/web/SupplyStateController`, frontend
`SupplyFeatureState`

### REQ-SUP-002 — Unit settings

An admin marks any active org unit as `may_raise_needs` and / or `accepts_donations`. No unit is
hardcoded; a unit without a setting does neither. Changes are audited (`SUPPLY_UNIT_SETTING_CHANGED`)
and carry the setting's own version.

**Enforced by:** `SupplyUnitSettingServiceTest` · **Code:** `supply/internal/SupplyUnitSettingService`,
`supply/web/SupplyAdminController`

### REQ-SUP-003 — Raising a need

The lead or a Logistician of a unit with `may_raise_needs`, and an admin, raise a need: a material xor
a game item, an amount above zero (whole units for pieces and game items), a quality tier for a
material (the base tier when omitted), a priority and an optional job order of that unit. Any other
caller receives 403. The catalogue entry and the tier of a need are fixed; a different one is a new
need.

**Enforced by:** `SupplyNeedServiceTest` · **Code:** `supply/internal/SupplyNeedService`

### REQ-SUP-004 — Purchasable flag

A need is purchasable only when the raising unit sets `purchasable` with a reason code
(`HIGH_FARM_EFFORT`, `RARE_SOURCE`, `TIME_CRITICAL`, `OTHER`) and a maximum unit price above zero in
whole aUEC. Without the flag no deal may reference the need (REQ-PUR-010).

**Enforced by:** `SupplyNeedServiceTest` · **Code:** `supply/internal/SupplyNeedService`

### REQ-SUP-005 — Need lifecycle

A need moves `OPEN` → `PAUSED` ⇄ `OPEN` → `FULFILLED` | `WITHDRAWN`. It becomes `FULFILLED` in the
statement that adds the covering amount; `FULFILLED` and `WITHDRAWN` are terminal. The covered amount is
added by single `UPDATE` statements that leave the need's version alone (REQ-SUP-019).

**Enforced by:** `SupplyNeedServiceTest` · **Code:** `supply/internal/SupplyNeedService#credit`,
`supply/internal/SupplyNeedRepository`

### REQ-SUP-006 — Need visibility and redaction

Every member sees the `OPEN` needs; the managers of a unit, the governing leadership and an admin see
every state of the unit's needs. The maximum unit price, the purchased amount and the UEX reference
price are returned only to the raising unit's lead and Logisticians, the governing leadership (the
governing Bereich's Bereichsleiter, the Organisationsleitung, admin) and a trade delegate (through the
`NeedPriceAudience` SPI the purchasing module implements); for everyone else the properties are absent
from the response, not null-filled by the client.

**Enforced by:** `SupplyMapperTest` · **Code:** `supply/internal/SupplyMapper`,
`supply/internal/SupplyAccessPolicy`

### REQ-SUP-007 — Pledge

Any member pledges a donation of a material or a game item, a quality and an amount above zero to a
unit with `accepts_donations`, optionally credited to one of that unit's open needs and optionally from
one of the member's own Lager rows. A pledge credited to a need takes the unit and the catalogue entry
from the need and must satisfy its tier.

**Enforced by:** `SupplyDonationServiceTest` · **Code:** `supply/internal/SupplyDonationService#pledge`

### REQ-SUP-008 — Earmark

A pledge from a Lager row earmarks the pledged amount on the row in the same transaction
(REQ-INV-057, REQ-INV-058). The earmarked amount cannot be sold, discarded, transferred or pledged again
until the pledge is received, declined or withdrawn. The pledge takes the row's lot lock first. The
personal Lager row offers „Spenden" (a dialog for the receiving unit, the amount up to the free
remainder) and shows the pledged slice as a chip (REQ-INV-058).

**Enforced by:** `SupplyDonationServiceTest`, `InventoryPoolCommandsTest`, `InventorySupplyRenderTest`,
`SupplyPledgeReceiveE2eTest` · **Code:** `supply/internal/SupplyDonationService`,
`supply/internal/SupplyDonationEarmarks`, `inventory/api/StockCommands`, `static/js/supply/supply-lager.js`

### REQ-SUP-009 — Receive

The lead or a Logistician of the receiving unit, or an admin, confirms a pledge with a received amount
between zero and the pledged amount and a location. In the same transaction the received amount moves
into the unit's pool — from the earmarked row by `transferToPool`, otherwise by `bookIntoPool` — the
donation becomes `RECEIVED` or `PARTIALLY_RECEIVED`, and the credited need's progress includes it.

**Enforced by:** `SupplyDonationServiceTest` · **Code:** `supply/internal/SupplyDonationService#receive`

### REQ-SUP-010 — Decline and withdraw

The receiving unit declines a pledge with a reason code (`WRONG_ITEM`, `NOT_NEEDED`,
`QUALITY_TOO_LOW`, `DONOR_ABSENT`, `OTHER`); the donor withdraws an unreceived pledge. Both release the
earmark.

**Enforced by:** `SupplyDonationServiceTest` · **Code:** `supply/internal/SupplyDonationService`

### REQ-SUP-011 — Recorded donation

The lead or a Logistician of a receiving unit records a donation already received, for a named member or
without a donor, and books it into the pool in the same transaction. The page picks the donor from the
member search or leaves it empty.

**Enforced by:** `SupplyDonationServiceTest`, `SupplyPledgeReceiveE2eTest` · **Code:** `supply/internal/SupplyDonationService#record`

### REQ-SUP-012 — Donor visibility

A donation has no anonymous mode: the receiving unit has to know in the game who hands the goods over.
The donor's name is returned to the donor, the lead and the Logisticians of the receiving unit, the
governing leadership and an admin, and to nobody else. The audit row names the donor as its target.

**Enforced by:** `SupplyMapperTest` · **Code:** `supply/internal/SupplyMapper`

### REQ-SUP-013 — Statistics

Per month and receiving unit the module reports the number of received donations, the distinct donors
and the received amount per material or item. A member sees their own totals; the managers of a unit
see their unit; the governing leadership sees every unit and the per-donor list.

**Enforced by:** `SupplyStatsServiceTest` · **Code:** `supply/internal/SupplyStatsService`

### REQ-SUP-014 — No free text

A donation and a need carry no free text. Every text column is a code, so no person can be named in
them, and the person search lists them as exempt.

**Enforced by:** `PersonSearchCoverageTest` · **Code:** `supply/internal/SupplyMentionSource`

### REQ-SUP-015 — Transfer notice

A donation that changes a Lager row's owner produces the donation notices (REQ-SUP-016) and neither the
owner-change notices of REQ-INV-055 nor the booked-out-by-other notice of REQ-INV-056:
`StockCommands#transferToPool` publishes no event.

**Enforced by:** `InventoryPoolCommandsTest` · **Code:** `inventory/internal/InventoryStockCommands`

### REQ-SUP-016 — Notifications

A pledge tells the receiving unit's lead and Logisticians (`SUPPLY_DONATION_PLEDGED`, cleared when the
pledge is received, declined or withdrawn); a received or partially received donation and a declined one
tell the donor (`SUPPLY_DONATION_RECEIVED`, `SUPPLY_DONATION_DECLINED`). After commit, actor excluded,
mutable (REQ-NOTIF-027), no free text. The pledge notice carries the donor's name.

**Enforced by:** `NotificationRuleEngineIntegrationTest`, `NotificationTypeMessageCoverageTest` ·
**Code:** `supply/api/events/SupplyNotices`, `V286__seed_supply_notification_rules.sql`

### REQ-SUP-017 — Audit

Every supply mutation records its `SUPPLY` audit event in the same transaction (REQ-AUDIT-001): the
event types `NEED_RAISED`, `NEED_UPDATED`, `NEED_PAUSED`, `NEED_RESUMED`, `NEED_WITHDRAWN`,
`NEED_FULFILLED`, `DONATION_PLEDGED`, `DONATION_RECEIVED`, `DONATION_DECLINED`, `DONATION_WITHDRAWN`,
`DONATION_RECORDED`, `SUPPLY_UNIT_SETTING_CHANGED` and `SUPPLY_SETTINGS_CHANGED`. Stock movements
additionally record their `INVENTORY` events.

**Enforced by:** `SupplyNeedServiceTest`, `SupplyDonationServiceTest` · **Code:**
`audit/api/AuditEventType`

### REQ-SUP-018 — Live update

Every supply write updates the page in place through `krtFetch` and propagates to peers on the topics
`supply-needs` (sections `needs`, `mine`) and `supply-donations:{unitId}` (section `donations`).

**Enforced by:** `LiveSyncTopicRegistryParityTest`, `LiveSyncSectionMapParityTest` · **Code:**
`static/js/supply/supply-common.js`, `kernel/livesync/LiveSyncTopicClass`

### REQ-SUP-019 — Locking

A need and a donation each carry their own `version`; the unit setting carries its own. What a donation
or a delivery covers is added by single statements that leave the need's version alone, so editing a
need does not conflict with a concurrent donation to it.

**Enforced by:** `SupplyNeedServiceTest` · **Code:** `supply/internal/SupplyNeed`

### REQ-SUP-020 — Privacy

Donations are part of the donor's data export. On erasure the donor is detached and the donation kept; a
pledge that was never received is withdrawn first. The account merge moves the donations to the
surviving account.

**Enforced by:** `GdprParticipantCoverageTest`, `PersonalDataExportCatalogueTest`,
`UserReassignmentOrderTest` · **Code:** `supply/internal/SupplyExportSections`,
`supply/internal/SupplyUserErasure`, `supply/internal/SupplyUserReassignment`

### REQ-SUP-021 — List tables

The supply tables keep their column headers visible below the sticky page header while the page
scrolls, and above 768 px a row's actions sit side by side on one line. The tables do not clip their
content, so a row's „⋯" menu is never cut off by the table; it opens upwards when it does not fit
below (REQ-UI-027).

**Enforced by:** manual check · **Code:** `static/css/pages/supply/supply.css`,
`static/js/kernel/krt-overflow-menu.js`, `static/js/kernel/common-handlers.js`

### REQ-SUP-022 — Needs and donations show a component's type, size, grade and class

A need and a donation for a game item show the Lager chips beside the item's name on the needs list,
„Meine Spenden" and „Eingehende Spenden", e.g. `FR-66 [SCHILD] [S1 · A] [MILITÄR]`.

- The chips follow the Lager rules (REQ-INV-061, REQ-INV-062): only a ship component gets the
  type, size/grade and class chips; every other item shows its category chip. A material need or
  donation renders as before.
- `NeedDto.gameItem` and `DonationDto.gameItem` are a `GameItemReferenceDto`
  (`GameItemReferenceDto.of`), read from the `game_item` row the list reads already fetch, so no
  read adds a query per row.
- The chips sit in the result fragments the live refresh fetches (REQ-SUP-018). The change is
  read-only; the `SUPPLY` audit trail is unchanged.

**Acceptance**

- [x] A need, the caller's own donation and an incoming donation for FR-66 show `[SCHILD] [S1 · A] [MILITÄR]`,
  also in a refreshed result fragment.
- [x] Material needs and donations render as before.

**Enforced by:** `SupplyItemChipsRenderTest`, `SupplyMapperTest` game-item cases · **Code:**
`SupplyMapper`, `supply/needs.html`, `supply/my-donations.html`, `supply/incoming-donations.html`,
`kernel/fragments/item-chips.html` · **Issues:** #2725

## Out of scope

Money, deals and partners ([`purchasing.md`](purchasing.md)); profit medals; any `/exchange/v1`
surface.

## Open questions

None; the decisions E-01 … E-12 and F-1 … F-4 are recorded in the plan.
