> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-11-11.
> **Owner area:** ORDERS · **Related ADRs:** [ADR-0359](../adr/0359-orders-record-money-operations-and-replay-their-outcomes.md), [ADR-0358](../adr/0358-one-configured-transfer-fee-policy-serves-bank-and-orders.md)
> **Status:** The shared workspace supports aUEC creation, provision, unit commitments, handover, settlement, corrections and holder and recipient maintenance. [Kartellbank handling](orders-auec-bank.md) is selected by default for each new applicable money operation. Each operation offers an explicit Bank-free choice.

# aUEC procurement orders

## Context & goal

Other OrgUnits commission Profit to make an agreed aUEC amount available to the requester.
An order tracks its own reported money, its holders and actual movements. It is usable in full
without Kartellbank. [Shared order rules](orders-core.md) own organisation, participation,
common read access and eligibility. [Optional Bank integration](orders-auec-bank.md) alone owns
the additional Bank effect timing and mappings. This document owns the order's monetary meaning,
operation identity and user workflow, irrespective of operating mode.

The approved planning decisions are historical rationale in the
[Wayfinder map](https://github.com/krt-profit/basetool/issues/3192). This document, the linked
requirements and the [role matrix](../../ROLES_AND_PERMISSIONS.md) are the canonical target.
They do not authorise implementation.

## Requirements

### REQ-ORDERS-057 — Target, holdings and handover are distinct whole-aUEC figures

The target is the net amount due to the requester, excluding fees. Distinguish:

| Figure | Meaning |
| --- | --- |
| Target | Current requested net aUEC amount |
| Provided/procured | Money first made available to this order, once, regardless of whether newly earned or drawn from existing authorised Bank funds |
| Booked holding | Effective, manually reported purpose-bound amount per player and order |
| Available holding | Booked holding less its locally bound pending amounts; no negative available balance |
| Outstanding commitment | A unit's still-planned contribution; no money or fulfilment |
| Net handed over | Effective requester handovers less actual requester returns and applicable corrections |
| Remaining | `max(0, target − net handed over)` |
| Remaining holdings / overpayment | Money still assigned to the order / `max(0, net handed over − target)`, shown separately |

Inputs and effective monetary figures use exact whole aUEC, not binary floating-point arithmetic.
A holder's amount is neither their complete in-game wallet nor a Kartellbank account balance.
“Reserved” means the same reported purpose binding; it is no additional amount or approval step.
“Centrally collected” describes optional consolidation with one holder, not another procurement
counter. No “in transit”, sent-versus-received or planned-transfer balance exists in the
bank-independent model. Bank waiting is the separate integration condition.

**Acceptance**

- [ ] With target 10 million, held 10 million and handed over zero, remaining is 10 million and the order is not completed.
- [ ] Commitments, held amounts and delivered amounts cannot be added into a misleading fulfilment total.
- [ ] All monetary input, storage, display and returned amounts preserve whole-aUEC values; fees follow REQ-ORDERS-060.
- [ ] The main progress displays target, net handed over and remaining; current availability and Bank waiting remain distinguishable.

**Enforced by:** `JobOrderMoneyIntegrationTest` verifies exact amounts and separate Bank-free
provision, holdings, fees, net handover, remaining and overpayment against PostgreSQL.
`JobOrderMoneyE2eTest` exercises the money workspace and reload-safe result recovery. The Bank
increment owns pending availability.

### REQ-ORDERS-058 — Reported holdings support players with or without accounts

Several players may hold money simultaneously; central collection is optional. A permitted
holder may immediately record their own holding through their explicitly linked account.
A responsible-unit writer may record holdings for named players. No second confirmation is
required. The record is a manual statement that the money is actually present and purpose-bound
for this order, not a technical wallet verification. Record the actor and change time; it remains
valid until corrected, without automatic expiry.

An app-authorised reader may provide their own money and thereby create their own linked holder
in the same action. A holder recording an actual own outgoing transfer may select or name a new
receiving holder; that action credits exactly that transfer and does not permit invented opening
funds for the counterparty. Other grants are in the role matrix.

Kartell members without Basetool accounts are supported by the same account-search/free-text
entry. A known account must be consciously selected. A name alone never links an account or
grants personal rights. Reuse stable holder identities within the order; do not create another
linked holder for the same account. Name correction, explicit later account linking and deliberate
same-person merging preserve amounts and references. Suspected free-text duplicates are not
automatically merged. Correct double-reported money through a linked financial correction. An
actual change of holder uses a movement. A responsible-unit writer can correct a holder name,
explicitly link an active account or merge two records for the same person. A merge retains the
original holder IDs as history references and combines their effective holdings under the selected
survivor. The resulting account link grants rights only to the selected account.

Holdings remain independent across orders. There is no common pool or coverage check against
other orders, the full game wallet or global Bank custody. Duplicate use of the same real money
under separately reported order holdings cannot reliably be detected: that is an accepted limit
of manual reporting, not a guarantee supplied by this model.

**Acceptance**

- [ ] A reader's self-provision creates a linked holder and amount in one action without approval or Bearbeiter enrolment.
- [ ] A responsible writer records a named accountless holder; an unrelated account with the same name gains no rights.
- [ ] Explicit linking/merging retains every amount and movement without another procurement entry.
- [ ] A personal incoming entry cannot invent additional funds for a foreign source.
- [ ] The same player can hold independently reported amounts for two orders; the UI does not claim global coverage.

**Enforced by:** `JobOrderMoneyIntegrationTest` and `JobOrderMoneyPrivacyIntegrationTest` exercise
holder authority, explicit linking, same-person merging and history retention. `JobOrderMoneyE2eTest`
contains the holder maintenance and live draft cases.

Initial provision accepts the reader's own linked holder, or a responsible writer's explicitly
selected account, existing holder or newly named accountless person. An outgoing holder can add an
inline receiving holder through their actual collection. A personal incoming recorder cannot add
foreign funds. A genuinely retired requester designation retains linked recipient readership when
it has a recorded handover. Either current unit writer can correct a mistaken direct designation.
Correcting a mistaken holder link also updates any associated requester designation. Past
operations retain the original designation ID and resolve the current recipient through the
correction. The superseded designation gives no personal read grant and is unavailable for new
movements and returns.

### REQ-ORDERS-059 — Unit commitments are planning with one-time provision attribution

Only the responsible unit and supporting Staffeln may give amount commitments, one current
commitment per unit and order. Overall responsibility is not an extra commitment. Personal
commitments do not exist. Commitments attach to the shared participation model; no second
enrolment is introduced.

An explicitly attributed new provision reduces the selected unit's outstanding commitment once.
Do not infer attribution from the holder, recorder or their membership. Credit only the
appropriate actually provided amount, not more than the open commitment. Unattributed provision
remains allowed. Internal transfers, requester handovers and later Bank postings of the same money
neither provide money again nor reopen a fulfilled commitment. Cross-order purpose inflow is
separate from new provision and does not masquerade as a second newly fulfilled contribution.

A true later loss or release does not make a past correct provision false. A correction of false
provision or attribution corrects the connected credit as well, without reviving an ended
commitment. Open commitments end on completion, rejection, cancellation or participation ending;
reopening does not revive them. An incoming responsible Staffel retains its open commitment.
When the former responsible unit has no active supporting participation after the transfer,
its outstanding commitment ends automatically. Prior credit and history remain associated
with that occurrence. A later deliberate supporting participation and a new commitment are
possible. Material's uncovered-capacity/proportional-cut policy
([REQ-ORDERS-024](orders-material-claims.md)) is not applied to money commitments.

**Acceptance**

- [ ] A unit commits 6 million and explicitly provides 4 million: 2 million remains committed, not 10 million provided.
- [ ] Internal collection and requester handover retain the original one-time credit.
- [ ] An ordinary personal Bearbeiter cannot create a unit commitment by virtue of that entry.
- [ ] Terminal ending is historical; reopening requires a new conscious commitment.
- [ ] Transfer preserves an incoming Staffel's commitment and ends the outgoing remainder when it has no active support. Later participation leaves the ended occurrence historical.

**Enforced by:** Job-order support and money integration tests exercise unit authority,
explicit credit and replay, parallel credit, reassignment, completion and cancellation in
PostgreSQL. Job-order browser tests verify the live commitment, retained peer input and the
history link to the money operation. `JobOrderMoneyIntegrationTest` contains the Bank-free case
for correcting the credit after a later revision and the case for an ended occurrence. Bank
confirmation has its own acceptance checks.

After a 6,000,000 aUEC commitment is credited with a reported provision of 4,000,000 aUEC,
the outstanding amount is 2,000,000 aUEC. If the unit deliberately revises that outstanding
amount to 5,000,000 aUEC and the original provision is then corrected to 3,000,000 aUEC,
the corrected effective credit is 3,000,000 aUEC. The missing 1,000,000 aUEC increases
the outstanding amount of the same active occurrence to 6,000,000 aUEC. A correction
to an ended occurrence changes the historical credit and leaves its outstanding amount
at zero. A later occurrence for the same unit is independent.

### REQ-ORDERS-060 — A completed transfer books both sides and its fixed fee once

Record actual transfers in one operation, with simultaneous source debit and recipient credit.
There is no order-transfer planning, dispatch/acceptance ladder or receipt confirmation. A
transfer's historical purpose distinguishes internal collection, requester handover and settlement.
Current unit membership or recipient-list changes cannot reclassify old movements.

Without Bank coupling the operation is immediately effective. Internal collection shifts order
stock only. A requester handover debits procurement holdings and increases net handover by the net
recipient amount; that amount is not still available procurement stock at the recipient.
Only a consciously designated requester recipient qualifies. Either authorised unit side may
designate recipients, including named accountless Kartell members. The Bank-account alternative
and confirmation delay belong to the integration spec.

Normally source order holdings cover principal plus fee. Additional missing means may be
explicitly provided in the same operation, subject to the corresponding provision grant:
holding 2 million, transfer 5 million means 3 million new principal provision, plus any explicitly
needed fee funding. The sender can supply the fee explicitly even when their reported balance
could cover it. Explicit new funds in this booking are limited to missing principal plus its fee.
A booked movement requires enough funds for both principal and fee.

The fee is `HALF_UP(net amount × applicable fixed rate)` in whole aUEC, added to the source debit.
Use the shared configured policy in ADR-0358. No per-operation manual fee override is allowed.
Retain the rate/fee associated with the submitted terms. A later central change does not reprice a
waiting or repeated attempt. A correction uses the historical rate; a genuinely new payment uses
its applicable rate. The integration spec owns the KRT-funded holder-transfer exception.

**Acceptance**

- [ ] Without fees, holding 2 and explicit new provision 3 followed by transfer 5 ends at source zero and recipient 5, with only 3 new provision.
- [ ] At a 0.5% rate a 2,000,000 net transfer debits 2,010,000 and credits 2,000,000; 10,000 is cost, not handover.
- [ ] Insufficient captured money without an explicit permitted provision fails without a partial debit.
- [ ] A rate change cannot alter an already submitted attempt or historical correction.
- [ ] Reclassifying a participant by name or membership does not change a posted movement's purpose.

**Shared prerequisite:** `admin.api.TransferFeePolicy` captures a normalized immutable
`RateSnapshot` and calculates `feeOn(amount, basis)` without resolving current settings.
`TransferFeePolicyTest` verifies exact whole-aUEC arithmetic and a restored basis after a
configuration change. This supplies no per-operation user input or order-to-Bank dependency.

**Enforced by:** `TransferFeePolicyTest` and `JobOrderMoneyIntegrationTest` cover exact whole fees,
explicit new funds, the affected parties, persisted terms and rollback of all posting effects.

### REQ-ORDERS-061 — Settlement reuses movements and explicit purpose changes

Overfunding and actual overpayment are allowed and displayed separately; neither changes the
target automatically. Completion or cancellation never automatically releases money, refunds it
or removes its history.

Use the movement action to release remaining money from the order's purpose, record a real
shortage, pay a named beneficiary or reassign the same player's money to another order. Release,
shortage and reassignment carry no game transfer fee. A beneficiary payment debits the holder's
principal and charges its game transfer fee separately. Its principal leaves the order's holdings
without changing the requester's net handover. Explicit additional funds can cover its fee.
A release or actual later shortage on an order Bank account position reduces the funds assigned to
the order on that position. It preserves historical provision and credit and creates no Bank posting.

Cross-order reassignment requires current rights and valid responsibility on both orders. An
eligible active destination receives the purpose inflow as the source records the purpose outflow.
Each side commits under the same operation ID. An account link identifies the same player across
orders. For accountless holders, select their record on each order and confirm that both refer to
the same person. The two effects are reported separately from new procurement.

If the holder is also a designated requester recipient, an explicit same-person purpose handover
reduces their procurement holding and increases net handover without a fictitious self-transfer,
new provision or fee. Same-person identity is deliberate, never a matching free-text name.

An actual requester return reduces net handover and credits the receiving order holder, without
counting the returned principal as new procurement. Its fee is separately covered by the returning
player's existing order holding or explicitly newly provided fee money. Do not silently reduce the
net return or decrease handover again for that fee. Preserve the original handover and return.
The return is bounded by the returning recipient's effective net receipt for that designation.
The corrected party can return money against the original designation's historical receipt.

**Acceptance**

- [ ] In a fee-free fixture, target 10, held 12 and handover 10 leaves 2 held; handover 11 shows 1 overpayment. A real source-funded fee additionally reduces holdings.
- [ ] Same-person purpose handover changes holding/handover with zero fee and zero new provision.
- [ ] Reassigning 8 from order A to B gives A −8 and B +8 with no new provision and no half-committed side.
- [ ] A net requester return of 2 reduces net handover by 2 and adds 2 at the recipient; fee funding is explained separately.
- [ ] Completion alone neither releases residual money nor manufactures a return.

**Enforced by:** `JobOrderMoneyIntegrationTest` contains PostgreSQL cases for same-person handover,
retained residuals, fee-funded requester returns, cancelled-order settlement, inverse-direction
reassignment and rollback of both sides after an audit failure. `JobOrderMoneyE2eTest` contains the
reassignment and destination refresh journey.

### REQ-ORDERS-062 — Corrections preserve truth and connected consistency

Never overwrite or delete the original financial fact to conceal an error. A linked correction
records what is corrected and the resulting effective values. A data correction performs no
in-game refund; an actual refund is its own movement. A correct original provision followed by
real loss/other use is a distinct shortage/holding reduction, retaining past provision.

A financial correction may need a connected set of affected entries. It must produce a coherent
nonnegative holding/availability state and correct associated commitment and fulfilment effects.
If 10 was recorded, 9 used and the true input was 8, an isolated edit producing −1 is refused:
correct the connected false entries together or explicitly provide actual additional funds.
Do not invent a reverse game transfer. In the fee-free example, correcting the provision to 8 and
the outflow to 7 in one submission leaves the source holding at 1 and the receiving holding at 7.
The original 10 and 9 entries remain visible. The original operation supplies the rate for any
revised fee. A correction preserves the funding rules for the original purpose.

A correction of a cross-order reassignment includes both reciprocal legs and can include affected
local entries. The changed amounts, any linked commitment credit, lifecycle effects and audit
commit together. Personal correction rights stop where foreign authority is required. The strict
invalid-responsibility gate applies even to correction and settlement.

**Acceptance**

- [ ] Original provision, linked correction, actor and changed effective amounts remain explainable.
- [ ] Correcting 10 to 8 after a 9 outflow cannot commit a negative holding.
- [ ] A real later loss reduces holdings without falsifying the original provided amount.
- [ ] Correcting a false attributed contribution adjusts its credit once; true later consumption does not.
- [ ] Identity merging does not silently remove financially duplicated entries.

**Enforced by:** `JobOrderMoneyIntegrationTest` contains the isolated-negative and connected
correction cases, replay, linked commitment credit, retained provision after loss and reciprocal
correction with a dependent local movement. `JobOrderMoneyPrivacyIntegrationTest` contains the
holder maintenance and history cases.

### REQ-ORDERS-063 — aUEC lifecycle follows effective net handover

A new order is OPEN. Organisational acceptance or first effective provision/booking changes it to
IN_PROGRESS; a commitment alone does not require that change. It becomes COMPLETED automatically
when effective net handover reaches the target. No manual completion bypass exists for aUEC.
Bank-coupled facts take effect only at the integration's successful decision boundary.

Either authorised unit side may record an agreed target change immediately, with old/new value,
actor and history, without a second approval workflow. Increasing a completed target above net
handover returns it to IN_PROGRESS; reducing it to/below net handover completes it and shows any
overpayment. Returns/corrections that make a completed order underfulfilled also recompute that
state. A CANCELLED order stays cancelled until deliberate reopening. Ordinary target and contact
edits resume after reopening.

Both unit sides may cancel and deliberately reopen, as defined in the core. Cancellation stops
normal procurement and retains all money/history. The normal movement/correction actions support
settlement, including necessary explicit fee means. “Settlement open” is a concrete indicator of
remaining money/repayment, not another state machine or work queue. Early rejection remains
with the responsible side. Terminal conditions end open commitments as in REQ-ORDERS-059.
MATERIAL/ITEM's early manual completion remains a separate rule.

Administrator hard-delete is allowed only before any monetary operation has ever reached its
accepted, durable submission boundary. An immediate Bank-free booking or submission for optional
Bank handling permanently closes that deletion path. Pending, rejected, withdrawn or replaced
Bank attempts, later corrections and a return to zero balances do not restore it. Unsent form
input and a validation/authorisation refusal before durable submission do not count as a submitted
operation. After that boundary use cancellation and linked corrections; preserve the order and
operation history under the existing privacy/anonymisation contracts in REQ-ORDERS-064.

**Acceptance**

- [ ] The last effective part-handover completes automatically; held money or a pending Bank action alone does not.
- [ ] With net handover 10, target 12 leaves 2 and IN_PROGRESS; target 8 shows 2 overpaid and COMPLETED.
- [ ] Cancellation with holdings preserves them; new normal procurement needs explicit reopening.
- [ ] A refund below target reopens a completed order, but does not reactivate a cancelled one.
- [ ] Invalid responsibility blocks even reopening, correction and coupled settlement until transfer.
- [ ] Only an administrator can hard-delete an eligible aUEC order before its first accepted monetary submission; unsent input and pre-submission validation failures do not close that path.
- [ ] A Bank-free booking or Bank-coupled submission prevents hard-delete, including after rejection, withdrawal, replacement, correction or settlement to zero.
- [ ] Concurrent first submission and hard-delete yield one coherent permitted outcome without losing a submitted operation or committing a booking against a deleted order.

**Enforced by:** `JobOrderMoneyIntegrationTest` covers the initial lifecycle, target amendments,
completion, cancellation, settlement and deletion races in PostgreSQL. The Bank increment owns
its delayed outcomes. The first accepted operation closes hard-delete. A pre-submission refusal
leaves that path open. Target changes retain the prior amount, new amount, actor and time in the
order history. The money section indicates settlement open when a terminal order has residual
holdings or the requester has received more than the target.

### REQ-ORDERS-064 — Effective operation history is the monetary authority

Authoritative effective monetary operations and linked corrections determine every holding,
provision, cost, commitment credit and handover. Persisted totals may accelerate reads but must be
reproducible and are not independently editable money truth. This does not introduce
application-wide event sourcing or replace the Bank ledger.

Reconcile the operation effects: first provision and cross-order purpose inflow/outflow balance
current booked holdings, net handovers, order-funded fees, explicit releases/settlement outflows
and real shortages. Internal principal transfers cancel within the order; requester returns reduce
net handover instead of creating provision. KRT-funded fees do not enter order-funded costs.
Pending bindings change usability rather than creating a second debit; pending new provision is
not already effective stock. Preserve known actual recipient/payment facts separately.

The participant-readable history records cause, original/revised effect, actor/system and time,
plus the connections needed to explain it. It is separate from private admin audit and does not
disappear with audit retention. Apply existing personal-data export, search, merge, erasure and
anonymisation contracts to holder identities and surviving history; this creates no blanket
retention exemption. No private Bank approval note enters the order view.

**Acceptance**

- [ ] Rebuilding totals from effective operations reproduces balances, fees, net handover and progress.
- [ ] The worked example below reconciles after every step; pending binding cannot double debit.
- [ ] Purging admin audit does not erase the order's necessary financial basis.
- [ ] Account changes and privacy actions preserve financial references without exposing unrelated personal data.

**Enforced by:** `JobOrderMoneyIntegrationTest` and `JobOrderMoneyPrivacyIntegrationTest` cover
Bank-free projection, persisted history, consolidation and erasure.

For an original Bank-free operation, the accepted terms and effect share one durable fact. A
correction records its own operation and the previous and revised amounts for each original.
The same effective history supplies the holdings and net handover used for previews, detail and
lifecycle decisions. Correction of a contribution carries the credit change and occurrence
reference. Names and actor labels resolve through the current privacy state.

When both consolidated accounts have a holder on the same order, the source holder becomes an alias
of the survivor and both provisions count toward the survivor's holdings. Identity edits record
previous and current identity, actor and time. Account deletion clears live links and historical
account references. A granted erasure redacts retained name snapshots, including historical names
linked to the person's holder and matching accountless spellings. The original operation ID still
supports authorised outcome recovery. Personal export covers the member's holder links and identity
history, directly related money operations, requester designations and recorded target changes. A
false designation can remain in identity history without granting access to the actual payment.

### REQ-ORDERS-065 — One persistent operation survives retries and lost responses

Separate the logical monetary operation, its submitted revision/Bank attempt, and HTTP request
correlation. Persist an operation identity across browser reload, response loss and retries.
Bind each submitted attempt to normalised economic terms: involved order/holder identities,
purpose, principal, explicit new means, fee payer/rate and account/recipient designations.

A repeat of the same submitted identity and terms returns its recorded outcome, not another
booking, fee, attribution, handover or Bank request. A successful replay is not rejected merely
because its original input versions are now stale. Current authorisation and identity checks still
apply. Incompatible changed terms under the same submitted identity cause a stable conflict.
Conscious corrections/replacement attempts retain lineage; a genuinely new payment has a new
operation identity. Only the valid active attempt can become effective.

Provide an authorised outcome lookup that distinguishes recorded success, waiting/known failure
and unknown outcome. An unknown browser response is not proof of failure; do not create a second
operation until the original is resolved. Lookup by UUID alone confers no access. It may project
only Bank information allowed by the integration contract. Bank request-level or connected-client
idempotency is not proof of logical-operation idempotency.

**Acceptance**

- [ ] Concurrent identical submits and replay after response loss produce one operation and one effect.
- [ ] Repeating a successful operation after relevant versions advanced returns the existing result.
- [ ] Changing amount, recipient or purpose under one submitted identity fails rather than silently returning a misleading success.
- [ ] Reload recovers the original submitted identity and outcome without another game payment.
- [ ] An unauthorised caller cannot inspect an outcome by knowing its ID.

**Enforced by:** `JobOrderMoneyIntegrationTest` covers durable provision replay, refusal, terms
and current authority against PostgreSQL. `JobOrderMoneyE2eTest` covers actual committed response
loss and recovery from a missed request followed by a stale retry.
Bank waiting and replacement attempts require their own checks.

The Bank-free provision, movement and financial correction endpoints use the caller's operation
UUID. A paired movement or correction records reciprocal entries under that UUID. Each order's
current read access governs recovery of its result. A link to the other order requires read access
there. The same authorised actor may replay matching terms with old versions or a changed current
fee configuration. A new attempt with stale holder, recipient, target or fee basis can be permanently
refused without a financial or audit effect. Refusal leaves the pre-submission
deletion boundary open. The authorized
`GET /api/v1/orders/{id}/money/operations/{operation}` returns the recorded effect,
`409 MONEY_PROVISION_REFUSED` for that permanent refusal, or `404` for an unknown result.
The browser keeps uncertain terms scoped to account and order. After a proven refusal it loads
the current holding for review and requires a deliberate new operation.

### REQ-ORDERS-066 — Narrow edit scopes protect shared financial invariants

Client versions belong to independently editable information, target, organisational/lifecycle,
holder, participation/commitment and personal-note scopes. Each command checks only the versions
it edits or genuinely relies on, through the edit-module contracts. Do not use a screen-wide
version or last-writer-wins retry for meaningful amount changes.

Internally protect affected holders together and target/net handover/completion through a narrow
fulfilment guard. Coordinate lifecycle/responsibility/hierarchy changes with eligibility-sensitive
writes; an unprotected pre-check is insufficient. Internal serialization of a genuine invariant
does not require a shared client version. Independent holders/orders remain independently editable.

Provision plus movement, correction sets and cross-order purpose changes commit all affected
balances, credits, status, history and owning-domain audit together. Follow one deterministic
resource-lock protocol across order guards, Bank requests/accounts and stock lots where they
interact. First insertion and initially absent counterparties must also be safe. Preserve existing
stock-lot-first and MANDATORY/WithinTransaction ownership; no inverse Bank-confirm/cancel path
or unbounded retry may conceal a deadlock/inconsistent partial effect.

The composition adapter and Bank callback authority are defined in ADR-0357 and the integration
spec. An outcome command does not grant Bank staff general order-edit authority. Missing required
versions and genuine conflicts use the existing RFC 7807 contract. Access is checked before
exposing version information. Exact schema, SQL and classes follow these contracts.

**Acceptance**

- [ ] Independent holder provisions and unrelated notes do not stale each other's forms.
- [ ] Competing use of the same captured funds cannot overdraw or spend a pending bound amount twice.
- [ ] Concurrent target change, handover, cancellation and hierarchy change produce a coherent permitted result.
- [ ] Either side's failure, including audit, rolls back a combined operation or cross-order change.
- [ ] PostgreSQL races cover first creation, replacement/confirm/cancel and inverse-direction transfers without deadlock-prone lock inversion.

**Enforced by:** `JobOrderMoneyIntegrationTest` exercises concurrent provision, same-source debits,
target change, cancellation, responsibility transfer, deletion, paired reassignment and audit
rollback with real PostgreSQL. The Bank increment owns joint Bank effects.

Personal and Bank collection position revisions start at zero before first insertion. A new Bank
collection provision checks the echoed position revision before entering the Bank queue. A matching
replay can recover the recorded result even after the revision advances. Contact and comment use an
information revision separate from target, lifecycle and responsibility. The shared order row stabilizes
money effects and the current write grants. Handover checks its holder and target observations.
A possible terminal transition takes the existing priority queue guard before scoped order rows.
Paired writes acquire the required account identity guards, the shared hierarchy guard, then both
order rows in PostgreSQL ID order. Each write rechecks both orders' current authority, eligibility
and relevant observations under the locks. Terms, holder revisions, lifecycle consequences and
audit commit in one transaction.

### REQ-ORDERS-067 — Few contextual actions provide truthful live feedback

The common overview and central detail remain the workspace. Creation requires requester unit,
responsible Profit unit, contact and target. The creation form numbers the order section and the
separate optional comment section. The comment shares the localized placeholder of the material
and item order forms and shows a live character count and its 1000-character limit. The sticky
action bar shows the exact localized target and the selected requesting unit as they are entered.
Preselect the canonical Profit area as the responsible unit using the backend projection in
REQ-ORDERS-053. The creator may choose another eligible unit. An invalid submission preserves that
choice. Holders, recipients, commitments and Bank details can follow later. Show only kind-relevant
inputs. After create, open the order detail and retain the list/filter return context.
Use the shared create transport in REQ-FE-006. AJAX submits the form and follows the server's
detail target. A native form submission redirects to the same detail.
An invalid native form returns the entered fields without writing. A backend create attempt that
requires reauthentication uses the existing session recovery.

The ordinary money entry points are provision and movement, with correction on a history entry.
The movement's purpose reveals only needed fields for collection, handover, return, release,
shortage, beneficiary payment or cross-order purpose change. Suggest one's own holder and existing
parties. Permit new parties inline under the agreed grants. Explicitly distinguish selected accounts from free-text
holders. No prior holder directory, setup wizard, collection-account prerequisite or extra
exception workflow is required.

Before one save show net principal, source charge, fee and payer, recipient/purpose and important
derived consequences. Additional provision is an explicit choice. Changed relevant basis/rate
must not be silently substituted after preview. Each new monetary operation starts with Bank
handling enabled and shows the authorised Bank fields and whether it posts directly or waits.
Bank-free handling requires a conscious selection for that operation; do not carry a previous
opt-out forward as the next operation's default. Missing Bank data, denied authority, unavailable
Bank handling or a refused attempt never silently falls back to Bank-free booking. Existing Bank
funding approvals and all other Bank controls remain binding.

This is the existing per-operation choice, not a persistent operating mode of the order or another
mode-switch workflow. Retry and outcome recovery retain the recorded operation's terms under
REQ-ORDERS-065; a new default does not reinterpret a submitted operation or replace its attempt.

Use the existing live-update/section framework for own and peer changes across every authorised
audience. Preserve dirty input in the open form while refreshing server basis/other sections.
An open commitment history stays expanded when a refreshed participation section still contains
the same commitment. Its entries and links come from the refreshed section.
An open Bank movement keeps the selected account and custodians while its options refresh.
Check the choices against the new options and obtain a fresh preview before saving.
The status badge follows the latest lifecycle revision delivered with the money or header fragment,
including while the money dialog is open.
Show relevant changes/conflicts; do not overwrite input or automatically rebook. There is no
persistent unsent-draft manager. Submitted-operation recovery under REQ-ORDERS-065 is durable
independently of this draft scope. No ordinary edit triggers a full-page success reload.

Remaining money, Bank waiting, clarification and invalid responsibility remain visible even after
completion. Invalid responsibility exposes only reading/transfer actions. Notifications follow
the separate [order notice policy](orders-notifications.md); routine movements do not notify
every participant.

**Acceptance**

- [ ] Create without holder, Bank or collection account reaches detail with the required minimal fields.
- [ ] A fresh aUEC form preselects the canonical active Profit area when available. A submitted
  eligible alternative remains selected after validation errors. Material and item suggestions
  continue to use the generic pair.
- [ ] Provision/movement/correction handles normal and settlement cases without a new process per exception.
- [ ] Preview shows exact cost and optional funding before one save; every new monetary operation defaults to Bank handling.
- [ ] Bank-free handling is explicitly selected per operation, remains fully usable without Bank prerequisites, and does not become a remembered default.
- [ ] Missing or denied Bank handling does not book Bank-free effects; retry/recovery preserves the existing submitted terms.
- [ ] A peer change and a real 409 preserve an open input; unrelated sections continue refreshing.
- [ ] All admitted requester/helper/holder/payee rooms receive permitted refreshes, with current access checks.
- [ ] A completed order with residual money or Bank clarification still shows those facts.

**Enforced by:** `JobOrderMoneyE2eTest` contains Bank-free recovery, live draft, holder maintenance,
recipient correction and reassignment journeys. The Bank-default and coupled result cases belong
to the Bank integration acceptance.

The workspace exposes the minimal aUEC creation form, booked and available holdings, and the
per-operation Bank choice. Account-only deposits, authorisation and delivery use the ordinary
provision and movement forms. Settlement uses the same movement dialog. A factual correction or
an eligible repair starts from history and previews the affected money before saving. Identity
maintenance uses the relevant holder or recipient row.

### REQ-ORDERS-068 — Both operating modes have verifiable end-to-end evidence

The following examples and the criteria of each requirement are the acceptance contract.
The repository's Gradle, database, security, contract and multi-user browser checks verify the
implemented paths. Audit and bounded observability have their own canonical specifications.

#### Required fee-free 10-million example

All amounts below are millions of aUEC, using a zero configured fee rate for this arithmetic
fixture only. C is a collection holder; R is a designated requester recipient. Target is 10.

| Effective action | A held | B held | C held | Provided | Net handed over | Remaining |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Create | 0 | 0 | 0 | 0 | 0 | 10 |
| A provides 4 | 4 | 0 | 0 | 4 | 0 | 10 |
| B provides 6 | 4 | 6 | 0 | 10 | 0 | 10 |
| A internally transfers 4 to C | 0 | 6 | 4 | 10 | 0 | 10 |
| B internally transfers 6 to C | 0 | 0 | 10 | 10 | 0 | 10 |
| C hands over 3 to R | 0 | 0 | 7 | 10 | 3 | 7 |
| C hands over 7 to R | 0 | 0 | 0 | 10 | 10 | 0 |

The order is IN_PROGRESS after first provision and completes only in the last row. R's received
10 is requester money, not remaining procurement stock. Direct A/B handovers work equally without
central collection.

#### Required nonzero-fee reconciliation

At 0.5%, A/B first provide 4/6 million principal. Explicit extra means cover each actual fee:
A→C needs 20,000, B→C 30,000, C→R 3 million needs 15,000, and C→R 7 million needs 35,000.
After all four payments, provided is 10,100,000, net handed over is 10,000,000, order-funded costs
are 100,000 and holdings are zero. Each fee amount is new provision only when actually explicitly
provided, not again when the Bank also records that payment. KRT-funded Bank custody transfer
changes the fee payer as specified by the integration spec; it does not require these fee top-ups
from the order.

**Acceptance**

- [x] Run every fee-free row and the nonzero-fee reconciliation with exact totals and no double credit.
- [x] Run the complete no-Bank journey with explicit Bank-free selection at every new monetary operation, without Bank accounts, holders, roles, requests or service prerequisites.
- [x] Run the selected-Bank matrix in REQ-ORDERS-069…076, including actual payment with a rejected wrong-account request and a corrected retry without another game transfer.
- [x] Cover underfunding, multiple orders/holders, returns, overpayment, cancellation, corrections, accountless identity and unauthorised access.
- [x] Verify mutation audit, grouped notices and peer updates against the same operation cause.

**Enforced by:** `JobOrderMoneyIntegrationTest.collectsTenMillionAndCompletesOnlyOnTheLastRequesterHandover`
and `fourFeeFundedTransfersReconcileExactly` passed on PostgreSQL at `be43dcd29`. They verify
each fee-free row and the full 100,000-aUEC fee reconciliation. Both examples check the fixture's
Bank footprint before creation and after completion, including replay in the configured-fee example.
`JobOrderMoneyE2eTest.contextualWriterCompletesWithoutBankOrCentralCollection` exercises separate
holders, a named accountless holder, live peer completion with its draft open, and recovery of
a committed handover after a lost response. The recovered handover yields one audit entry and
one affected-reader notice. The actor needs no Bank staff grant or Bank prerequisite for this
order. REQ-ORDERS-061 and REQ-ORDERS-062 list the settlement and correction evidence.
REQ-ORDERS-069…076 define the selected-Bank acceptance matrix.

Full App CI at `be43dcd29` passed the `JobOrderMoneyIntegrationTest` cases for underfunding
(`onlyExplicitAuthorisedProvisionCanFillTheDeficitInTheSameOperation`), cross-order movements
(`inversePurposeMovesAcquireBothOrderRowsInPostgresOrder`), requester returns and cancellation
(`requesterReturnReopensCompletedOrderAndFundsItsFeeSeparately`,
`actualReturnOnACancelledOrderPreservesCancellation`), overpayment and corrections
(`sameHolderPurposeHandoverRequiresExplicitPurposeAndKeepsOverpaymentSeparate`,
`connectedCorrectionPreservesOriginalFactsAndAppliesOnce`), and authority
(`onlyResponsibleWriterCanRecordNamedAccountlessProvision`,
`explicitlyLinkedUnitlessHolderCanReadOnlyTheirOrderAcrossWorkspace`).
[Hosted App CI](https://github.com/krt-profit/basetool/actions/runs/43098702371)

The browser run at `d7ec1198b` passed the selected-Kartellbank and explicitly Bank-free journeys
in all six Chromium, Firefox and WebKit desktop/mobile profiles. The isolated Chromium run at
`be43dcd29` passed all 29 selected cases, including both aUEC modes and existing Bank, MATERIAL
and ITEM journeys. The selected-Bank journey repairs the rejected wrong-account request under
the same operation identity. REQ-ORDERS-076 records the database, audit, grouped notice and
draft preservation evidence.
[Hosted E2E](https://github.com/krt-profit/basetool/actions/runs/43087989837)

## Out of scope

Automatic in-game payments, full-wallet verification, global cross-order coverage, mixed-kind
orders, MATERIAL/ITEM money management, new notification channels, durable unsent drafts,
implementation tasks, schema/code changes and deployment. General Bank policy belongs to
[bank.md](bank.md); the optional coupling belongs to its dedicated integration home.

## Open questions

None of the settled business choices is deferred to implementation. A newly discovered business
choice must be raised through the planning map rather than invented during coding.
