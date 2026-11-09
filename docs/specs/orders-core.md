> **Doc type:** Living spec — hierarchy foundation implemented; remaining workflows accepted/planned. Last reviewed: 2026-11-09.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0356, ADR-0359, ADR-0360

# Shared JobOrder model

## Context & goal

MATERIAL, ITEM and aUEC procurement use one Auftragssystem. This is the canonical shared
organisation, participation, access and lifecycle contract. Accepted Wayfinder decisions
[responsibility](https://github.com/krt-profit/basetool/issues/3193),
[access](https://github.com/krt-profit/basetool/issues/3197) and
[shared scope](https://github.com/krt-profit/basetool/issues/3200) replace conflicting legacy
rules. The implemented hierarchy foundation is identified below; other shared workflows remain planned.

Kind-specific homes remain [production](orders-item-production.md),
[material promises](orders-material-claims.md), [money](orders-auec.md) and
[optional Bank integration](orders-auec-bank.md). Acceptance below remains unchecked.

## Requirements

### REQ-ORDERS-049 — Every order has exactly one kind

An order is exclusively MATERIAL, ITEM or aUEC procurement. Material/item demand and money demand
cannot be mixed. aUEC holdings, financial promises, recipients, movements and optional order/Bank
coupling belong only to aUEC orders. Common organisation, participation, reading, unit authority,
navigation and invalid-hierarchy handling apply to all three kinds.

**Acceptance**

- [ ] Each kind exposes only its applicable demand and processing actions.

**Enforced by:** Pending implementation · **Issues:** #3200, #3203

### REQ-ORDERS-050 — Responsibility follows the actual Profit hierarchy

**Implemented foundation:** MATERIAL and ITEM resolve responsibility from the active designated
Profit area and its active Staffel/SK children. Current writers of either proposed side may create;
current responsible-unit writers may transfer directly, including recovery after hierarchy changes.
Unknown legacy original boundaries remain unknown and do not alone invalidate current eligibility.
Before application readiness, automatic maintenance ends existing ineligible active promises with
retained quantities, attribution and history, normal audit records and grouped current-writer notices.
It also runs atomically on later hierarchy changes; restarting does not repeat ended promises.
The complete participation, common-view and aUEC workflows below remain separate implementation work.
`JobOrderDto.canEdit` remains responsible-side processing authority in every projection;
`canEditAsRequester` separately exposes the current requesting-unit writer and existing delivery
freeze. The existing MATERIAL requester editor uses that explicit capability independently of
redaction. The ITEM processing editor retains its responsible-side gate; completion of the shared
requester write workflows belongs to the subsequent requester/lifecycle implementation. List
projections resolve the requester delivery restriction in one bounded ID query.

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

**Enforced by:** Pending implementation · **Issues:** #3193, #3197, #3200

### REQ-ORDERS-051 — One Staffel participation carries support and promises

An eligible Profit Staffel may support an order responsible to the Bereich, an SK or another
Staffel. At most one active participation per order/Staffel exists. Its optional material
promises (REQ-ORDERS-024) or aUEC promise belong to that participation; creating a first promise
also enrolls it. There is no parallel helper registration. Multiple Staffeln may participate.

Responsible-unit writers may enroll/remove supporting Staffeln; writers of a supporting Staffel
may enroll/remove their own Staffel and maintain its own promises. A responsible Staffel cannot
also support itself. Making a supporter responsible removes it from the active support list while
preserving its contribution lines and history under its responsibility. This role transition is
not a withdrawal of its contributions. The former responsible Staffel is not automatically enrolled
as a supporter. Withdrawing support ends its open promises, preserving past contributions. REJECTED, COMPLETED and
CANCELLED end open promises; reopening does not revive them. Empty promises do not end support.

**Acceptance**

- [ ] Concurrent enrollment/promise creation produces one active Staffel participation.
- [ ] A helper becoming responsible retains history without duplicate active roles.
- [ ] Reopening or replenishing a former need does not revive ended promises.

**Enforced by:** Pending implementation · **Issues:** #3193, #3200, #3207

### REQ-ORDERS-052 — Permitted readers see the complete order

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

**Enforced by:** Pending implementation · **Issues:** #3197, #3200, #3201

### REQ-ORDERS-053 — Writing uses current contextual and personal authority

A unit writer is authorized unit leadership or a contextual Logistician under the existing
unit-specific grants and leadership reach rules, or an administrator. Mere membership is read authority. Either a writer of the requesting unit
or a writer of the proposed responsible unit may create an order on behalf of those units.

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

**Enforced by:** Pending implementation · **Issues:** #3197, #3200

### REQ-ORDERS-054 — Lifecycle distinguishes kind-specific fulfillment

Orders start OPEN. aUEC acceptance or first posted money causes IN_PROGRESS; completion follows
the strict money fulfillment contract. MATERIAL and ITEM retain automatic completion on their
complete final handover and permit responsible-unit manual early completion. Their other
ordinary status changes remain responsible-unit writes. Both requesting and responsible writers
may cancel and reopen a cancellation. For MATERIAL/ITEM, need edits do not automatically reopen
an order manually completed or cancelled. aUEC target-change effects follow its money spec.

Cancelling MATERIAL/ITEM releases order stock earmarks without deleting physical stock, booked
production or history. It ends open promises. aUEC cancellation, residual money, corrections,
returns and reopening follow its own spec. A completed MATERIAL/ITEM may therefore truthfully
show unmet quantities; the UI must not imply full physical delivery.

**Acceptance**

- [ ] Manual early MATERIAL/ITEM completion remains; aUEC cannot complete below its threshold.
- [ ] Cancellation releases MATERIAL/ITEM earmarks and open promises without deleting history.
- [ ] Need edits do not silently reopen manual completion or cancellation.

**Enforced by:** Pending implementation · **Issues:** #3195, #3200, #3201

### REQ-ORDERS-055 — Invalid responsibility permits reading and valid transfer only

The hierarchy foundation applies this prerequisite inside existing order and Inventory-owned
linked-stock writes. All Inventory write transactions acquire the shared hierarchy lock before
lot/row locks; organisational changes acquire its exclusive counterpart before the change and
synchronous promise maintenance. Priority maintenance leaves invalid orders' stored slots unchanged.
Ended legacy promises retain quantity and attribution with an ending instant; reactivation does not
revive them. The obsolete ordinary eligibility column remains during the required phase-one soak.

If the responsible unit ceases to satisfy the fixed original Profit boundary and active hierarchy,
retain the order and history, flag clarification, and permit only existing authorized reading
and transfer to a valid responsible unit. Existing current-unit/leadership/admin recovery authority
keeps this transfer usable even when the former unit no longer passes the general Profit gate. This applies to all statuses, including completed or
cancelled orders. No collection, correction, settlement or coupled Bank operation bypasses this
gate. Once valid responsibility is restored, ordinary rules apply.

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

**Enforced by:** Pending implementation · **Issues:** #3193, #3197, #3200

### REQ-ORDERS-056 — One shared overview leads to a central detail

All kinds share the overview and a type filter. Its scopes are Meine (requesting units),
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

**Enforced by:** Pending implementation · **Issues:** #3201, #3202

## Out of scope

Implementation, automatic in-game payments, reading/verifying full player wallets, mixed-kind
orders, money processing on MATERIAL/ITEM, production reversal and extra approval/workflow steps
beyond the accepted decisions. Bank internals remain in the Bank specs.

## Open questions

None for this shared contract. Runtime acceptance remains pending implementation.
