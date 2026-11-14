> **Doc type:** Living spec. Last reviewed: 2026-11-11.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0356, ADR-0359, ADR-0360

# Shared JobOrder model

## Context & goal

MATERIAL, ITEM and aUEC procurement use one Auftragssystem. This is the canonical shared
organisation, participation, access and lifecycle contract. Accepted Wayfinder decisions
[responsibility](https://github.com/krt-profit/basetool/issues/3193),
[access](https://github.com/krt-profit/basetool/issues/3197) and
[shared scope](https://github.com/krt-profit/basetool/issues/3200) replace conflicting legacy
rules. Money operations follow the aUEC specs.

Kind-specific homes remain [production](orders-item-production.md),
[material promises](orders-material-claims.md), [money](orders-auec.md) and
[optional Bank integration](orders-auec-bank.md).

## Requirements

### REQ-ORDERS-049 — Every order has exactly one kind

An order is exclusively MATERIAL, ITEM or aUEC procurement. Material/item demand and money demand
cannot be mixed. aUEC holdings, financial promises, recipients, movements and optional order/Bank
coupling belong only to aUEC orders. Common organisation, participation, reading, unit authority,
navigation and invalid-hierarchy handling apply to all three kinds.

**Acceptance**

- [ ] Each kind exposes only its applicable demand and processing actions.

**Enforced by:** `JobOrderMoneyIntegrationTest` and `ExchangeDemandDetailsControllerTest` cover
the aUEC kind, stock-kind gates and external demand exclusion. **Issues:** #3200, #3203

The initial aUEC kind can be created through the same unit writer gate and appears in the common
workspace. Its stock-facing writes and the Inventory, Supply and external Exchange stock pickers
exclude it. Use [the money contract](orders-auec.md) for its separate provision and target scope.

### REQ-ORDERS-050 — Responsibility follows the actual Profit hierarchy

MATERIAL and ITEM use the designated Profit hierarchy for current responsibility. Current writers
of either proposed unit can create an order. A valid transfer takes effect immediately and preserves
observed unit relationships, booked work and the original Profit boundary when it is known.
Responsibility, lifecycle, need, participation and personal note versions belong to their respective
edits.

The Kartell has exactly one Profit Bereich, identified by the organisational domain identity
Department.PROFIT, not a display name or an independent eligibility flag. Enforce that there
cannot be a second Profit designation. The active Profit Bereich itself and its active subordinate
Staffeln and Spezialkommandos are eligible responsible units. Retire isProfitEligible as an
independent eligibility/access setting for Staffeln and SKs throughout the Auftragssystem.

The Profit Bereich may be directly responsible; it is not merely an intake address. A writer
of the current responsible unit may transfer responsibility immediately and unilaterally to a
valid target within the order's original Profit hierarchy boundary. No target acceptance is
required. The same order, requester, number and complete provenance remain. A transfer does not
itself move stock, holdings or payments. The requesting OrgUnit is a separate customer and need
not belong to Profit.

**Acceptance**

- [ ] Profit Bereich, its active Staffel and SK can each be directly responsible; an independent
  flag cannot admit an outside unit or exclude a unit within the actual eligible hierarchy.
- [ ] Reassignment takes effect without target approval and retains requester/history.

**Enforced by:** The MATERIAL/ITEM foundation is covered by
`JobOrderHierarchyAuthorityIntegrationTest`, `ProfitHierarchyIntegrationTest`,
`ProfitHierarchyWriteRaceIntegrationTest`, `ProfitHierarchyStartupIntegrationTest`,
`ProfitHierarchyNoticeIntegrationTest` and `V379ProfitHierarchyMigrationTest`.
The aUEC workflow follows its own implementation slice.
**Issues:** #3193, #3197, #3200

### REQ-ORDERS-051 — One Staffel participation carries support and promises

An eligible Profit Staffel may support an order responsible to the Bereich, an SK or another
Staffel. At most one active participation per order/Staffel exists. Its optional material
promises (REQ-ORDERS-024) or aUEC promise belong to that participation; creating a first promise
also enrolls it. There is no parallel helper registration. Multiple Staffeln may participate.

Responsible-unit writers may enroll/remove supporting Staffeln; writers of a supporting Staffel
may enroll/remove their own Staffel and maintain its own promises. A responsible Staffel cannot
also support itself. Eligible Staffeln can enroll while the order is active. Withdrawal uses the
observed participation version. Making a supporter responsible removes it from active support while
preserving its contribution lines and history under its responsibility. This role transition is
not a withdrawal of its contributions. The former responsible Staffel is not automatically enrolled
as a supporter. The effect on its outstanding money commitment follows
[REQ-ORDERS-059](orders-auec.md#req-orders-059--unit-commitments-are-planning-with-one-time-provision-attribution).
Withdrawing support ends its open promises and preserves past contributions. REJECTED, COMPLETED and
CANCELLED end open promises. Reopening leaves them historical. An empty promise leaves support active.

**Acceptance**

- [ ] Concurrent enrollment/promise creation produces one active Staffel participation.
- [ ] A helper becoming responsible retains history without duplicate active roles.
- [ ] Reopening or replenishing a former need does not revive ended promises.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest` and `MaterialClaimConcurrencyTest`
cover MATERIAL/ITEM participation and role conversion. The aUEC promise workflow follows its
own implementation slice. **Issues:** #3193, #3200, #3207

### REQ-ORDERS-052 — Permitted readers see the complete order

**Implemented MATERIAL/ITEM workspace:** queue/count, number search, detail, linked reads,
material demand and persisted handover reports use the same complete permitted audience.
Requester reduction and its DTO flag are removed; processing and requester capabilities remain
separate. Current contextual writers receive the concrete workspace capability even without a
direct membership. Independent producer, mission, unrelated Inventory, Bank and reduced Exchange
gates remain. V381 retains only observed order/unit relationships: current parties and retained
claim facts migrate; authorised creates and edits retain their observed before/after parties.
Current unit reach is required, and a former individual's lost membership grants nothing.
Current and ended participations provide observed supporting-unit readership; only active support
enters the unit-work filter, independently of open promised quantities. Money-linked readers
follow their owning implementation slice.

The full order is readable by members of the requesting, responsible and active supporting units,
leadership with existing reach over those units, personal Bearbeiter, members of actual
eligible Profit units, and administrators. aUEC additionally admits explicitly account-linked
holders and recipients; their personal involvement/history read survives zero balance and
completion. A membership or account link is an authority relationship; a written player name is
not. Former units retain only the authorized read of their concrete historical orders; past
involvement is not a global Profit grant or a lasting unit write role.

This common full view includes progress, identities, individual order movements, contributions,
handover history and linked-stock owner/location. It replaces the reduced requester view.
Independent personal-blueprint/factory-panel access (REQ-ORDERS-016), unreadable missions,
unrelated inventory and Bank accounts retain their own gates. Order reading confers no general
Bank or personal-blueprint permission. Lists, number lookup, existing order-view representations
and live-room admission follow the same permitted order set. The exchange keeps its separate
reduced, capability-based surface.

**Acceptance**

- [ ] Requesters and other admitted readers see the same order facts without requester redaction.
- [ ] Linked holders retain personal order/history read after disposal of their balance.
- [ ] Common read does not expose an independently private blueprint, mission or Bank account.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest`,
`JobOrderSupportReadIntegrationTest`, `V381JobOrderUnitReadingMigrationTest`, `JobOrderAssigneeGrantIntegrationTest`,
`JobOrderScopeQueryIntegrationTest`, `JobOrderLinkedStockReadNoNPlusOneTest` and the frontend
order render tests cover the implemented workspace. `JobOrderMoneyIntegrationTest` covers the
currently linked holder through workspace capability, detail, list count and number lookup;
recipient designation belongs to the handover workflow.
**Issues:** #3197, #3200, #3201

### REQ-ORDERS-053 — Writing uses current contextual and personal authority

A unit writer is authorized unit leadership or a contextual Logistician under the existing
unit-specific grants and leadership reach rules, or an administrator. Mere membership is read authority. Either a writer of the requesting unit
or a writer of the proposed responsible unit may create an order on behalf of those units.

The creation affordance and form for all three kinds use the current backend `canCreateJobOrders` and
`GET /api/v1/orders/create-options` projection. Options carry per-unit writer flags and one
currently permitted proposed pair. The projection also carries the canonical active Profit area
as the aUEC responsible suggestion when available among the eligible choices. It uses the
same writable requester and falls back to the generic responsible suggestion if the root is
unavailable in the choices. The frontend consumes the projected suggestions and writer flags.
The backend revalidates the selected pair when creating. All create transports consume the
returned order identity and open its central detail page. A reader without a permitted pair gets
no create action and cannot open the form. Workspace and unit-work view capabilities remain
independent of creation and individual action rights.


| Actor | Order write scope |
| --- | --- |
| Responsible-unit writer | Processing, acceptance/rejection, responsibility transfer, Bearbeiter/support management and kind-specific processing, subject to financial and stock gates. |
| Requesting-unit writer | Need, target, contact/comment, recipients, cancellation and reopening a cancellation; their own requester handovers/returns under the money rules. No arbitrary internal holdings. |
| Supporting-Staffel writer | Its own support and promises, plus specifically authorized contribution recording. |
| Personal Bearbeiter | Own note and self-removal; the entry grants no general money write authority. |
| Personally linked holder/recipient | Their own financial involvement under the money rules. |
| Any full reader | May provide their own money, creating their own explicitly linked holder. Read alone does not grant assignee enrollment. |

Current authority is rechecked on each action. A genuine own transfer may introduce a new
counterparty with exactly that transfer's credit, not fabricate provision for another holder.
Active account search is scoped to the permitted action. Display names alone grant no rights.
Requesters may safely edit MATERIAL remaining demand and ITEM quantities after partial work,
within existing booked-production/delivery lower bounds; recorded facts are not discarded.

**Acceptance**

- [ ] Creation works through either side's unit writer; ordinary membership alone cannot create.
- [ ] External assignees can maintain their own note without arbitrary money rights.
- [ ] Requester edits after partial work preserve production, delivery and historical facts.

**Enforced by:** `JobOrderCreateOptionsIntegrationTest`, `JobOrderCreationWorkspaceMvcTest` and
`JobOrderAssigneeGrantIntegrationTest` cover current creation and personal-entry capabilities.
`JobOrderHierarchyAuthorityIntegrationTest` covers safe MATERIAL and ITEM requester edits
after booked work. The money workflow follows its own implementation slice. **Issues:** #3197, #3200

### REQ-ORDERS-054 — Lifecycle distinguishes kind-specific fulfillment

Orders start OPEN. aUEC acceptance or first posted money causes IN_PROGRESS; completion follows
the strict money fulfillment contract. MATERIAL and ITEM retain automatic completion on their
complete final handover and permit responsible-unit manual early completion. Their other
ordinary status changes remain responsible-unit writes. Both requesting and responsible writers
may cancel an active order and explicitly reopen a cancellation to OPEN. Each status write uses
the observed lifecycle version. MATERIAL and ITEM need edits preserve the current lifecycle,
including manual completion and cancellation. aUEC target-change effects follow its money spec.

Cancelling MATERIAL/ITEM stops ordinary fulfillment and releases order stock earmarks without
deleting physical stock, booked
production or history. It ends open promises. A new stock earmark requires reopening a cancelled order. aUEC cancellation, residual money, corrections,
returns and reopening follow its own spec. A completed MATERIAL/ITEM may therefore truthfully
show unmet quantities; the UI must not imply full physical delivery.
Lifecycle changes refresh a peer's support actions on the order page, including when the order has
no open promises.

**Acceptance**

- [ ] Manual early MATERIAL/ITEM completion remains; aUEC cannot complete below its threshold.
- [ ] Cancellation releases MATERIAL/ITEM earmarks and open promises without deleting history.
- [ ] Need edits do not silently reopen manual completion or cancellation.
- [ ] A peer sees the material handover action return in place after explicit reopening.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest` covers MATERIAL and ITEM lifecycle
rights, cancellation and the preservation of historical promises and booked work. The ITEM
early-completion case reads back the unmet need and original handover after a responsible-unit
writer closes the order. `JobOrderStatusE2eTest` covers early MATERIAL completion.
`JobOrderSharedWorkspaceE2eTest` covers the peer handover action after reopening without a reload.
`JobOrderStatusQueueRaceIntegrationTest` covers terminal queue changes. The Bank-free completion,
return and settlement cases are part of the
[money lifecycle contract](orders-auec.md#req-orders-063--auec-lifecycle-follows-effective-net-handover).
**Issues:** #3195, #3200, #3201

### REQ-ORDERS-055 — Invalid responsibility permits reading and valid transfer only

> [!note] Amended 2026-11-14 (ADR-0383) — a patch change's relocation is excepted
> A patch change's relocation still takes the shared hierarchy lock before its lot and row locks,
> but validates no linked job order: it is a game fact and no order write, and it leaves every
> earmark as it is ([REQ-PATCH-026](patch-change.md#req-patch-026--one-all-or-nothing-transaction-per-member-exactly-once)).

The hierarchy foundation applies this prerequisite inside existing order and Inventory-owned
linked-stock writes. All Inventory write transactions acquire the shared hierarchy lock before
lot/row locks; organisational changes acquire its exclusive counterpart before the change and
synchronous promise maintenance. Both coordination statements are declared on the owning
`OrgUnitRepository`; `TableOwnershipTest` verifies their repository boundary. Priority maintenance
leaves invalid orders' stored slots unchanged.
Ended legacy promises retain quantity and attribution with an ending instant; reactivation does not
revive them. The obsolete ordinary eligibility column remains during the required phase-one soak.

If the responsible unit ceases to satisfy the fixed original Profit boundary and active hierarchy,
retain the order and history, flag clarification, and permit only existing authorized reading
and transfer to a valid responsible unit. Existing current-unit/leadership/admin recovery authority
keeps this transfer usable even when the former unit no longer passes the general Profit gate. This applies to all statuses, including completed or
cancelled orders. The Bank-free writer checks this gate for settlement, financial correction and
identity maintenance on every affected order. Coupled Bank operations have the same prerequisite.
Once valid responsibility is restored, ordinary rules apply.

A supporting Staffel that becomes ineligible immediately loses active support and open promises;
past contributions remain. Existing orders and legacy flags are not silently reparented.
Migration preserves verifiable provenance, flags unresolved cases and does not invent an original
Profit boundary or deleted contribution history. Unknown original history does not block an
authorized explicit transfer into the actual Profit hierarchy: retain the missing past as unknown
and record the new transfer completely. Concrete linked personal history remains readable
where authorized; historical names alone do not create access.

**Acceptance**

- [ ] Invalid responsibility blocks every user mutation and coupled financial effect except a valid responsibility transfer; mandatory automatic eligibility maintenance still ends ineligible support and promises.
- [ ] Ineligible support ends without deleting past contributions or reviving promises.
- [ ] Migration cannot infer missing provenance solely from the current responsible unit.

**Enforced by:** `JobOrderHierarchyAuthorityIntegrationTest`,
`ProfitHierarchyWriteRaceIntegrationTest`, `InventoryJobOrderScopeIntegrationTest`,
`StockRowViewCharacterisationTest`, `JobOrderModuleContractTest`,
`JobOrderAssigneeGrantIntegrationTest`, `ProfitHierarchyStartupIntegrationTest`,
`ProfitHierarchyNoticeIntegrationTest` and `V379ProfitHierarchyMigrationTest` cover current
MATERIAL/ITEM mutations, linked stock, recovery and retained legacy history. The Bank-free writes
use the same hierarchy and order edit guards.
**Issues:** #3193, #3197, #3200

### REQ-ORDERS-056 — One shared overview leads to a central detail

**Shared workspace:** a server-side kind filter narrows every scope and its
database page count. The browser remembers kind, scope, status and unit selections; an unset scope
defaults to ALL for every permitted reader. Current responsible
and active-support unit work are independent of command authority, and creation opens the central
created-order identity. A managed Chromium journey uses distinct requester and processor accounts:
it rejects an unrelated live room, observes peer queue updates without reload, preserves an open
personal-note draft and its independent version, then saves and applies the deferred detail update.
The same workspace supports aUEC creation, reported amounts, money movements, target and
information edits, and submitted-operation recovery.

All kinds share the overview and a type filter. The overview displays the localized Orders heading.
Its scopes are Meine (requesting units),
Ich bearbeite (personal Bearbeiter), Zu bearbeiten (responsible or supporting own units under
current context/reach), and Alle (all permitted orders, including personal holder/recipient access).
Remember the selection; default to Alle. Every filter narrows the permitted read set.

Creation leads to the central detail. Kind-specific progress stays truthful without a mandatory
setup wizard. Peer updates propagate automatically, preserve open input and recheck changed
dependencies before saving; unrelated sections do not conflict. Submitted money operations have
reload-safe identity and outcome lookup. There is no durable manager of unsubmitted drafts.
Notification preferences, recipient deduplication and privacy gates remain; see
[notifications](orders-notifications.md) and the [money journey](orders-auec.md).

**Acceptance**

- [ ] Scopes/type filters work across all kinds and preserve the permitted order set.
- [ ] Creation opens the detail; peer updates preserve an open edit.
- [ ] Retry after an unknown submitted money outcome cannot duplicate its effects.

**Enforced by:** `JobOrderSupportReadIntegrationTest`, `JobOrderHierarchyAuthorityIntegrationTest`,
`OrdersListPatternRenderTest`, `JobOrderCreationWorkspaceMvcTest`, `JobOrderCreateE2eTest` and
`JobOrderSharedWorkspaceE2eTest` cover the MATERIAL/ITEM workspace. The initial aUEC create and
recovery journey is covered by `JobOrderMoneyE2eTest`. **Issues:** #3201, #3202

## Out of scope

Automatic in-game payments, reading/verifying full player wallets, mixed-kind orders, money
processing on MATERIAL/ITEM, production reversal and extra approval/workflow steps beyond the
accepted decisions. Bank internals remain in the Bank specs.

## Open questions

None for this shared contract. See the requirements above for shared workflow verification.
