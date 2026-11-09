> **Doc type:** Living spec - approved target. Last reviewed: 2026-11-09.
> **Owner area:** ORDERS / BANK
> **Status:** Accepted / planned; not implemented. All acceptance criteria below are pending.
> **Related ADRs:** [ADR-0357](../adr/0357-optional-auec-bank-effects-use-a-thin-composition-adapter.md),
> [ADR-0358](../adr/0358-one-configured-transfer-fee-policy-serves-bank-and-orders.md),
> ADR-0232, ADR-0251, ADR-0254, ADR-0352, ADR-0353.

# Optional Kartellbank use for aUEC job orders

## Canonical boundary

This is the canonical home of the order meaning of optional Bank use: economic mapping, delayed
order effects, attempts, recovery and reversal consequences. The core monetary model lives in
[orders-auec.md](orders-auec.md); shared responsibility, participation and invalid-responsibility
rules live in [orders-core.md](orders-core.md). Reusable Bank authority, request, ledger and fee
contracts remain in [bank.md](bank.md). Their requirements are linked here rather than redefined.

This specification records the approved planning outcome. It changes no running application,
schema or production data and authorises no implementation. The connected-client Bank surface in
[bank-exchange.md](bank-exchange.md) remains a separate frozen contract.

## Requirements

### REQ-ORDERS-069 - Optional Bank use preserves the complete independent order flow

Every aUEC order can be created, supplied, held, collected, handed over, corrected and closed without
Bank accounts, roles, custodians, requests or transactions. Choosing Bank use for one operation
creates only that operation's explicit coupling; order progress, responsibility and completion do
not automatically post to the Bank.

Bank use may accompany new provision during procurement, use of existing Bank funds, internal
collection, requester handover and returns. The user selects it in the corresponding ordinary order
action. Reuse its amount, actual parties and purpose without a second entry workflow. Bank staff
book directly only under the applicable Bank authority; other actors raise a permitted request.
There is no required retrospective-linking workflow for arbitrary old transactions.

The actual economic boundary selects the representation:

| Economic change | Optional Bank representation | Order effect |
| --- | --- | --- |
| Money first enters Bank custody | DEPOSIT to the selected account and receiving registered custodian | Provision only on first attribution to this order |
| Money leaves Bank custody | WITHDRAWAL from the selected account and paying custodian to the actual counterparty | The provision, movement, handover or return the operation represents |
| Organisational account ownership changes | TRANSFER between the selected accounts with actual custodians | Reallocation or designated requester handover/return; no repeat procurement |
| Custody changes between Bank custodians, principal account ownership unchanged | HOLDER_TRANSFER under REQ-BANK-031 | Collection/holder change; no repeat provision or fulfilment |
| Existing Bank funds are authorised for this order without an account/custody movement | Successful authorisation under REQ-BANK-022/-023/-024, without a ledger transaction | First authorised order provision once |
| Both sides remain outside Bank custody | No Bank posting | The independent order action |

Entering or leaving the Bank does not repeat an already recorded provision or unit contribution.
Four million newly contributed and six million first provided from existing Bank funds is ten
million provision, regardless of how many related Bank records explain it.

**Acceptance**

- [ ] A complete independent journey, including accountless holders and split handovers, needs no Bank object or right.
- [ ] Early deposit, existing-fund provision, collection, delivery and return each have an explicit ordinary-action Bank choice.
- [ ] The same provision is not counted again when later deposited, withdrawn or transferred in the Bank.
- [ ] Ordinary progress/completion creates no implicit Bank transaction.

### REQ-ORDERS-070 - Existing Bank funds require debit-equivalent authority before availability

An order may use existing active accounts. Suggest the affected unit's account, such as the providing
Profit unit for an early deposit or the requesting unit for delivery. Explicit alternatives must
pass the Bank's rules. Do not create an account for each order.

Existing funds may remain on the same account and with the same registered custodian. Record the
funding account and actual custodian as provenance, not a per-account custodian balance. Bank
custody remains the independent global dimension of REQ-BANK-003/-006.

The Bank's authorisation-only contract in REQ-BANK-022/-023/-024 applies before provision becomes
available. Account selection or order write rights do not authorise spending. A pending account-owner
approval is insufficient. At final Bank confirmation or authorised direct action, the single funding
account must cover the amount made available. Ten million cannot be provided from six million.

This check creates no Bank reservation, aggregate allocation pool or guarantee against later
independent Bank dispositions. It does not sum other orders' earmarks. A known subsequent shortage
uses the ordinary holding correction/real-shortage rules. The first successful provision counts
once even though the Bank ledger remains unchanged.

**Acceptance**

- [ ] Unauthorised or merely pending Bank funds are unavailable to the order.
- [ ] Final authorisation of ten million from a six-million account is refused without an order effect.
- [ ] Successful authorisation leaves Bank account/custody totals unchanged, stores its traceable outcome and credits provision once.
- [ ] Other orders' allocations neither reserve nor reduce the Bank account balance used by this check.

### REQ-ORDERS-071 - Bank parties and requester destinations are explicit

