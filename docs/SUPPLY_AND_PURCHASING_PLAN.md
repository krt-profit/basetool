> **Doc type:** Living plan — written 2026-10-07 for the owner's review and brought up to the code of
> `main` at `0da1df779` (v1.16.1) on 2026-10-13. The open decisions of §2 were answered by the owner
> on 2026-10-07 (E-01 … E-12, F-1 … F-4) and still stand. **Status 2026-10-14:** all six steps are
> built: B1 (four-eyes, ADR-0251), S1 (`StockCommands`, ADR-0252), S2 (`supply`, ADR-0253,
> [`supply.md`](specs/supply.md)), S3 … S6 (`purchasing`, ADR-0254, ADR-0255,
> [`purchasing.md`](specs/purchasing.md)). The requirements of §9.1 and §9.2 live in the two specs and
> bind. §8.1 records where S2 deviates from this text, §8.2 where S3 … S6 do.
> **Owner area:** BE · FE · BANK · INV · NOTIF · AUDIT · OBS · MOD
> **Related ADRs:** ADR-0020, ADR-0028, ADR-0096, ADR-0109, ADR-0123, ADR-0229, ADR-0231 …
> ADR-0236, ADR-0240 … ADR-0242, ADR-0244, ADR-0245, ADR-0250 · **Related specs:**
> [`module-boundaries.md`](specs/module-boundaries.md) (REQ-MOD-001 … 006) ·
> **Related plans:** [domain modularisation](archive/DOMAIN_MODULARISATION_PLAN.md) (done and frozen
> 2026-10-13; its rules live on in the spec above and in ADR-0231 … ADR-0239) ·
> **Related issues:** krt-profit/basetool#2409, #2413, #2414 (all three built: REQ-INV-055, ADR-0244,
> ADR-0245)
> **Source concept:** „Konzept Handelsbeauftragte" (Bereich Profit, draft of 2026-10-05, Google Doc
> outside the repository)

# Material supply and purchasing — donations, demand and trade deals

## 1 What this is

The Bereich Profit proposes to relieve the crafting Spezialkommando ARGON: material ARGON needs is
no longer only farmed or donated by members, but also **bought from external sources** by a small,
named group of **Handelsbeauftragte** (HB), paid from the KRT account. The concept asks the Basetool
to record every purchase („Warenauftrag" with demand, material, quality, amount, price,
counterparty, status), the goods' arrival in the Lager, the money through the Kartellbank, and the
transparency report. It also names a condition the tool must make measurable: **the purchases must
not lower the members' willingness to donate**, and the pilot ends at once if they do. So
donations have to be recorded too — today they are not recorded anywhere.

This plan turns that into two features and one architecture:

| Feature | What a member sees | Module |
| --- | --- | --- |
| **Bedarf & Spenden** | ARGON publishes what it needs; any member pledges a material or item donation against it (or freely); ARGON confirms receipt, the stock moves into ARGON's pool, the donor is thanked and counted | `supply` (new, rank 10) |
| **Ankauf** (trade deals) | An HB records an offer from an external org or person against an ARGON need, requests the money through the bank, records deliveries; ARGON accepts the quality and books the goods in; the HB settles and returns the rest | `purchasing` (new, rank 12) |

Both are born as modules of the modular monolith ([`module-boundaries.md`](specs/module-boundaries.md),
ADR-0231): own package, `api` / `internal` / `web`, declared with `@ApplicationModule` and in the
domain map (REQ-MOD-001, REQ-MOD-006), no module-coupling violation (the baseline is empty, so the
first one fails the build), interaction only through module APIs, observer SPIs and after-commit
events (ADR-0232).

### 1.1 Facts the design stands on (checked against `main` at `0da1df779`, 2026-10-13)

- **ARGON** exists in no code, migration or document; it is an ordinary org unit of kind
  `SPECIAL_COMMAND` (only a frontend test fixture names it). The design therefore never hardcodes it:
  units are *configured* as demand-raising / donation-receiving units.
- **The bank already has the money mechanics.** A `SPECIAL` account type (Sonderkonto) exists, the
  KRT account (`CARTEL`) has the approval ladder T1 / T2 (bank employee → Bankleitung → OL,
  REQ-BANK-047), debits from `CARTEL` / `SPECIAL` require a justification (REQ-BANK-045), accounts
  carry a balance target, and any member may request a deposit into any active account. A member may
  correct their own still-pending request (REQ-BANK-056). What it lacks, re-checked on 2026-10-13:
  **no structured purpose** on a booking request (only free-text note / justification / staff note),
  and **no four-eyes check** — `BankBookingRequestService#confirm` and `#applyOwnerApprovalWithinTransaction`
  never compare the confirmer with `requestedBy`; „nobody approves their own request" holds only
  structurally (ADR-0123). `bank.api` publishes amounts, the request type, the problem codes and
  events only — no command and no observer.
- **The Lager has no provenance.** `InventoryItem` has no source/donor field and no history table;
  movements are traced only by audit events. The entity now lies in `inventory.internal` and other
  modules see a row only as the read-only `inventory.api.StockRow`.
- **`inventory.api.StockCommands` exists, but not for this.** It carries the lot-lock protocol
  (`lockLots`, `lockLotRows`, ADR-0229), `bookIn` (a *personal* row without an org unit), `bookOutRow`,
  `markStolen`, the job-order earmark release and take-from-earmark calls and the production and
  refinery book-ins. It has **no book-in into an org-unit pool, no transfer into a pool and no
  donation earmark**; those are what this work adds (E-11). A *pool* is not a table: it is a shared row
  (`personal = false`) held by a member and stamped with `owning_org_unit_id`. An earmark is an
  allocation row beside the row — `InventoryJobOrderAllocation` and `InventoryMissionAllocation`; the
  Lager learns what it shows of their targets through the `inventory.api.EarmarkTargets` read SPIs,
  which the target's module implements.
