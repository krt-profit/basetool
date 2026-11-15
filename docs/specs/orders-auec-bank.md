> **Doc type:** Living spec. Last reviewed: 2026-11-11.
> **Owner area:** ORDERS / BANK
> **Status:** The ordinary order actions use the synchronous Bank integration and its
> [Bank-owned capabilities](bank.md#req-bank-061--purpose-reference). Acceptance evidence is
> recorded against each requirement below.
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

The Bank command, request and ledger capabilities belong to [bank.md](bank.md). The connected-client
Bank surface is governed by the frozen contract in [bank-exchange.md](bank-exchange.md).

## Requirements

### REQ-ORDERS-069 - Optional Bank use preserves the complete independent order flow

Bank handling is preselected for every new monetary operation, so the standard journey uses the
Bank throughout. The complete Bank-free journey remains available through an explicit selection
for each operation, without Bank accounts, roles, custodians, requests or transactions. Creation
still needs only the minimal order fields; no Bank setup is a creation prerequisite. This is a
per-operation choice, not an order-wide operating mode. The selected Bank handling creates only
that operation's coupling; order progress, responsibility and completion do not automatically post
to the Bank. No missing configuration, denied request or unavailable Bank service silently turns
a Bank-enabled operation into a Bank-free one.

Bank use may accompany new provision during procurement, use of existing Bank funds, internal
collection, requester handover and returns. The user selects it in the corresponding ordinary order
action; Bank handling starts enabled, and Bank-free handling must be deliberately chosen.
Reuse its amount, actual parties and purpose without a second entry workflow. Bank staff
book directly only under the applicable Bank authority; other actors raise a permitted request.
There is no required retrospective-linking workflow for arbitrary old transactions.

The actual economic boundary selects the representation:

| Economic change | Optional Bank representation | Order effect |
| --- | --- | --- |
| Money first enters the collection account | DEPOSIT to the selected account, with the registered receiving custodian confirmed in Bank | Provision in the order's collection-account position on first attribution |
| Money leaves Bank custody | WITHDRAWAL from the selected account and paying custodian to the actual counterparty | The provision, movement, handover or return the operation represents |
| Account ownership changes without a game payment | Account-only TRANSFER between the collection account and the designated requester account | Debit the collection position and record net handover, or credit the original position and reduce net handover on a requester return |
| Account ownership changes with a game payment | TRANSFER between the selected accounts with actual custodians | Reallocation or designated requester handover/return, with the actual payment terms |
| Custody changes between Bank custodians, principal account ownership unchanged | HOLDER_TRANSFER under REQ-BANK-031 | Collection/holder change; no repeat provision or fulfilment |
| Existing Bank funds are authorised for this order without an account/custody movement | Successful authorisation under REQ-BANK-022/-023/-024, without a ledger transaction | First authorised order provision once, to the selected account position or explicitly selected player holding |
| Both sides remain outside Bank custody | No Bank posting; explicitly select Bank-free handling | The independent order action under that explicit choice |

An order can use the collection account throughout. A deposit credits the order's position in
that account after Bank confirmation. A subsequent account-only transfer delivers to the
designated requester account. The order records both effects without a personal money holder.
Bank staff select or confirm the actual registered receiving custodian for the deposit.
The account transfer changes account balances and leaves custody and game fees unchanged.
The same collection-account position can receive successfully authorised existing Bank funds.
A requester-account return reverses the direction and requires debit-equivalent authority on the
requester account. Its principal is bounded by the still-unreturned receipt from that particular
collection position, with pending returns deducted. Final confirmation restores that position and
reduces the net handover without new provision or unit credit.
Returns to a personal holding draw only from receipts funded by personal holdings, even when
the same requester designation also received money from a collection account. Each path deducts
its own pending returns from the corresponding receipts.
For a return from a designated Bank account to a personal holding, the ordinary return action
uses that designation's account as the withdrawal source. The action offers the designation to
current order unit writers with Bank access for that withdrawal and an available personal receipt.
The Bank options disclose the source only when it is among that caller's admitted withdrawal
accounts. The general order view checks independent Bank account visibility in the current
active org-unit context (REQ-ORG-032). The same context governs ordinary source-account choices,
previews, new submissions and replacements, including authorisation-only provision. A member
may use their whole Bank reach with no selected unit. Cross-unit deposit and transfer-destination
selection and Bank staff authority follow their own Bank rules.

The order suggests the responsible unit's eligible collection account. An authorised designation
can use the requesting unit's account or another eligible Bank account. The form uses the order's
own position and designation to select the accounts for handover and return, including when the
underlying account identifier is masked. Suggestions contain only permitted references and the
order's position revision. Account releases and shortages use the existing settlement action.

Entering or leaving the Bank does not repeat an already recorded provision or unit contribution.
Four million newly contributed and six million first provided from existing Bank funds is ten
million provision, regardless of how many related Bank records explain it.

**Acceptance**

- [ ] A complete explicitly selected Bank-free journey, including accountless holders and split handovers, needs no Bank object or right.
- [ ] Early deposit, existing-fund provision, collection, delivery and return each have an explicit ordinary-action Bank choice.
- [ ] The same provision is not counted again when later deposited, withdrawn or transferred in the Bank.
- [ ] Ordinary progress/completion creates no implicit Bank transaction.
- [ ] A receipt funded by a collection account is unavailable to a return into a personal holding and remains returnable to its original position.
- [x] Ordinary source accounts outside a selected unit are absent and refused in preview and submit. Permitted cross-unit credit destinations remain selectable and the shared order view masks Bank account identifiers outside the active context.
- [ ] Each new operation preselects Bank handling; an earlier Bank-free choice does not silently carry forward.
- [ ] A denied, incomplete or unavailable Bank path produces no automatic Bank-free effect and does not bypass existing-fund approvals.

**Enforced by:** The independent-path checkpoint runs both worked examples in
`JobOrderMoneyIntegrationTest`. It scopes Bank account, holder, grant, request, transaction,
posting and audit checks to the example actors, units, named parties and order. A contextual
Profit Logistician and an ordinary member exercise the direct-handover path in
`JobOrderMoneyE2eTest.contextualWriterCompletesWithoutBankOrCentralCollection`.
`DomainMapTest`, `ModuleBaselineTest`, `ModularityTest` and `TableOwnershipTest` enforce the
JobOrder module's independent boundary. `JobOrderLinkedBankIntegrationTest.linkedBankFundingHonoursTheActiveOrgUnit`
checks account choices, preview, submission and account visibility through HTTP and PostgreSQL.

### REQ-ORDERS-070 - Existing Bank funds require debit-equivalent authority before availability

An order may use existing active accounts. Suggest the affected unit's account, such as the providing
Profit unit for an early deposit or the requesting unit for delivery. Explicit alternatives must
pass the Bank's rules. Do not create an account for each order.

Existing funds may remain in the selected Bank account. Account-only authorisation records the
order's position in that account. An explicitly selected player holding retains the actual
registered custodian as provenance. Bank custody remains the independent global dimension of
REQ-BANK-003/-006.

The Bank's authorisation-only contract in REQ-BANK-022/-023/-024 applies before provision becomes
available. Account selection or order write rights do not authorise spending. A pending account-owner
approval is insufficient. At final Bank confirmation or authorised direct action, the single funding
account must cover the amount made available. Ten million cannot be provided from six million.

This check creates no Bank reservation, aggregate allocation pool or guarantee against later
independent Bank dispositions. It does not sum other orders' earmarks. A known subsequent shortage
uses the ordinary holding correction or real-shortage rules. A loss or release against an order's
collection position changes its purpose balance without a Bank posting or a change to its original
provision or contribution. The first successful provision counts once even though the Bank ledger
remains unchanged. A factual correction cannot make more Bank-funded principal available than the
effective authorisation or the first-provision Bank withdrawal approved.

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
- [ ] Multiple actual deposits using different registered Bank custodians credit the same order collection-account position without creating a personal order holder.
- [ ] An account-only transfer of ten million from the collection account to the designated requester account delivers ten million with two account postings and zero custody change.
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

When an actual Bank withdrawal first provides funds to a player, the receiving principal is the
new order provision and the Bank funding account bears the separate fee. At 0.5%, a 4,000,000
aUEC payment requires 4,020,000 aUEC of Bank coverage. The order credits 4,000,000 aUEC as
available provision and eligible unit credit. Its history shows the 20,000 aUEC Bank-funded fee
separately. The Bank applies its established withdrawal authority and approval limits.

A true purpose change at the same actual player without a game transfer is fee-free in the order
and its Bank representation, including the scoped withdrawal exemption and a same-custodian
account transfer. Account permissions and approvals remain required. This classification is not
an editable fee override.

**Acceptance**

- [ ] A later configured rate does not reprice a submitted coupled attempt or its ordinary retry.
- [ ] A KRT-funded holder-transfer fee changes neither principal provision nor order holdings beyond principal.
- [ ] Source-order-funded and KRT-funded expenses reconcile separately.
- [ ] A first-provision Bank withdrawal credits 4,000,000 aUEC and displays its separate 20,000 aUEC Bank-account-funded fee after successful debit coverage of 4,020,000 aUEC.
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
the generic Bank edit surface. A replacement of a failed linked deposit retains the exact earlier
Bank depositor snapshot, including after handle erasure or account deletion. The current Bank
receiving-custodian and admission checks still apply.

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
connected corrections may be necessary. Reversing a requester handover restores order funds to
the original holder or collection position and removes the handover. An effective or pending
account return must remain covered by its original collection-position receipt. Reversing the
account return removes the returned amount from that position and restores delivery. Recalculate
completion under the current target; a cancelled order stays cancelled.

A no-ledger authorisation has no ledger reversal. Its correction or release uses the ordinary
authorised actions; no separate revocation queue or workflow is introduced.

**Patch change (amendment, [REQ-PATCH-043](patch-change.md#req-patch-043--waiting-order-bank-attempts-from-before-the-live-time-are-cancelled)).**
A patch change's currency wipe cancels every attempt waiting since before its live time,
together with its pending Bank request, with the patch change as the reason. The cancellation
follows this requirement: it creates no booking and releases the attempt's local binding,
while an attempt with a reported actual payment keeps its money unavailable until a correction
or a replacement. It runs whatever the order's status and responsibility, and the attempt
history shows the patch label.

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

The order retains each submitted attempt and its decision. The same operation identity can
recover the active result. A replacement uses the previous request's retained fee basis and
keeps the old revision visible in the order's linked attempt history. The repair editor previews
the corrected amount and its fee at the retained rate before submission. It sends the failed
revision and the deliberate account correction, if any. The server retains the parties and
custody coordinates and applies current Bank admission to the replacement. Recovery recognises
the acknowledged replacement revision before clearing its pending submission.
Booked holdings and usable
holdings are separate figures. A Bank account designation carries its account identifier only
for an actor who can currently view that account in the Bank.

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

Required verification includes real PostgreSQL financial/Bank/hierarchy races, coupled
rollback after domain/audit failures, repeated/mismatched submissions, correct pending availability,
rate snapshots and fee funding, both reversal modes, no-Bank journeys and selected-Bank journeys.
Bounded metrics cover waiting age/count, clarification, technical failure and integrity contradictions
without player/order/account/operation IDs, names or transaction amounts as metric labels. Legitimate
approval waiting is not itself a new deadline or technical failure.

**Acceptance**

- [x] Order views, history, links and outcomes expose no additional Bank data or private approval note.
- [x] Audit/live/notice causes explain each effective mutation once and preserve the correct actor and current access.
- [x] Frozen exchange schema/golden/capability tests retain their public contract for the new internal cases.
- [x] Appropriate database, security, contract, UI and observability checks cover both operating modes; no unimplemented test is marked as passing.

**Evidence**

- `JobOrderBankMoneyE2eTest.creditsAndReturnsThroughAccountPositions` covers the default Bank
  selection, recovery and wrong-account repair, linked approval and delivery, return to the
  collection position and private note exclusion. It passed in all six browser profiles at
  `d7ec1198b`. [Hosted E2E](https://github.com/krt-profit/basetool/actions/runs/43087989837)
- Full App CI passed at `be43dcd29`. The focused PostgreSQL run at that revision passed all 19
  cases in `JobOrderLinkedBankIntegrationTest` and all five in
  `JobOrderLinkedBankSettlementIntegrationTest`. These cases exercise coupled audit rollback,
  cancellation and hierarchy races, rejected-payment binding, fee attribution and both reversal
  modes. [Hosted App CI](https://github.com/krt-profit/basetool/actions/runs/43098702371)
- The final focused review run passed 115 backend and 80 frontend cases. Its PostgreSQL tests
  verify that a new account provision checks the collection position version, an exact replay
  remains recoverable, and an account receipt cannot fund a return into a personal holding.
  The privacy cases verify that only the current corrected designation can be used for returns.
  The Exchange wire contract and OpenAPI generation checks passed in the same run.
- `BankLinkedPaymentsIntegrationTest` verifies the ten-million authorisation against six
  million of coverage, applicant and accountless depositor attribution, balanced account-only
  transfers, and the retained KRT-funded holder transfer fee. `JobOrderLinkedBankIntegrationTest`
  verifies pending funds, failed-payment repair, confirmation races against cancellation
  and hierarchy changes, and the treatment of a real payment when its ledger entry is reversed.
- Both `JobOrderLinkedBankIntegrationTest.linkedAuthorisationGroupsOutcomeAndRetainsApprovalTasks`
  variants passed in that PostgreSQL run. They verify the staff audit actor, approval task
  lifecycle, one Order credit without a Bank posting, one preferred confirmation when unmuted
  and suppression of the fallback when muted. The expanded
  `JobOrderBankMoneyE2eTest.creditsAndReturnsThroughAccountPositions` passed in a separate
  isolated Chromium run. It verifies that a staff confirmation refreshes the Order while
  preserving the next deposit's amount, account and Bank choices in the writer's open draft.
- `BankLinkedPaymentsIntegrationTest.ordinaryMembershipCannotAuthoriseFundsFromAHiddenAccount`
  verifies hidden-account admission. In `JobOrderMoneyPrivacyIntegrationTest`,
  `correctingAFalseHolderLinkRevokesReadershipAndRetainsTheHandover` and
  `correctingDirectRequesterDesignationRevokesFalseHistoricalReadGrant` verify revocation
  after correction. The browser journey verifies private-note exclusion from Order history.
- Against main at `34bc84efb`, the published Exchange OpenAPI and schemas are byte-identical. The
  generated internal relay document differs only by the shared `ProblemDetail.x-problem-codes`
  extension entry `MONEY_PROVISION_REFUSED`. This preserves the Exchange runtime vocabulary,
  endpoints and wire shapes. See [REQ-XCH-039](external-exchange.md#req-xch-039--the-relay-surface-has-its-own-internal-openapi-document)
  and [REQ-API-019](api-conventions.md#req-api-019--every-problem-code-is-registered-once-and-documented).
  Hosted App CI at `be43dcd29` passed `ExchangeWireContractTest`, `ExchangeGoldenAnswerTest`,
  `ExchangeRoutesContractTest` and the Bank Exchange request scope tests.
  [Hosted App CI](https://github.com/krt-profit/basetool/actions/runs/43098702371)
- The linked-money dashboard signals and passing contradiction alert test are recorded in
  [REQ-OBS-011](observability.md#req-obs-011--business-metrics-basetool_).
- The integrated S15 tree passed the Gradle wrapper's `spotlessApply build --continue` against the
  published `v1.23.4` release. Of that release's contracts only the exchange schemas have a
  previous-release comparison
  ([REQ-XCH-026](external-exchange.md#req-xch-026--the-contract-grows-additively-under-exchangev1));
  the backend's OpenAPI documents have none since ADR-0269. The isolated Chromium runs cover both
  operating modes and the expanded Bank draft assertion.