Keep the authenticated requester/recorder, actual depositor/payee, organisational account and actual
Bank custodian distinct. An order holder is not automatically a Bank custodian. Accountless Kartell
members remain usable order holders and counterparties through the agreed authorised writers;
Bank custodians retain their registered-user prerequisite. A transfer out of Bank custody to an
unregistered order holder is a withdrawal to a counterparty, not a Bank-holder transfer.

Order-originated deposit requests preserve the actual depositor, including a named accountless
member, separately from their authenticated requester. An explicit account/identity selection is
required; names alone do not establish identity, custody or a fee exemption.

Authorised writers on either the requesting or responsible side may designate an existing Bank
account as a requester handover target, subject to Bank access. Executed credit to that designated
account is a handover without a later mandatory payout to a player. Money credited there ceases to
be procurement holdings. An arbitrary deposit on another account does not fulfil the order.

**Acceptance**

- [ ] Recording an accountless member's deposit through another account retains both the real depositor and authenticated requester.
- [ ] Same-custodian transfer of ten million from the Profit account to the designated requester account can deliver ten million without a game transfer or later player payout.
- [ ] A request or account-owner approval alone does not credit delivery.
- [ ] A matching name neither creates a Bank custodian nor proves a no-transfer purpose change.

### REQ-ORDERS-072 - One real fee has one funding source and a preserved calculation

Use the shared configured fee policy of REQ-BANK-033 and ADR-0358. Submitted coupled terms retain
its rate snapshot and whole-aUEC HALF_UP fee; Bank and order use the same basis. No manual fee
amount/rate override is introduced. Historical corrections use the historical basis; a new actual
payment uses the rate applicable to that new operation.

For ordinary source-order-funded transfers, the source order holding covers recipient principal
plus the fee under the core aUEC rules. Explicit genuinely additional fee provision may be part of
the same permitted action.

For Bank HOLDER_TRANSFER, REQ-BANK-031 continues to charge CARTEL. The order source loses principal
only, the destination gains principal, and the KRT-funded fee is visible separately. It neither
requires another order fee provision nor counts as procurement nor consumes order stock twice.
For four million at an illustrative 0.5%, order holdings move A=4/C=0 to A=0/C=4; the Bank charges
20,000 to CARTEL and A's global custody. This is one game cost represented in both views.

A true purpose change at the same actual player without a game transfer is fee-free in the order
and its Bank representation, including the scoped withdrawal exemption and a same-custodian
account transfer. Account permissions and approvals remain required. This classification is not
an editable fee override.

**Acceptance**

- [ ] A later configured rate does not reprice a submitted coupled attempt or its ordinary retry.
- [ ] A KRT-funded holder-transfer fee changes neither principal provision nor order holdings beyond principal.
- [ ] Source-order-funded and KRT-funded expenses reconcile separately.
- [ ] A proven same-player purpose change charges zero while retaining debit-equivalent authority.

### REQ-ORDERS-073 - Bank waiting protects local availability without fabricating financial effects

An independent order action is immediately effective. When Bank coupling is selected, its monetary
order effect waits for final Bank confirmation, even if the game payment has already happened.
Before that point, it credits no provision, recipient holding, unit contribution or requester
handover.

Document known actual payment facts and parties immediately. An unchanged booked source holding
must not imply that spent money remains physically present. Make affected money unavailable for
further spending within the order, at source and recipient. A pending new provision is not already
booked stock; a binding against booked holdings can use only existing source funds. Include
order-funded fees, but do not additionally bind KRT-funded fees from order holdings.

This is operation-local availability protection. It creates neither global wallet/Bank coverage nor
a new planned/sent/received transfer workflow or main JobOrder status. A successful HTTP call may
have raised a pending request instead of posting: only the authoritative outcome determines effect.

**Acceptance**

- [ ] A known actual coupled payment is visible before confirmation but cannot be spent again at either side.
- [ ] Pending contribution/delivery affects neither fulfilment nor unit contribution credit.
- [ ] Combined pending provision plus movement does not create a negative fictional source binding.
- [ ] An accepted pending result, including the existing KRT over-limit direct-to-request path, is not treated as booked money.

### REQ-ORDERS-074 - Bank and order share one effective operation outcome

Use the stable operation, immutable submitted terms, correction lineage and safe replay contract of
[orders-auec.md](orders-auec.md). Several Bank attempts belong to that same logical payment.
Duplicate clicks, lost responses, reloads and retries cannot create another operation, Bank posting,
fee, provision, contribution credit or handover. A known successful replay returns its authorised
recorded result; incompatible reuse of the same submitted identity is a conflict.

Bank confirmation and all matching order effects commit in one database transaction or remain
monetarily unchanged together. The owning JobOrder command checks the active attempt, frozen terms,
current valid responsibility and permitted lifecycle effect. A refused order effect, including
invalid responsibility, rolls back the Bank decision and Ledger effect. Bank outcome authority
confers no general order-edit rights. Missing/ambiguous required outcome handling fails closed.