- **A member-to-member transfer already tells the receiver** (#2409, REQ-INV-055):
  `InventoryCheckoutService` publishes `InventoryTransferredToUserEvent` / `…FromUserEvent` after commit
  on the single and bulk `TRANSFER` paths, and stock booked out by somebody else tells its owner
  (`INVENTORY_BOOKED_OUT_BY_OTHER`, REQ-INV-056). A donation moves a donor's row at the hand of the
  receiving unit, so it would trip both unless it is a command of its own (§4.2).
- **Job orders are scoped by the responsible org unit** (`responsible_org_unit_id` drives
  visibility, ADR/REQ-ORDERS). HB are explicitly *not* an org unit („keine SK"), so a purchase cannot
  be a job order without breaking that scope rule. The wiki's „Warenauftrag" is the job order; the
  concept's „Warenauftrag" for a purchase is therefore built as its own aggregate, the
  **Handelsvorgang**, linked to the ARGON need it serves.
- **Quality** is the tier catalogue (ADR-0241, `NONE` „Keine" and `GOOD` „Gut (650+)"); the
  concept's „Qualitätsklasse Masse" is the base tier `NONE`.
- **UEX prices** are in the database: `Material.priceBuy/priceSell` (averages), `MaterialPrice`
  per terminal, `GameItemPrice` per item and terminal — the price reference the concept asks for.
- **Notifications**: the engine extension this plan waited for is **built** (#2414 Part A, ADR-0244,
  ADR-0245, REQ-NOTIF-018, -024 … -028). There are now ten selector kinds, among them
  `EVENT_RECIPIENTS` (the set of users an event lists) and `OrgRelativeRole.UNIT_LEADERSHIP`; an
  event can supersede a notice for named recipients only; a `TimedNoticeProducer` SPI lets a module
  produce time-based notices from the notification module's single advisory-locked task; members
  mute types (`notification_mute`, a type declares whether it is mutable); a render parameter ending in
  `Code` renders a localized word (REQ-NOTIF-028).
- **Existing „Spende"**: only the payout preference `DONATE` of an operation payout (aUEC donated
  from a mission share). Material donations are new; the UI calls them „Materialspende" to keep the
  two apart.
- **Naming**: „Handel" is already the navigation group of prices and the trade-margin calculator.
  The new area is therefore „Beschaffung", the module names are `supply` and `purchasing`.
- **The modularisation is finished** (frozen 2026-10-13): the backend has 26 domain-map modules
  (the transitional `privacy` and the composition root `app` among them), every class lies in its module package, the module baseline is empty, every
  domain owns an access policy and the table-ownership map is gated. Two things were *not* done and
  matter here: bank and exchange stay in the `backend` Gradle project (no extraction, owner decision
  2026-10-12), and the REST API cut waits for the owner's release decision, so the old roots stay and
  the new modules simply use the target shape. The admin fence `/api/v1/*/admin/**` → `ADMIN` is
  already in `SecurityConfig`.
- **Numbers on `main`** (all provisional until claimed at push time by re-grepping `origin/main` and
  the open PRs): highest ADR **0250**, highest Flyway **`V283`**, highest `REQ-BANK` **060** (057 …
  060 are `bank-notifications.md`), `REQ-INV` **056** (055 is the transfer notice, 056 the notice to
  an owner whose stock somebody else booked out), `REQ-NOTIF` 029, `REQ-AUDIT` 007, `REQ-OBS` 021, `REQ-SEC`
  084, `REQ-DATA` 023, `REQ-API` 023, `REQ-FE` 035, `REQ-MOD` 006. No `REQ-SUP` / `REQ-PUR` prefix is
  in use.

## 2 Decisions (owner, 2026-10-07)

Every question was answered by the owner on 2026-10-07. Ten of E-01 … E-12 follow the recommendation; E-03
widens the four-eyes rule to the whole bank, which raised the follow-ups F-1 … F-4, and E-09 drops
the farm-time figure. The draft requirements of §9 are written for these answers.

| # | Question | Decision | Rejected and why |
| --- | --- | --- | --- |
| E-01 | How is a Handelsbeauftragter represented? | **An in-app appointment** (`purchasing_delegate`): appointed and revoked by the Bereichsleitung of the governing Bereich (plus OL, admin), with an optional personal monthly limit and an end date; audited; the capability is derived from the active appointment, like the Logistician flag | A Keycloak role `TRADE_DELEGATE`: realm write per appointment (production write gate), no limit or end date, admins instead of the Bereichsleitung decide — contradicts „Benennung durch die Bereichsleitung Profit" |
| E-02 | One module or two? | **Two**: `supply` (needs + donations, no money) and `purchasing` (deals, partners, delegates, bank link) | One `procurement` module: aborting the pilot (concept §11) would then mean switching off part of a module that donations also live in |
| E-03 | Four-eyes rule | **For every bank booking request**, not only purchasing ones: the requester of a request may neither grant its owner approval nor confirm it; details F-1 … F-4 | Purchasing requests only (the recommendation): the owner chose the bank-wide rule — a behaviour change of the whole bank with its own requirement (REQ-BANK-062) and ADR |
| F-1 | May a requester reject their own request? | **Yes.** Rejecting moves no money and is in effect the cancel the requester may do anyway; four-eyes covers granting and confirming only | Forbid it: symmetry without a risk it removes |
| F-2 | Direct bookings (no request) | **Unchanged.** A bank employee's direct deposit, withdrawal and transfer — including a payout to themselves — stay as today; four-eyes applies to requests only | Turn a self-payout into a request (the recommendation); turn every direct booking into a request (abolishes direct booking, which the design keeps for what nobody files a request for) |
| F-3 | A responsible holder's own request | **The ADR-0123 exemption stays**: it needs no owner approval; the second person is the bank employee who confirms, and the holder may not confirm it themselves | A higher approver above the limit: a new approval chain |
| F-4 | Does four-eyes bind bank management and admin? | **Bank management yes, admin no.** An admin may grant and confirm their own request (emergency path); every other role needs a second person | Binding admin too (the recommendation): no emergency path |
| E-04 | Which account pays? | **Configurable funding account**: the KRT account (`CARTEL`) by default; the concept's variant 6.3 is a `SPECIAL` account „Handelskasse" selected in the purchasing settings, filled by the Staffeln through ordinary deposit requests and its existing **balance target** as the „Grund-Handelskapital" | KRT only or Sonderkonto only: either drops one of the concept's variants; a new account type is unnecessary |
| E-05 | What is stored about counterparties? | **Orgs**: name, tag, channel kind, conditions, status. **Individuals**: in-game handle and a reputation note only, visible to HB, governing Bereichsleitung, bank management and admin; erasable on request; no Discord ids, no real names | Full contact persons: personal data of non-members with no documented legal basis |
| E-06 | Who sees an ARGON need? | **Every member** (it is the call for donations), with the maximum price and the purchase progress redacted for everyone outside the HB, the raising unit's leadership, the governing Bereichsleitung and admin | Only HB, ARGON and the Bereichsleitung: donors would not see what is needed |
| E-07 | How is a donation recorded? | **Pledge and confirm**: the donor pledges (from „Mein Lager" or as a free pledge); a Logistician or the lead of the receiving unit confirms receipt; on confirmation the stock moves (or is booked in) into the unit's pool. A Logistician may also record a received donation directly | Logistician-only recording: no donor action, no proof for the donor |
| E-08 | Profit medals | **Out of scope**; the donation statistics (§4.5) are the data a later medal feature reads | Build medals now: the award rules are undecided |
| E-09 | „Eingesparte Farmzeit" in the report | **Not recorded.** The tool keeps no farm-time estimate; the Bereichsleitung evaluates saved farm time outside the tool | An optional per-material estimate (the recommendation); a mandatory one |
| E-10 | Android app | **Web only** for the new screens; the app ships only the rendering of every new notification type (mandatory: it fails its build on an unworded type) | App screens now: doubles the effort while the pilot may still be aborted |
| E-11 | Stock writes | **Extend the existing `inventory.api.StockCommands`** with book-in into a pool, transfer to a pool and a donation earmark as part of this work, owned by inventory, `MANDATORY` (the interface exists since the modularisation; these methods are new) | Foreign `new InventoryItem()` in `supply` / `purchasing`: the APPSEC-01 class of defect — and no longer even possible, the entity is `inventory.internal` |
| E-12 | Governance before go-live | **A feature switch per module, off by default** (`supply.enabled`, `purchasing.enabled` as runtime system settings); `purchasing` is switched on only after the OL resolutions of concept §10 | Ship enabled: the KRT account's purpose is not yet extended by an OL resolution |

## 3 Roles and capabilities

| Concept role | In the tool | Derived from |
| --- | --- | --- |
| ARGON (Bedarfsträger) | **Raising unit**: an org unit with `may_raise_needs` in the supply settings; its SK lead / Staffelleiter, its Logisticians and admin raise, edit, pause and withdraw needs and accept deliveries | `supply_unit_setting` + existing membership flags |
| Donation receiver | **Receiving unit**: an org unit with `accepts_donations`; its lead and Logisticians confirm donations | same table |
| Handelsbeauftragte | **Active `purchasing_delegate` appointment** | E-01 |
| Bereichsleitung Profit | **Governing Bereich**: the Bereich set in the purchasing settings; its Bereichsleiter appoints HB, sets the budget cap, pauses and resumes purchasing, reads all deals and the report | `purchasing_setting.governing_area_id` + existing Bereich leadership |
| Bankleitung / Bankmitarbeiter | unchanged bank roles; approve and confirm the money requests along the existing ladder | `BANK_EMPLOYEE`, `BANK_MANAGEMENT`, grants |
| OL | above T2 on the KRT ladder (unchanged); may pause purchasing | existing |
| Nexus Sektion 2 | no tool role; a partner record names „Kontakt hergestellt über" as a free choice | — |
| Begleitschutz | optional link from a deal to an Einsatz; no new role | existing missions |
| Member | sees needs, pledges donations, sees own donations and own statistics | `KRT_MEMBER` |

### 3.1 Who may do what

| Function | Member | Raising / receiving unit lead or Logistician | HB | Governing Bereichsleitung | Bank staff | Admin |
| --- | :-: | :-: | :-: | :-: | :-: | :-: |
| See open needs (max price redacted) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| See a need's max price and purchase progress | ❌ | ✅ own unit | ✅ | ✅ | ❌ | ✅ |
| Raise / edit / withdraw a need, release it for purchase | ❌ | ✅ own unit | ❌ | ❌ | ❌ | ✅ |
| Pledge a donation, see own donations | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Confirm / decline a pledged donation, record a received one | ❌ | ✅ own unit | ❌ | ❌ | ❌ | ✅ |
| See a unit's donations with donor | ❌ | ✅ own unit | ❌ | ✅ | ❌ | ✅ |
| Maintain partners | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| Create and edit a deal, submit a money request, record deliveries, settle | ❌ | ❌ | ✅ own deals | ❌ | ❌ | ❌ |
| Read every deal | ❌ | ✅ deals for own unit's needs | ✅ | ✅ | ✅ purpose-bound requests only | ✅ |
| Accept / reject a delivery (quality) and book it in | ❌ | ✅ own unit | ❌ | ❌ | ❌ | ✅ |
| Approve / confirm the money | ❌ | ❌ | ❌ (never own) | per ladder (never own) | per ladder and grant (never own) | per ladder (own allowed, F-4) |
| Appoint / revoke HB, set limits and budget cap | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Pause / resume purchasing („sofort einstellen") | ❌ | ✅ raising unit lead | ❌ | ✅ | ❌ | ✅ |
| Settings (units, governing Bereich, funding account, risk classes, switches) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Report | ❌ | ✅ own unit | ✅ | ✅ | ✅ | ✅ |

An admin creates no deals: deals belong to appointed HB, and an admin who should trade is appointed.

## 4 Functional design

### 4.1 Need („Beschaffungsbedarf")

A need is raised by a raising unit: material **or** game item, amount (SCU or pieces, from the
catalogue's quantity type), quality tier (ADR-0241), priority, optional link to the unit's job
order it feeds, and two procurement fields: **`purchasable`** (ARGON's explicit choice to allow
buying — concept §7 „Zukauf gezielt") with a mandatory reason code (`HIGH_FARM_EFFORT`,
`RARE_SOURCE`, `TIME_CRITICAL`, `OTHER`) and a **maximum unit price** in aUEC, required when
`purchasable`. The UEX reference price is shown beside it.

States: `OPEN` → (`PAUSED` ⇄ `OPEN`) → `FULFILLED` | `WITHDRAWN`. Progress = confirmed donations
credited to the need + accepted purchase deliveries. A need reaches `FULFILLED` automatically when
progress ≥ amount. Withdrawing a need cancels its deals that hold no money yet; deals already funded
continue to settlement (§4.3).

### 4.2 Donation („Materialspende")

Two ways in (E-07):

1. **Pledge** — a member opens „Spenden" from a need or from a row of „Mein Lager" and pledges
   material/item, quality, amount, receiving unit (pre-filled from the need). If it came from a Lager
   row, the amount is **earmarked** on that row (no double pledge, no double sale) — a third
   allocation kind beside the existing job-order and mission allocations of a Lager row, owned by
   inventory, pointing at the pledge by id, and read back through a new `EarmarkTargets.Donations`
   SPI that supply implements (the pattern of `EarmarkTargets.Missions` / `JobOrders`). The
   receiving unit confirms receipt after the in-game handover:
   - from a Lager row: `StockCommands.transferToPool` moves the amount into the receiving unit's
     pool (a shared row held by the confirming member and stamped with the unit) at the location the
     receiver names;
   - free pledge: `StockCommands.bookIntoPool` books it in at the receiver's location.
   The receiver may confirm a smaller amount (partial) or decline with a reason code.
2. **Recorded** — a Logistician of the receiving unit records a donation already received, for a
   named donor (or without one), and books it in at once.

A donation is credited to at most one need. It never carries free text beyond an optional note
that appears nowhere outside the donation itself (not in notifications, audit details or metrics).
The receiving unit always sees the donor: it has to know in the game from whom the goods arrive.

Transfer-notice interplay (REQ-INV-055, REQ-INV-056): a donation transfer changes a row's owner. It
must produce the **donation** notices only — not the generic „Umbuchung" notices
(`InventoryTransferredTo/FromUserEvent`) and not „von jemand anderem ausgebucht"
(`INVENTORY_BOOKED_OUT_BY_OTHER`). `transferToPool` is therefore its own command that publishes
neither event, instead of a reason flag on the `TRANSFER` book-out path; the donor hears about it
through N2 (§6). The audit rows still say what moved (§7.1).

### 4.3 Trade deal („Handelsvorgang")

Created by an HB against an `OPEN`, `purchasable` need:

| Part | Content | Locked separately (own version) |
| --- | --- | --- |
| Header | need, partner (org or individual), material/item, quality tier, amount, unit price, total, payment model, risk class, escort wish (+ optional Einsatz) | `deal.version` |
| Tranches | 1..n money requests: kind `ADVANCE` (Vorkasse) / `ON_DELIVERY` (Zug um Zug) / `FINAL`, amount, the linked bank request id, state | per tranche row |
| Deliveries | 1..n: amount delivered, location, then ARGON's acceptance: accepted amount, measured quality, rejected amount with reason code | per delivery row |
| Settlement | money received, money paid to the counterparty, remainder returned (the deposit request), loss with reason code (`FRAUD`, `NON_DELIVERY`, `QUALITY`, `OTHER`) | `deal.settlement_version` |

States: `DRAFT` → `SUBMITTED` (first tranche requested) → `FUNDED` (a tranche confirmed by the
bank) → `IN_DELIVERY` → `DELIVERED` (all expected amount delivered or the HB closes delivery) →
`SETTLED`; side exits `CANCELLED` (before any money), `FAILED` (money out, goods not received — must
be settled with a loss). A deal is never deleted.

**Rules enforced at submit** (each a problem code, §5.6):

- the need is `OPEN`, `purchasable`, and purchasing is not paused (global or for the need's unit);
- unit price ≤ the need's maximum unit price; total within the risk class's maximum;
- the amount of all non-cancelled deals for the need does not exceed the need's open remainder;
- the counterparty is not a member: an individual's handle must not match a member handle (case-
  insensitive, the roster's handles) — „nur externe Quellen";
- the HB's money requested in the current calendar month plus this tranche ≤ the HB's limit (personal
  or default), and the governing Bereich's budget cap for the period is not exceeded;
- the **risk class** is computed, not chosen: from the total and the partner's history (number of
  settled deals without loss); the HB may raise it, never lower it. Per class the settings hold the
  maximum total, the maximum advance share (A = 0 %, B ≤ 50 %, C = 0 % with partial deliveries —
  placeholders, concept §5), whether a known partner is required, and whether escort is suggested.

**Money flow** (no new ledger, E-03/E-04): each tranche is an ordinary bank `WITHDRAWAL` request
from the funding account with the HB as recipient, raised through a new `bank.api` command that
stamps a **purpose reference** (`purpose_kind = PURCHASING_DEAL`, `purpose_ref = deal id`) and a
generated justification. It then travels the existing ladder and queue unchanged (owner approval
above T1/T2, bank confirmation). The settlement's remainder is a `DEPOSIT` request with the same
purpose reference. The bank never reads the deal (ADR-0020 stays: the reference is opaque to the
bank). The bank tells purchasing about confirm / reject / cancel of a purpose-bound request through
an **observer SPI owned by the bank** and implemented by purchasing, inside the bank's transaction
(ADR-0232: the deal's state must equal the request's state when the transaction commits).

**Delivery and acceptance**: the HB records a delivery after the in-game handover to the raising
unit; the raising unit's Logistician or lead accepts it (accepted amount, measured quality — must
meet the need's tier, otherwise partially or wholly rejected) and books the accepted amount into the
unit's pool through `StockCommands.bookIntoPool` at a location they name. The HB never writes stock.

**Settlement** is due within N days (setting, default 7) after `DELIVERED` or `FAILED`. Overdue
settlement is surfaced on the deal list, on the governing Bereichsleitung's view and by a notice
(§6, a `TimedNoticeProducer` of the extended engine, REQ-NOTIF-026).

### 4.4 Partners

`purchasing_partner`: kind `ORG` / `INDIVIDUAL`, name or handle, org tag, channel kind (`DISCORD`,
`RSI`, `UEX`, `OTHER`), conditions note, reputation note, „Kontakt hergestellt über" (free choice of
`DIPLOMACY`, `HB`, `OTHER`), status `ACTIVE` / `BLOCKED` (blocked: no new deal). History (deals,
volume, losses) is computed from deals, never stored. Individuals are erasable by admin on request
(E-05); erasure keeps the deals and replaces the handle with a placeholder.

### 4.5 Report and statistics

- **Purchasing report** (per month, per need, per material): number of deals, amount bought, aUEC
  spent net of returned remainders, average unit price vs the UEX reference, losses, overdue
  settlements; no farm-time figure (E-09). CSV and a forum-safe text block (as the bank-update
  skill does), no member names.
- **Donation statistics**: per month and per receiving unit — number of donations, distinct donors,
  amount per material; the trend line the concept's abort criterion needs („sinkt die
  Spendenbereitschaft"), shown **next to** the purchase volume. Per member: the member's own
  totals on their profile; the per-donor list only for the governing Bereichsleitung and admin
  (medals, E-08).
- The bank's monthly report (`bank-update` skill) can split the KRT account's withdrawals by
  purpose („davon Materialeinkauf") from the new purpose column; no names (concept §8).

### 4.6 Kill switch and pilot

- **Pause**: the raising unit's lead, the governing Bereichsleitung, the OL or admin set purchasing to
  `PAUSED` — globally or for one raising unit — with a reason code. While paused, no deal is
  submitted and no new tranche is requested; deliveries, acceptance and settlement of running deals
  continue, so no money is stranded. Resuming is the same people's action.
- **Pilot scope** is configuration, not code: the raising units, the HB appointed, the budget cap
  per period, the allowed quality tiers for purchasing (pilot: `NONE` only) and the risk-class
  table.

## 5 System architecture

### 5.1 Module placement

```text
rank 2   catalogue ─────────────┐ materials, items, quality tiers, UEX prices
rank 3   identity ──────────────┤ members, handles (counterparty check)
rank 4   orgunit ───────────────┤ units, Bereich leadership, membership flags
rank 5   scope                  │
rank 6   admin ─────────────────┤ SystemSettings (feature switches)
rank 8   inventory ─────────────┤ StockCommands (extended): bookIntoPool, transferToPool, donation earmark
rank 10  joborder ──────────────┤ read for the need's optional job-order link
rank 11  supply  ◄──────────────┘ needs, donations          (new)
rank 11  bank ───────────────── PurposeBoundRequests (new command), BookingRequestPurposeObserver (new SPI)
rank 12  purchasing ──► supply.api, inventory.api, bank.api, catalogue, identity, orgunit, admin  (new)
rank 1   audit, notification, livesync ◄── everyone (AuditRecorder, events, topics)
```

Domain map additions: `module supply 11`, `module purchasing 12`, `package supply`,
`package purchasing` (REQ-MOD-001); both declared in `package-info` with `@ApplicationModule`, an
`@NamedInterface("api")` on `api` and every package below it, and the `allowedDependencies` the map
derives (REQ-MOD-006); both enter `ModularityTest.DECLARED_MODULES` and the lists of every module that
may depend on them. No baseline entry — the baseline is empty (REQ-MOD-004).

**Why `supply` is rank 11, not 10.** The rank rule lets a module depend only on lower ranks (REQ-MOD-003).
The need's optional job-order link is validated against `joborder.api`, and `joborder` is rank 10, so
a rank-10 `supply` would need an `allow supply joborder` row. Rank 11 needs none and costs nothing:
`supply` does not depend on `bank`, and `purchasing` (12) sits above both. `inventory` (8) never
names `supply` — it reaches the pledge through the `EarmarkTargets.Donations` SPI that `supply`
implements.

`bank` does **not** depend on `purchasing` (the SPI inverts it). Bank stays inside the `backend`
Gradle project — the extraction was dropped on 2026-10-12 — so the SPI is a rank discipline now, not
a precondition for a build split.

### 5.2 Inside the modules

```text
backend.supply
├── api/       SupplyQueries (open needs, need progress, donation sums per unit/material/period),
│              NeedRef, events/ (NeedRaised…, DonationPledged…, DonationReceived…)
├── internal/  Need, Donation, SupplyUnitSetting, repositories, NeedService, DonationService,
│              SupplyAccessPolicy (bean supplyAccessPolicy) and the scope fragments beside the
│              repositories, mappers and REST DTOs, the EarmarkTargets.Donations implementation,
│              SupplyLiveSyncTopicAuthorizer, SupplyBusinessGauges, SupplyExportSections,
│              SupplyUserErasure
└── web/       NeedController, DonationController, SupplyAdminController

backend.purchasing
├── api/       events/ (DealSubmitted…, DeliveryRecorded…, DeliveryAccepted…, SettlementOverdue…,
│              PurchasingPaused…, DelegateAppointed…), PurchasingProblemCode
├── internal/  Deal, DealTranche, DealDelivery, Partner, Delegate, PurchasingSetting, RiskClass,
│              DealService, TrancheService, DeliveryService, SettlementService, PartnerService,
│              DelegateService, BankPurposeObserverImpl, PurchasingAccessPolicy
│              (bean purchasingAccessPolicy), SettlementOverdueNoticeProducer
│              (a TimedNoticeProducer), PurchasingLiveSyncTopicAuthorizer,
│              PurchasingBusinessGauges, PurchasingExportSections, PurchasingUserErasure, DTOs
└── web/       DealController, PartnerController, DelegateController, PurchasingAdminController
```

The layering is the one `module-boundaries.md` (REQ-MOD-006) records: `web` depends on `internal`,
never the reverse, and the REST DTOs live in `internal` beside the services until a module API
returns its own records. The participants mirror what the existing modules implement
(`BankExportSections`, `InventoryUserErasure`, `BankBusinessGauges`, `OrgUnitBankLiveSyncTopicAuthorizer`).

Changes in existing modules:

- `inventory.api.StockCommands` (exists; `InventoryStockCommands` implements it in `inventory.internal`)
  — new methods `bookIntoPool(material|item, quality, amount, location, owningOrgUnit, holder,
  reason)`, `transferToPool(sourceRowId, amount, targetOrgUnit, holder, location, reason)` and
  `earmarkForDonation(rowId, donationId, amount)` / `releaseDonationEarmark(…)`; `MANDATORY`, under
  the existing lot-lock protocol (`lockLots`, `lockLotRows`, ADR-0229), audits in the same
  transaction, returns the row ids. `reason` is a new closed enum `StockBookingReason` (`DONATION`,
  `PURCHASE`) that the audit details use; the existing `StockChangeReason` stays what it is, the
  reason the offer ratchet records for a lowered row. `transferToPool` publishes neither
  `InventoryTransferredTo/FromUserEvent` nor the booked-out-by-other notice (§4.2). `supply` (donations)
  and `purchasing` (accepted deliveries) are its callers; neither writes a row of its own.
- `inventory.api.EarmarkTargets.Donations` — a read SPI beside `Missions` and `JobOrders`, implemented
  by `supply`, so the Lager can show a pledge on a row without naming `supply`.
- `bank.api.PurposeBoundRequests` — `requestWithdrawal(accountId, amount, recipientUserId,
  purpose)`, `requestDeposit(…)`, `cancel(requestId)`; `MANDATORY`; validates through the existing
  `BankBookingGuards`; writes the same bank audit events. `BankPurposeKind` (the closed enum, first
  value `PURCHASING_DEAL`) sits in `bank.api` beside `BankBookingRequestType`.
- `bank.api.BookingRequestPurposeObserver` — `onConfirmed / onRejected / onCancelled(requestId,
  purpose, transactionId)`; called by `BankBookingRequestService` inside its transaction for
  requests with a purpose; implementations are `MANDATORY`.
- `bank` schema: `bank_booking_request.purpose_kind` (enum, nullable) + `purpose_ref` (uuid,
  nullable), both or neither (check constraint); the bank-wide four-eyes check (E-03,
  REQ-BANK-062) lives in owner approval and confirm of every request, independent of the purpose.

### 5.3 Interaction mechanisms (ADR-0232)

| Interaction | Mechanism | Why |
| --- | --- | --- |
| supply / purchasing → inventory (book-in, transfer, earmark) | command API, `MANDATORY` | stock and donation/acceptance must commit together |
| inventory → supply (what a pledge earmark shows on a Lager row) | read SPI `EarmarkTargets.Donations`, owned by inventory, implemented by supply | inventory (rank 8) never names a higher module; one read per page |
| supply → joborder (does the need's job order belong to the unit) | query on `joborder.api` | read only; why supply is rank 11 (§5.1) |
| purchasing → bank (request money, return remainder) | command API, `MANDATORY` | the tranche row and the request commit together |
| bank → purchasing (request confirmed/rejected/cancelled) | observer SPI owned by bank | deal state must equal request state at commit |
| purchasing → supply (need open? progress) | query API | read only |
| supply → purchasing | none | supply knows nothing of purchasing (E-02) |
| everything → notification | after-commit event (REQ-NOTIF-002) | a notice may fail on its own |
| purchasing → notification (settlement overdue) | `TimedNoticeProducer` SPI, run by the notification module's one advisory-locked task (ADR-0245) | the marker and the event commit together; at most one notice per deal |
| everything → audit | direct `AuditRecorder` call, `MANDATORY` (REQ-AUDIT-001, REQ-AUDIT-007) | audit row in the business transaction |
| everything → livesync | after-commit topic publish; room authorization by the module's `LiveSyncTopicAuthorizer` | as every domain |

### 5.4 Persistence

Flyway, one migration per concern, in the global sequence (provisional V284 …; one schema, one
Flyway location, one `V<n>` sequence in `backend`). Foreign keys into the foundation modules (users,
org units, catalogue) stay real keys; the links between business aggregates are plain id columns
(`job_order_id`, `source_inventory_item_id`, `bank_request_id`, a deal's `need_id`), as the table
below shows:

| Table | Owner | Key columns |
| --- | --- | --- |
| `supply_unit_setting` | supply | `org_unit_id` (unique), `may_raise_needs`, `accepts_donations`, `version` |
| `supply_need` | supply | unit, `material_id` xor `game_item_id`, `quality_tier_id`, `amount`, `priority`, `purchasable`, `purchase_reason`, `max_unit_price`, `job_order_id` (id reference, no FK into another module's aggregate — §5.6 of the modularisation plan), `status`, audit columns, `version` |
| `supply_donation` | supply | donor `user_id` (nullable after erasure), receiving unit, material xor item, quality, `pledged_amount`, `received_amount`, `need_id`, `source_inventory_item_id` (id reference), `status` (`PLEDGED`, `RECEIVED`, `PARTIALLY_RECEIVED`, `DECLINED`, `WITHDRAWN`), `decline_reason`, `received_by`, timestamps, `version` |
| `purchasing_setting` | purchasing | singleton: `governing_area_id`, `funding_account_id`, `default_monthly_limit`, `budget_cap`, `budget_period`, `settlement_due_days`, `paused`, `pause_reason`, `version` |
| `purchasing_unit_pause` | purchasing | per raising unit pause |
| `purchasing_risk_class` | purchasing | `code` A/B/C, `max_total`, `max_advance_percent`, `requires_known_partner`, `escort_suggested` |
| `purchasing_delegate` | purchasing | `user_id`, `appointed_by`, `appointed_at`, `valid_until`, `monthly_limit`, `revoked_at` |
| `purchasing_partner` | purchasing | §4.4 |
| `purchasing_deal` | purchasing | §4.3 header + settlement, `delegate_user_id`, `need_id`, `version`, `settlement_version` |
| `purchasing_deal_tranche` | purchasing | deal, kind, amount, `bank_request_id` (id reference), state, `version` |
| `purchasing_deal_delivery` | purchasing | deal, amount, location, accepted amount, quality, rejected amount, reason, `accepted_by`, booked row ids, `version` |

Inventory gains one table for the donation earmark, next to its job-order and mission allocations.
Amounts in whole aUEC (`bigint`) as the bank; SCU with the catalogue's scale. Every table enters the
table-ownership map (`backend/src/test/resources/architecture/table-ownership.txt`, REQ-DATA; the test
fails on a table with no owner). Each access policy keeps its scope fragment beside its repository
and carries a parity test against the per-row gate (REQ-DATA-010). Indexes for the scoped list
queries; the list reads are statement-bounded (REQ-DATA-003, no N+1).

### 5.5 Authorization

- Two access-policy beans owned by their modules (ADR-0236), built on the scope kernel:
  `supplyAccessPolicy` (raising / receiving unit membership, donor ownership, governing Bereich) and
  `purchasingAccessPolicy` (active delegate, deal ownership, governing Bereich, raising unit of the
  deal's need, bank staff for purpose-bound reads). Each owns its per-row gate **and** its list query
  fragment, so the two widen together.
- Controllers keep a coarse role gate on their own (`isAuthenticated()` with the member baseline;
  admin endpoints `hasRole('ADMIN')` under `/api/v1/<root>/admin/**`). The `/api/v1/*/admin/**` →
  `ADMIN` matcher already stands in `SecurityConfig` before every domain rule, so the two admin roots
  need no new rule — only a test that they are fenced. Scope and ownership live in the module API so
  non-HTTP callers hit the same gates.
- Redaction: a need's `maxUnitPrice` and purchase progress, a donation's donor for members who are neither the donor nor in the floor —
  stripped in the module's mapper for callers outside the floor, with redaction tests.
- No exchange (`/exchange/v1`) surface; the edge allow-list for the app vhost is not widened (web
  only, E-10), and the edge deny probe asserts the new roots stay closed there.
- Rate limits: the pledge and the deal create/submit endpoints get the write bucket of their
  family; `no-store` for every new family — the two roots go into `NoStoreApiScopes`
  (REQ-SEC-031, D-18 of the modularisation plan).

### 5.6 REST API (the target cut of [`rest-api-cut.md`](modularisation/rest-api-cut.md), tier T2 — web only)

The REST cut of the old roots has not run (it waits for the owner's release decision and an app
release), but the new modules are born in its shape: one root per domain, member-scoped resources under
`<root>/me`, admin under `<root>/admin`, no shape suffixes, collections paged with `q`, `page`, `size`,
`sort` and a `/lookup` for pickers (principles P1 … P5).

| Root | Operations |
| --- | --- |
| `/api/v1/supply/needs` | `GET` (filters: unit, status, material, purchasable), `POST`, `GET/PUT /{id}`, `POST /{id}/withdraw`, `POST /{id}/pause`, `POST /{id}/resume` |
| `/api/v1/supply/donations` | `GET` (unit view), `POST` (pledge or record), `POST /{id}/receive`, `POST /{id}/decline`, `POST /{id}/withdraw` |
| `/api/v1/supply/me/donations` | `GET`, `GET /stats` |
| `/api/v1/supply/stats` | `GET` (per unit and period) |
| `/api/v1/supply/admin/units` | `GET`, `PUT /{orgUnitId}` |
| `/api/v1/purchasing/deals` | `GET`, `POST`, `GET/PUT /{id}`, `POST /{id}/tranches`, `POST /{id}/deliveries`, `POST /{id}/deliveries/{did}/accept`, `PUT /{id}/settlement`, `POST /{id}/cancel` |
| `/api/v1/purchasing/partners` | `GET`, `POST`, `PUT /{id}`, `POST /{id}/block`, `POST /{id}/unblock` |
| `/api/v1/purchasing/delegates` | `GET`, `POST`, `POST /{id}/revoke`, `PUT /{id}/limit` |
| `/api/v1/purchasing/state` | `GET`, `POST /pause`, `POST /resume` |
| `/api/v1/purchasing/report` | `GET` (JSON), `GET /export` (CSV), `GET /forum-text` |
| `/api/v1/purchasing/admin/settings` | `GET`, `PUT`; `/admin/risk-classes` `GET`, `PUT` |

Nested writes (`/deals/{id}/deliveries/{did}/accept`) are authorized on the child and a child of
another deal answers 404 (the rule „nested paths keep the gate on the child"). One
OpenAPI tag per module, unique schema names, every problem code in the module's `ProblemCode` enum
and the documented list (ADR-0235).

### 5.7 Frontend

- Packages named like the backend's (REQ-FE-034, `frontend/CLAUDE.md`): `supply.web` /
  `supply.client` / `supply.model`, `purchasing.web` / `.client` / `.model`; typed clients
  `SupplyBackendClient`, `PurchasingBackendClient` over `BackendApiClient` (URI templates only;
  `TypedBackendClientTest` and `WebClientConfinementTest` hold them to it).
- Assets live in the domain folder and nowhere else: `templates/supply/…`, `static/js/supply/…`,
  `static/css/supply/…` (and the same for `purchasing`); `AssetLayoutTest` fails on a flat file. Scripts
  are ES modules (ADR-0250): `<script type="module">`, static imports by absolute path from the own
  domain and `/js/kernel/` only, registered under their domain in `typecheck-ratchet.json` and
  type-checked by `:frontend:typecheckJs`.
- Navigation (ADR-0240): the drawer is grouped, so the new group **„Beschaffung"** is a drawer group
  with „Bedarf" (every member), „Meine Spenden" (every member), „Eingehende Spenden" (receiving
  units), „Ankauf" (HB, governing Bereichsleitung, raising unit, bank staff), „Bericht", and
  „Handelsbeauftragte" (governing Bereichsleitung, admin). The quick access indexes the drawer links
  the server rendered, so visibility rules stay server-side and no endpoint is added. Admin settings
  get a „Beschaffung" section in the admin mode.
- Page patterns A to C of ADR-0242 (list, overview, form) and the colour and layer tokens of
  ADR-0243; the DAS KARTELL design system skill populated before any UI work; deal detail as an
  overview page with tabs „Übersicht · Zahlungen · Lieferungen · Abrechnung · Verlauf"; the UEX
  reference price beside every price input; the risk class and its rules shown, not hidden.
- Live update (REQ-FE-001…010): every write updates in place through `krtFetch`; peers receive
  live-sync topics `supply/needs`, `supply/donations/{unitId}`, `purchasing/deals` (room
  authorization = the module's `LiveSyncTopicAuthorizer`, reusing its read gate); each section echoes
  its own version (deal header, tranche row, delivery row, settlement).
- i18n: every string DE/EN under `supply.*` and `purchasing.*`, umlauts as `\uXXXX`.

## 6 Notifications

Built on the engine as #2414 Part A left it (ADR-0244, ADR-0245; REQ-NOTIF-018, -024 … -028) — every
prerequisite the first version of this plan waited for is shipped, so no notice below is gated on
engine work. Every type gets a rule seeded by a Flyway migration (editable by the admin), DE/EN
labels, a rule-editor option (`AdminNotificationRuleOptionListsTest`), a link via
`NotificationPageController.targetOf`, and Android rendering. All are after-commit, exclude the actor,
carry no free text. A word that differs by language („pausiert", „fortgesetzt") travels as a
`…Code` render parameter (REQ-NOTIF-028), never as a stored string.

| # | Notice | Trigger | Recipients | Cleared by | Mutable (REQ-NOTIF-027) |
| --- | --- | --- | --- | --- | --- |
| N1 | „Neue Spende angemeldet" | donation pledged | Lead / Logistician of the receiving unit (`ORG_RELATIVE_ROLE`, `RESPONSIBLE`) | receive / decline / withdraw (supersede, ADR-0096) | yes |
| N2 | „Deine Spende ist angekommen" (with amount) | donation received / partially received | the donor (`EVENT_RECIPIENT`) | — | yes |
| N3 | „Deine Spende wurde abgelehnt" | donation declined | the donor (`EVENT_RECIPIENT`) | — | yes |
| N4 | „Neuer Bedarf zum Ankauf freigegeben" | need raised or set `purchasable` | every active HB (`EVENT_RECIPIENTS`: the event lists them) | need fulfilled / withdrawn | yes |
| N5 | „Lieferung zur Abnahme" | delivery recorded | Lead / Logistician of the raising unit (`ORG_RELATIVE_ROLE`) | delivery accepted (supersede) | no |
| N6 | „Lieferung abgenommen / abgelehnt" | acceptance | the deal's HB (`EVENT_RECIPIENT`) | — | no |
| N7 | „Abrechnung überfällig" | settlement due date passed, from `SettlementOverdueNoticeProducer` (`TimedNoticeProducer`, REQ-NOTIF-026) | the deal's HB (`EVENT_RECIPIENT`); the governing Bereichsleitung (`ORG_RELATIVE_ROLE` `UNIT_LEADERSHIP`); `ROLE` bank management | settlement saved (per-entity supersede) | no |
| N8 | „Ankauf pausiert / fortgesetzt" | pause / resume | every active HB (`EVENT_RECIPIENTS`); raising unit leadership (`UNIT_LEADERSHIP`) | resume supersedes pause | no |
| N9 | „Du bist Handelsbeauftragter / nicht mehr" | appointment / revocation | the member (`EVENT_RECIPIENT`) | revocation supersedes appointment | no |
| N10 | money tranche notices | — | **reused**: the bank's `BANK_BOOKING_REQUEST_CREATED / APPROVED / CONFIRMED / REJECTED` (REQ-BANK-057, `bank-notifications.md`) already reach approvers, requester and responsible holder; the correction of a pending request (REQ-BANK-056) applies unchanged | — | as bank |

The overdue marker is a column on the deal, written in the producer's transaction together with the
event, so a deal produces N7 at most once; changing the due days or the delivery date resets it.
Whether a type can be muted is the type's own declaration (ADR-0245): the donor notices can, the
notices a purchasing duty hangs on cannot.

## 7 Audit, monitoring, data protection

### 7.1 Audit (REQ-AUDIT-001)

Two new `AuditDomain` values (the enum lives in `audit.api`, next to the twelve of today),
`SUPPLY` („Bedarf & Spenden") and `PURCHASING` („Ankauf"), registered everywhere a domain is (export
and purge switches, person-search targets, `pdf.audit.title.*`, the frontend `AuditDomains`, the
viewer's per-area filter, `admin.audit.*` labels, the AuditDomainSilenceAnomaly exclusion list — both
are low-traffic). Every call is a direct `AuditRecorder` call inside the business transaction
(REQ-AUDIT-007); never an event. Event types:

- `SUPPLY`: `NEED_RAISED`, `NEED_UPDATED`, `NEED_PAUSED`, `NEED_RESUMED`, `NEED_WITHDRAWN`,
  `NEED_FULFILLED`, `DONATION_PLEDGED`, `DONATION_RECEIVED`, `DONATION_DECLINED`,
  `DONATION_WITHDRAWN`, `DONATION_RECORDED`, `SUPPLY_UNIT_SETTING_CHANGED`.
- `PURCHASING`: `DEAL_CREATED`, `DEAL_UPDATED`, `DEAL_CANCELLED`, `DEAL_TRANCHE_REQUESTED`,
  `DEAL_TRANCHE_FUNDED`, `DEAL_TRANCHE_REFUSED`, `DEAL_DELIVERY_RECORDED`, `DEAL_DELIVERY_ACCEPTED`,
  `DEAL_DELIVERY_REJECTED`, `DEAL_SETTLED`, `DEAL_FAILED`, `PARTNER_CREATED`, `PARTNER_UPDATED`,
  `PARTNER_BLOCKED`, `PARTNER_UNBLOCKED`, `PARTNER_ERASED`, `DELEGATE_APPOINTED`,
  `DELEGATE_REVOKED`, `DELEGATE_LIMIT_CHANGED`, `PURCHASING_PAUSED`, `PURCHASING_RESUMED`,
  `PURCHASING_SETTINGS_CHANGED`, `RISK_CLASSES_CHANGED`.
- Stock movements additionally audit in `INVENTORY` through `StockCommands` (reason `DONATION` /
  `PURCHASE`); money movements stay in `bank_audit_event`, with the purpose reference in its
  details.
- Details payloads: ids, enums, amounts, quality, tier codes — never notes, partner names or
  handles.

### 7.2 Monitoring (REQ-OBS-005…011)

- Counters: `basetool_supply_donations_total{status}`, `basetool_supply_needs_total{event}`,
  `basetool_purchasing_deals_total{event}`, `basetool_purchasing_tranches_total{kind,outcome}`,
  audit-event counters for both domains (existing mechanism).
- Gauges, each registered by its own module in a `*BusinessGauges` class in `internal` (the pattern of
  `BankBusinessGauges`; the composition-root `BusinessMetricsCollector` in `app` stays out of it):
  `basetool_supply_needs_open`, `basetool_supply_donations_pledged_open`,
  `basetool_purchasing_deals{status}`, `basetool_purchasing_settlements_overdue`,
  `basetool_purchasing_paused` (0/1).
- No task metrics of its own: the overdue notice is produced by the notification module's single
  timed-notice task, which already reports runs, duration, failures and notices produced through
  `TaskMetrics` (ADR-0245). The producer's own failures are covered by that task's alert.
- No user, partner, unit or material as a label (bounded labels only).
- Alerts: `PurchasingSettlementOverdue` (warning, > 0 for 3 days); Grafana: a „Beschaffung" row on the
  business dashboard (donations vs purchases per week, open deals by state, overdue settlements).
- Tracing: no new outbound call.

### 7.3 Data protection

- Donor and HB are members: their donations and deals are part of the personal-data export (each module
  contributes a `PersonalDataExportSection`, as `BankExportSections` does) and of erasure and handle
  anonymisation (the `UserErasureParticipant`, `HandleAnonymisationParticipant` and
  `UserReassignmentParticipant` SPIs of `platform.api`: donor and HB anonymised, donations and deals
  kept for the books).
- External individuals (partners): §4.4, E-05; listed in the data-protection notes as a new category
  of data subject.
- No personal data in metrics, logs (`LogSafe`), notifications beyond display names, or audit
  details.

## 8 Delivery plan

Each step is one pull request (or a short stack), shippable alone, behind the feature switch.

| Step | Content | Depends on |
| --- | --- | --- |
| S0 | Approval of this plan; ADRs (§10); specs `supply.md`, `purchasing.md` and the amendments (§9) | owner |
| B1 | Bank-wide four-eyes rule (REQ-BANK-062, F-1 … F-4): independent of the rest, may ship first; bank wiki page and ROLES_AND_PERMISSIONS amended | S0 |
| S1 | Extend `inventory.api.StockCommands` (book-in into pool, transfer to pool, donation earmark and its `EarmarkTargets.Donations` SPI), with the audit contract, the lot-lock tests and the proof that no transfer notice fires | S0 |
| S2 | `supply` module (declared, in the domain map, in the table-ownership map): settings, needs, donations, web pages, N1–N3, audit `SUPPLY`, gauges, statistics; switch `supply.enabled` | S1 |
| S3 | `purchasing` core without money: delegates, partners, deals, deliveries, acceptance with book-in, settlement record, pause; audit `PURCHASING`; N4–N6, N8, N9 | S2 |
| S4 | Bank integration: purpose columns, `PurposeBoundRequests`, observer SPI, limits and budget cap, risk classes; bank-update skill split | S3 |
| S5 | Overdue settlement: `SettlementOverdueNoticeProducer` and N7 | S3 |
| S6 | Report (CSV, forum text), dashboard, alerts | S4 |
| Pilot | Admin switches `purchasing.enabled` after the OL resolutions; BL Profit appoints HB, sets cap; 4–6 weeks; evaluated with the S6 report | S4 (S5, S6 recommended) |

### 8.1 Where S2 deviates from this text

- The governing Bereich and the switch `supply.enabled` are system settings (`supply.governing_area_id`),
  not columns; the sidebar flag reaches the frontend through `SupplyFeatureState` (30 s cache) and a
  layout advice, not through the capabilities record.
- A donation and a need carry **no free text**: the `note` fields are dropped, so no person can be named
  in them (REQ-SUP-014).
- The trade delegate's view of the purchase fields goes through the `NeedPriceAudience` SPI that
  `supply` owns and `purchasing` implements (S3); without an implementation nobody is a delegate.
- A need's covered and purchased amounts are credited by bulk `UPDATE` statements that leave the
  need's version alone (REQ-SUP-019).
- Not built in this repository: the Android rendering of the three notices (repository
  `basetool-android`); the supply live-sync topics are web-only.

### 8.2 Where S3 … S6 deviate from this text

- A donation has no anonymous mode (decided after the review): the receiving unit must know from whom
  the goods arrive in the game (REQ-SUP-012).

- The governing Bereiche of purchasing are a set (table `purchasing_setting_governing_area`, the Bereich
  Profit by default) and the switch `purchasing.enabled` a system setting; supply keeps its own pair. Only
  their Bereichsleiter and Bereichskoordinatoren govern, never a Bereichsoperator. The overdue notice names
  the first governing Bereich as its context. The delegate's capability is the
  appointment row itself (ADR-0255).
- The settlement is its own table with its own version, not a `deal.settlement_version` column; the
  deal header takes a pessimistic row lock for every state change and tranche, delivery, close and
  settlement do not echo the header's version (REQ-PUR-026).
- `purchasing` implements three SPIs of lower modules: `BookingRequestPurposeObserver` (bank),
  `NeedLifecycleObserver` and `NeedPriceAudience` (supply). The bank transaction is not tagged with the
  purpose; a deal reaches its money through the request's id (REQ-BANK-061).
- The risk class is the smallest class whose maximum total covers the deal; the partner history enters
  through the class's „known partner" rule and not as a second computed step (REQ-PUR-011).
- The report is computed in memory over the deals of the period; the CSV has one line per deal and the
  forum text is plain text (REQ-PUR-023). The `bank-update` skill reports the confirmed purchasing
  withdrawals and the returned remainders as „davon Materialeinkauf" from the purpose columns.
- Not built in this repository: the Android rendering of the eight purchasing notices (repository
  `basetool-android`); the purchasing live-sync topic is web-only.

Nothing in the engine is left to wait for: the notification extension (#2414 Part A), the transfer
notice (#2409) and the holder reconciliation (#2413) are on `main`, so S5 is an ordinary step and N4
and N8 reach their recipients from the first day of S3. The modularisation does not block anything
either: it is finished, the new modules depend only on `api` packages that exist (`StockCommands`,
`SystemSettings`, the catalogue, identity and org-unit APIs) or are created here (the bank purpose
API), and the module checks run on them from the first commit.

Every step carries what the repository rules ask of a change: tests (unit, MockMvc gate matrix,
`@SpringBootTest` with TestContainers for the money and stock paths, concurrency tests for pledge vs
sale and for tranche confirm vs cancel, redaction tests, E2E for the pledge-receive flow and the
deal flow, `ModularityTest` and the domain-map tests for the two modules, the table-ownership test,
the scope-fragment parity test), Checkstyle/SpotBugs/NullAway and the frontend lint gates, CHANGELOG,
README, `ROLES_AND_PERMISSIONS.md`, arc42 §5 (two building blocks), §8 if a cross-cutting rule changes
and §11 for the new debt, and the German wiki (new pages „Bedarf und Spenden", „Ankauf"; „Kartellbank",
„Lager", „Benachrichtigungen" amended). The Basetool Knowledge Base was retired on 2026-10-13; the
specs, ADRs, arc42 chapters and runbooks of the repository are the only record to keep in step.

## 9 Draft requirements

Status of every requirement below: **proposed**. Numbers are provisional. „MUST" is binding once
approved.

### 9.1 REQ-SUP — Bedarf & Spenden (`docs/specs/supply.md`)

- **REQ-SUP-001 Feature switch.** The supply surface MUST be enabled by the runtime system setting
  `supply.enabled` (default off). While off, every supply endpoint MUST answer 404 and the navigation
  MUST NOT show the group's supply entries.
- **REQ-SUP-002 Unit settings.** An admin MUST be able to mark any active org unit as
  `may_raise_needs` and/or `accepts_donations`. No unit is hardcoded. Changes MUST be audited
  (`SUPPLY_UNIT_SETTING_CHANGED`).
- **REQ-SUP-003 Raising a need.** The lead (SK lead / Staffelleiter) or a Logistician of a unit with
  `may_raise_needs`, and admin, MUST be able to raise a need with: material xor game item, amount > 0
  in the catalogue's quantity type, an active quality tier, priority, optional job order of that unit.
  Any other caller MUST receive 403.
- **REQ-SUP-004 Purchasable flag.** A need MUST be purchasable only when the raising unit sets
  `purchasable = true`, gives a reason code from `HIGH_FARM_EFFORT`, `RARE_SOURCE`,
  `TIME_CRITICAL`, `OTHER` and a maximum unit price > 0. Without the flag no deal MAY reference the
  need (REQ-PUR-010).
- **REQ-SUP-005 Need lifecycle.** A need MUST move `OPEN` → `PAUSED` ⇄ `OPEN` → `FULFILLED` |
  `WITHDRAWN`. It MUST become `FULFILLED` automatically in the transaction in which credited
  donations plus accepted purchase deliveries reach its amount. `WITHDRAWN` and `FULFILLED` are
  terminal.
- **REQ-SUP-006 Need visibility and redaction.** Every member MUST see `OPEN` needs. The maximum
  unit price and the purchase progress MUST be returned only to active HB, the raising unit's lead
  and Logisticians, the governing Bereichsleitung and admin; for everyone else the fields MUST be
  absent from the response (not null-filled by the client).
- **REQ-SUP-007 Pledge.** Any member MUST be able to pledge a donation of material or game item,
  quality, amount > 0, to a unit with `accepts_donations`, optionally credited to one of that unit's
  open needs and optionally from one of the member's own Lager rows.
- **REQ-SUP-008 Earmark.** A pledge from a Lager row MUST earmark the pledged amount on that row in
  the same transaction; the earmarked amount MUST NOT be sold, discarded, transferred, offered on the
  Materialbörse or pledged again until the pledge is received, declined or withdrawn. Concurrent
  pledge and sale of the same row MUST NOT both succeed.
- **REQ-SUP-009 Receive.** The lead or a Logistician of the receiving unit (or admin) MUST be able to
  confirm a pledge with a received amount between 1 and the pledged amount and a location. In the same
  transaction the received amount MUST move into the receiving unit's pool — from the earmarked row
  via `StockCommands.transferToPool`, otherwise via `StockCommands.bookIntoPool` — the donation MUST
  become `RECEIVED` or `PARTIALLY_RECEIVED`, and the credited need's progress MUST include it.
- **REQ-SUP-010 Decline and withdraw.** The receiving unit MUST be able to decline a pledge with a
  reason code; the donor MUST be able to withdraw an unreceived pledge. Both MUST release the earmark.
- **REQ-SUP-011 Recorded donation.** A Logistician or lead of a receiving unit MUST be able to record
  a donation already received for a named member donor or without donor, booking it into the pool in
  the same transaction.
- **REQ-SUP-012 Donor visibility.** A donation MUST NOT have an anonymous mode. The donor's identity
  MUST be returned to the donor, the receiving unit's lead and Logisticians, the governing
  Bereichsleitung and admin, and to nobody else.
- **REQ-SUP-013 Statistics.** The tool MUST provide per month and receiving unit: number of donations,
  distinct donors, received amount per material and item. A member MUST see their own totals; the
  per-donor list MUST be visible only to the governing Bereichsleitung and admin.
- **REQ-SUP-014 No free text outside the donation.** A donation's optional note MUST NOT appear in
  notifications, audit details, metrics or logs.
- **REQ-SUP-015 Transfer notice.** A donation that changes a Lager row's owner MUST produce the
  donation notices (REQ-SUP-016) and MUST NOT additionally produce the owner-change transfer notices
  of REQ-INV-055 or the booked-out-by-other notice of REQ-INV-056.
- **REQ-SUP-016 Notifications.** Pledged → receiving unit lead and Logisticians (N1, superseded on
  receive/decline/withdraw); received / partially received / declined → the donor (N2, N3). After
  commit, actor excluded.
- **REQ-SUP-017 Audit.** Every supply mutation MUST record its `SUPPLY` audit event in the same
  transaction (REQ-AUDIT-001); stock movements additionally record their `INVENTORY` events.
- **REQ-SUP-018 Live update.** Every supply write MUST update the page in place and propagate to peers
  on `supply/needs` and `supply/donations/{unitId}` without a reload.
- **REQ-SUP-019 Locking.** Need and donation each carry their own `version`; editing a need MUST NOT
  conflict with a concurrent donation to it.
- **REQ-SUP-020 Privacy.** Donations MUST be part of the donor's data export; on erasure the donor
  MUST be anonymised and the donation kept.

### 9.2 REQ-PUR — Ankauf (`docs/specs/purchasing.md`)

- **REQ-PUR-001 Feature switch.** `purchasing.enabled` (default off) as REQ-SUP-001.
- **REQ-PUR-002 Settings.** An admin MUST set the governing Bereich, the funding account (an active
  `CARTEL` or `SPECIAL` account), the default monthly limit per HB, the budget cap and its period,
  the settlement due days and the risk-class table. Changes MUST be audited.
- **REQ-PUR-003 Appointment.** The Bereichsleiter of the governing Bereich, OL members and admin MUST
  be able to appoint a member as HB with an optional end date and personal monthly limit, and to
  revoke it. Only an active, unexpired, unrevoked appointment MUST confer the HB capability. A
  revoked HB MUST keep read access to and the settlement duty for their running deals, and MUST NOT
  create or submit deals.
- **REQ-PUR-004 Partners.** HB, the governing Bereichsleitung and admin MUST be able to create, edit,
  block and unblock partners of kind `ORG` or `INDIVIDUAL`. A blocked partner MUST NOT be used on a
  new deal or tranche.
- **REQ-PUR-005 External only.** A deal's counterparty MUST NOT be a member: an `INDIVIDUAL`
  partner whose handle equals a member's handle (case-insensitive) MUST be refused at create and at
  every submit with `PURCHASING_COUNTERPARTY_IS_MEMBER`.
- **REQ-PUR-006 Partner erasure.** An admin MUST be able to erase an individual partner's handle and
  notes; deals MUST be kept with a placeholder.
- **REQ-PUR-007 Deal create.** An active HB MUST be able to create a deal for exactly one need with
  partner, material/item of the need, quality tier ≥ the need's tier, amount, unit price, payment
  model, escort wish and optional Einsatz.
- **REQ-PUR-008 Price ceiling.** The unit price MUST be ≤ the need's maximum unit price; otherwise
  `PURCHASING_PRICE_ABOVE_MAXIMUM`. The UEX reference price MUST be shown beside it (no gate).
- **REQ-PUR-009 Need remainder.** The sum of amounts of all non-cancelled, non-failed deals of a need
  MUST NOT exceed the need's open remainder at submit.
- **REQ-PUR-010 Need state.** A deal MUST be submitted only for an `OPEN`, purchasable need, while
  purchasing is neither paused globally nor for the need's unit.
- **REQ-PUR-011 Risk class.** The system MUST compute the risk class from the deal's total and the
  partner's settled-without-loss history according to the risk-class table; the HB MAY raise it and
  MUST NOT lower it. The class's maximum total and maximum advance share MUST be enforced on every
  tranche; a class requiring a known partner MUST refuse a partner without a settled deal.
- **REQ-PUR-012 Tranche request.** Each tranche MUST be raised as a bank `WITHDRAWAL` request from the
  funding account to the HB, through `bank.api.PurposeBoundRequests`, carrying
  `purpose_kind = PURCHASING_DEAL` and the deal id, with a generated justification (deal number,
  material, amount — no partner name). It MUST then follow the existing approval ladder and queue
  unchanged (REQ-BANK-041/-047).
- **REQ-PUR-013 Limits.** A tranche MUST be refused when the HB's requested-and-not-refused money in
  the current calendar month plus the tranche exceeds the HB's limit, or the period's total exceeds
  the budget cap (`PURCHASING_LIMIT_EXCEEDED`, `PURCHASING_BUDGET_EXCEEDED`).
- **REQ-PUR-014 Bank state mirror.** When the bank confirms, rejects or cancels a purpose-bound
  request, the tranche and deal state MUST change in the same transaction, through the bank-owned
  observer SPI.
- **REQ-PUR-015 Deliveries.** The deal's HB MUST be able to record deliveries; each MUST NOT exceed
  the deal's undelivered amount.
- **REQ-PUR-016 Acceptance.** The lead or a Logistician of the need's raising unit (or admin) MUST
  accept a delivery with accepted amount, rejected amount (with reason code) and measured quality. An
  accepted amount MUST meet the need's quality tier. In the same transaction the accepted amount MUST
  be booked into the raising unit's pool via `StockCommands.bookIntoPool` (reason `PURCHASE`) and
  credited to the need. The HB MUST NOT accept their own delivery and MUST NOT write stock.
- **REQ-PUR-017 Settlement.** After `DELIVERED` or `FAILED` the HB MUST record the settlement: money
  received, money paid to the counterparty, loss with reason code; a positive remainder MUST be
  returned as a purpose-bound `DEPOSIT` request into the funding account. The deal MUST become
  `SETTLED` only when the remainder's deposit is confirmed (or the remainder is zero).
- **REQ-PUR-018 Overdue settlement.** A deal not settled within the configured days MUST be marked
  overdue on every deal list and in the report and MUST raise N7 (once per deal, through a
  `TimedNoticeProducer`, REQ-NOTIF-026).
- **REQ-PUR-019 Pause.** The raising unit's lead, the governing Bereichsleitung, OL members and admin
  MUST be able to pause and resume purchasing globally or per raising unit with a reason code. While
  paused, deal submit and tranche requests MUST be refused (`PURCHASING_PAUSED`); deliveries,
  acceptance and settlement MUST continue.
- **REQ-PUR-020 Need withdrawn.** When a need is withdrawn, its deals without a confirmed tranche MUST
  be cancelled and their open tranche requests cancelled in the bank in the same transaction; deals
  with money out MUST continue to settlement.
- **REQ-PUR-021 Visibility.** A deal MUST be readable by active and former HB, the governing
  Bereichsleitung, the raising unit's lead and Logisticians, bank staff for its purpose-bound
  requests, and admin; nobody else. Partner notes MUST NOT be returned to bank staff or the raising
  unit.
- **REQ-PUR-022 No deletion.** Deals, tranches and deliveries MUST NOT be deletable; `CANCELLED` is
  allowed only before any confirmed tranche.
- **REQ-PUR-023 Report.** HB, the governing Bereichsleitung, bank staff, the raising unit and admin
  MUST be able to read and export the monthly report of §4.5 (CSV and forum text) without member
  names or partner handles.
- **REQ-PUR-024 Notifications.** N4–N9 as in plan §6, after commit, actor excluded, N5–N9 not
  mutable.
- **REQ-PUR-025 Audit.** Every purchasing mutation MUST record its `PURCHASING` audit event in the
  same transaction; details MUST carry no partner names, handles or notes.
- **REQ-PUR-026 Locking.** Deal header, settlement, each tranche and each delivery MUST carry their
  own version; recording a delivery MUST NOT conflict with a concurrent tranche confirmation.
- **REQ-PUR-027 Live update.** Every purchasing write MUST update in place and propagate on
  `purchasing/deals`.
- **REQ-PUR-028 Privacy.** Deals MUST be part of the HB's data export; erasure anonymises the HB and
  keeps the deal.

### 9.3 Amendments to existing requirements

| Spec | Requirement | Amendment |
| --- | --- | --- |
| `bank.md` | **new REQ-BANK-061** Purpose reference | A booking request MAY carry `purpose_kind` + `purpose_ref` (both or neither), set only through the bank's command API, never by the member or staff REST surface; shown read-only on request and transaction detail. |
| `bank.md` | **new REQ-BANK-062** Four-eyes for every booking request | The requester of a booking request MUST NOT grant its owner approval and MUST NOT confirm it (`BANK_FOUR_EYES_REQUIRED`); `callerMayConfirm` and the owner-approval action MUST be absent for the requester. The requester MAY reject it (F-1). The rule binds bank employees and bank management; an **admin** MAY grant and confirm their own request (F-4). Direct bookings are not requests and stay unchanged (F-2). A responsible holder's own request stays exempt from owner approval (ADR-0123, F-3) and still needs a confirmer other than the holder. A request whose only eligible confirmer is the requester stays pending until another employee with the grant, bank management or an admin confirms it. |
| `bank.md` | **new REQ-BANK-063** Purpose observer | On confirm, reject and cancel of a purpose-bound request the bank MUST call the registered observer for that purpose kind inside its transaction; an observer failure MUST roll the bank decision back. |
| `bank.md` | REQ-BANK-045 | unchanged; the generated justification satisfies it. |
| `inventory-lager.md` | **new REQ-INV-057** Pool and donation commands | `StockCommands` MUST offer `bookIntoPool`, `transferToPool` and the donation earmark / release alongside its existing calls, `MANDATORY`, under the lot-lock protocol (ADR-0229), each recording its `INVENTORY` audit event with a `StockBookingReason`. |
| `inventory-lager.md` | **new REQ-INV-058** Earmarked stock | An earmark for a pledge MUST reduce the row's available amount for every checkout, rebook, offer and allocation path. |
| `inventory-lager.md` | REQ-INV-055, REQ-INV-056 | a transfer into a pool for a donation publishes neither notice (REQ-SUP-015). |
| `audit.md` | REQ-AUDIT-001 | coverage list gains `SUPPLY` and `PURCHASING` with the event types of plan §7.1. |
| `notifications.md` | „Wired use cases" | gains N1–N9 on the engine of REQ-NOTIF-024 … -028 (N10 reuses `bank-notifications.md`). |
| `observability.md` | REQ-OBS-005…011 | the metrics and alerts of plan §7.2. |
| `module-boundaries.md` | REQ-MOD-001, -006 | domain map gains `supply 11`, `purchasing 12`; both declared and in `ModularityTest.DECLARED_MODULES`; no baseline edge. |
| `data-persistence.md` | table-ownership map (`table-ownership.txt`) | the eleven tables of §5.4 and inventory's donation-allocation table. |
| `security-and-access.md` | REQ-SEC | the two access policies, the `/api/v1/*/admin/**` fence covering `/api/v1/supply/admin/**` and `/api/v1/purchasing/admin/**`, `no-store` classification (`NoStoreApiScopes`), the edge stays closed (web-only). |
| `api-conventions.md` | REQ-API | the two roots, tier T2, problem codes registered. |
| `docs/privacy/` (data-protection notes) | — | external individual partners as a new category of data subject. |

## 10 ADRs to write on approval

The next free number on `main` is ADR-0251 (2026-10-13); claim at push time.

1. **Material supply and purchasing are two modules** (`supply` rank 11, `purchasing` rank 12) —
   E-02, the module placement of §5.1.
2. **A bank booking request may carry an opaque purpose, and the bank notifies its owner through an
   observer SPI** — REQ-BANK-061/063, keeps ADR-0020 (bank org-unit-blind) intact; amends ADR-0028
   only if the seam rules need the new API named.
3. **Four-eyes for every bank booking request, admin excepted** — E-03, F-1 … F-4, REQ-BANK-062;
   amends ADR-0123 (the exemption stays, the confirmer may no longer be the requester).
4. **Handelsbeauftragte are an in-app appointment, not a Keycloak role** — E-01.
5. **`StockCommands` gains the pool and donation commands** — E-11; the first writes that put stock
   into an org-unit pool and the donation earmark as a third allocation kind, with `transferToPool`
   as a command of its own so that no transfer notice fires.

## 11 Risks

| Risk | Mitigation |
| --- | --- |
| The tool suggests control it does not have: in-game payments are invisible | Every money and goods figure is the HB's or ARGON's declaration; the deal shows who declared what; acceptance by a second party (ARGON) and the bank's four-eyes are the actual controls |
| Members feel watched by the donation statistics | Own statistics private to the member, per-donor list only for the governing Bereichsleitung, no ranking page |
| Earmark logic interferes with existing Lager paths (sale, rebook, Materialbörse ratchet) | Earmark as an allocation kind inside inventory (beside job-order and mission allocations), concurrency tests per path (REQ-SUP-008) |
| A bank change regresses the money path | The bank change is additive (two nullable columns, an SPI) and the four-eyes check ships as its own step B1 with its own tests (requester refused, admin allowed, reject allowed, direct booking unchanged, holder exemption unchanged); the existing bank suites, `BankReadNoNPlusOneTest`, the ladder tests and E2E stay green unchanged |
| Notification noise | Donor notices mutable (REQ-NOTIF-027), N4/N8 only to the listed HB (`EVENT_RECIPIENTS`), one notice per action, N7 once per deal |
| A donation transfer trips the Lager's transfer or booked-out notices | `transferToPool` is its own command that publishes neither event; a test asserts no `INVENTORY_TRANSFERRED_*` and no `INVENTORY_BOOKED_OUT_BY_OTHER` notice after a received donation |
| Pilot aborted | Switch off `purchasing.enabled`; data stays readable for admin; `supply` unaffected (E-02) |
| Personal data of non-members | E-05 minimal fields, erasure, restricted visibility |

## 12 Out of scope

Profit medals; Android screens beyond notification rendering; any `/exchange/v1` surface; automatic
reading of UEX reputation; payments inside the tool other than bank requests; using the job order as
the purchase record; a new bank account type.