Retries of an unknown outcome inspect/continue the existing operation. An eligible replacement for
a rejected/cancelled attempt retains history and prevents the superseded attempt from posting.
Correcting an erroneous account or reported amount preserves old/new facts and required approvals;
it does not repeat the actual game payment. Submitted linked terms cannot be silently changed in
the generic Bank edit surface.

A real payment may be recorded without Bank participation only when factually appropriate and
authorised. Removing the Bank option cannot bypass approval of existing Bank funds or substitute
for the missing executed credit to a designated requester Bank account.

There is no partial confirmation inside a Bank request. If only three of a requested ten million
was actually paid, correct/replace that attempt to represent the actual three and its fee coherently.
The other seven remains demand; no automatic request is created. A later real instalment has its
own complete operation/request.

**Acceptance**

- [ ] Duplicate/replacement attempts cannot create a second effective Bank ledger or order payment.
- [ ] Failure of either domain or its audit rolls back the coupled decision without pretending to undo a real game payment.
- [ ] Invalid responsibility blocks coupled effects until the permitted responsibility transfer; no stale Bank callback bypasses it.
- [ ] Wrong-account repair and a partial actual payment require neither another game payment nor a misleading full confirmation.
- [ ] Unknown-result/reload recovery preserves the operation identity and permission checks.

### REQ-ORDERS-075 - Failure, cancellation and reversal preserve actual money facts

Rejecting/cancelling an attempt before any game payment creates no monetary booking and permits
release of its local binding. After an actual payment, keep its destination/facts visible and money
unavailable pending the normal correction or suitable replacement attempt. Do not invent a refund,
free source money or another game payment.

Completion/cancellation stops unexecuted ordinary procurement intentions. Actual payments and
necessary correction/settlement facts may still be recorded under valid responsibility and their
agreed rights. Cancellation of a pending attempt that wins a race prevents its later posting; a
confirmation that wins retains its effect. Order cancellation alone is not a ban on legitimate
cleanup, and cleanup never automatically reactivates the cancelled order.

For a pure Bank account/purpose posting actually reversed in the ledger, reverse its linked order effect
atomically and coherently with the Bank reversal. Preserve history and nonnegative holdings;
connected corrections may be necessary. Reversing a same-custodian requester handover restores the
order funds at that holder and removes the handover. Recalculate completion under the current
target; a cancelled order stays cancelled.

A no-ledger authorisation has no ledger reversal. Its correction or release uses the ordinary
authorised actions; no separate revocation queue or workflow is introduced.

For reversal of a Bank record linked to an actual player payment, retain effective order amounts
until a targeted authorised correction establishes the correct facts. Add the visible clarification
marker; a completed order may remain completed with that marker. Neither game principal nor a
consumed game fee is refunded by a bookkeeping reversal. Preserve the distinction between actual
KRT-funded cost and its Bank correction. Use ordinary correction/return actions, not another queue
or settlement workflow.

**Acceptance**

- [ ] A failed attempt after actual payment does not free source money or repeat the payment.
- [ ] Confirmation/cancellation/replacement races have exactly one effective result.
- [ ] Pure Bank-only handover reversal restores the same holder and reopens only when appropriate; cancellation is never automatically undone.
- [ ] Real-payment Bank reversal flags clarification while retaining posted order amounts until explicit correction.
- [ ] Cleanup of a cancelled order respects both its limited scope and the strict invalid-responsibility gate.

### REQ-ORDERS-076 - Integration preserves access, evidence and external boundaries

Order readers receive only the agreed order financial facts and fee attribution. Bank linkage does
not expose global custody, additional account balances, private staff/approval notes, unrelated
transactions or the Admin audit. Current authorisation governs outcome lookup, replay and deep links;
knowledge of an operation/request identifier is not access.

Maintain the owning-domain synchronous audit, participant-readable history, scoped versions,
post-commit live updates and grouped notification causes under the core specifications. The original
requester is not the actor of a later Bank staff decision. Existing Bank tasks, mute/priority rules
and private-note boundaries survive; no duplicate success notice bypasses a muted outcome.

Keep the separate Bank exchange contract unchanged: no new connected-client purpose request,
confirmation, booking or wider directory/allocation capability follows from this internal feature.
Existing connected-client idempotency is not automatic protection of an order operation. Published
request/queue projections need compatibility evidence for new internal meanings without changing
frozen wire shapes or exposing additional data.

Later implementation evidence includes real PostgreSQL financial/Bank/hierarchy races, coupled
rollback after domain/audit failures, repeated/mismatched submissions, correct pending availability,
rate snapshots and fee funding, both reversal modes, no-Bank journeys and selected-Bank journeys.
Bounded metrics cover waiting age/count, clarification, technical failure and integrity contradictions
without player/order/account/operation IDs, names or transaction amounts as metric labels. Legitimate
approval waiting is not itself a new deadline or technical failure.

**Acceptance**

- [ ] Order views, history, links and outcomes expose no additional Bank data or private approval note.
- [ ] Audit/live/notice causes explain each effective mutation once and preserve the correct actor and current access.
- [ ] Frozen exchange schema/golden/capability tests retain their public contract for the new internal cases.
- [ ] Appropriate database, security, contract, UI and observability checks cover both operating modes; no unimplemented test is marked as passing.
