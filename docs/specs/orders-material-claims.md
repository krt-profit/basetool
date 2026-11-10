> **Doc type:** Living spec — accepted/planned, not implemented. Last reviewed: 2026-11-09.
> **Owner area:** ORDERS · **Related ADRs:** ADR-0360 (amends ADR-0092)

# Material promises within Staffel participation

## Context & goal

A material claim (Eintragung) is a Staffel's still-outstanding announced contribution to a
MATERIAL or ITEM order's material/quality requirement. It belongs to the single participation
of [REQ-ORDERS-051](orders-core.md); there is no second support registration. Support can exist
without a quantity, and one participation may carry promises for several requirements.
This accepted target replaces the signal-only, SK-only claim model and the original-total ITEM
claim denominator. Runtime behavior has not yet been implemented or verified.
The owner-approved decisions are [material quantities](https://github.com/krt-profit/basetool/issues/3207)
and [technical contracts](https://github.com/krt-profit/basetool/issues/3202).

## Requirements

### REQ-ORDERS-024 — Outstanding material promises fit uncovered demand and credit real provision once

**Meaning and capacity.** A promise is neither physical stock nor delivered material nor an
immutable original quantity. For each material/quality bucket, let D be outstanding material
demand, S the matching current order-earmarked stock, and P the sum of active outstanding
promises. Capacity is max(0, D − S), and 0 <= P <= capacity must hold at commit, including concurrent
changes and the first promise in an empty set.

MATERIAL D is its remaining requested quantity. ITEM D is the material needed for items not yet
manufactured, changing the intentional full-original-order denominator in REQ-ORDERS-025.
Existing manufactured, skipped-consumption and delivered counters and safe edit bounds remain.
Use the established quality-floor allocation (REQ-ORDERS-036/-037): each physical stock quantity
covers at most one bucket. Current quality coverage is not historical evidence of a Staffel's
contribution. aUEC promises have their own money rules; this does not impose a material cap on them.

**Explicit provision credit.** A genuine suitable provision earmarked to the order may fulfill
an explicitly selected Staffel's matching open promise once, within the lesser of its open
amount and the genuinely provided quantity. Record attribution in the ordinary stock
provision/allocation action. The responsible-unit writer or affected Staffel's writer may do
this with the required inventory rights. The recorder, stock owner, recipient, memberships or
executing-Staffel snapshot do not infer the credited Staffel. Excess/unattributed stock is allowed
under inventory rules; it creates no fictitious promise or fulfillment of a foreign promise.

The same physical quantity cannot be credited again through relocation, release/relinking,
row merge/removal, dynamic quality redistribution, manufacture or final handover. Genuine new
provision is a new fact. A promise itself creates or moves no inventory. Historical contribution
credit is durable evidence separate from the current coverage projection.

**One coherent adjustment.** Apply the stock provision, allowed explicit credit, capacity
calculation and necessary proportional reductions atomically. Credit the selected promise
first, then reduce any remaining promises that exceed changed capacity. Do not proportionally
cut and then credit the same quantity again. A legitimate demand or stock change, including
an authorized requester demand edit, may cause derived reductions without a new approval from
each Staffel. This gives no arbitrary manual foreign-promise write authority.

Proportional reductions mean no longer needed, not contribution fulfilled. Preserve old/new
amounts and reason. Floor exact proportional quotas to valid quanta (whole PIECE or 0.001 SCU),
then give remaining quanta by descending fractional remainder, breaking ties by stable immutable
participation identity. The resulting sum equals the reduced capacity in valid quanta, never
exceeds it and never increases an individual promise. Repeated inputs give the same result.
Zero open quantity does not withdraw the Staffel's whole participation.

If a bucket disappears, its capacity becomes zero and its open promises end while history remains.
Do not silently move an old quality promise to a new bucket. Increasing need/capacity never grows
reduced promises or revives ended ones; new/increased promises require their normal deliberate
write action.

**True later changes and false facts.** Correctly credited past provision remains a contribution
when stock is genuinely consumed, released or lost. Current uncovered demand may rise without
creating a new obligation for that Staffel. Manufacture and delivery do not credit that contribution
a second time; skipped material consumption is not proof of provision.

If the original provision or attribution was false, correct connected stock, promise and credit
facts together. Restore the wrongly removed amount only for a still-running promise and within
current capacity; no negative quantity or ended-promise revival is allowed. Connected later
records may require the agreed connected correction rather than an isolated inverse. A true
stock loss is not a correction of a truthful historic contribution.
The same ordinary correction action also corrects a falsely recorded original supplied quality.
Retain its prior observed value, or its explicit unknown status, and the corrected declared value
in history. Apply the resulting truthful historical credit and prescribed promise/capacity
consequences together under current affected-data authority and scoped versions. A genuine later
quality change is a separate fact and must be preserved. Current stock quality alone neither
proves the original quality nor authorizes blindly resetting retained stock. Change retained
physical quality only through its known connected facts and current Inventory rights/versions.
Consumption does not by itself prevent correction of the historical quality/credit fact and does
not authorize production reversal. Correcting quality does not turn an unknown historical total
or composition into observed quantity evidence. This adds no material substitution or correction
of other original provision fields.

**Correction ownership and known quantity.** A provision's actual declared quantity is distinct
from its credit clipped to an open promise. Retain the actual known composition, including any
uncredited excess, separately from current truthful attribution and immutable processed-once
quantity. Correcting actual provision 4 to 3 when only 3 were credited removes the uncredited
quantity first. If that quantity was later truthfully credited through another receipt, correct
every known intersecting credit in the same transaction, including credits on another order.
Every affected order must have valid current responsibility and the actor must currently be
allowed to correct its affected contribution. Inaccessible or changed connected facts fail
atomically; no extra manual approval or client version is required for prescribed consequences.

A historical attribution-only correction changes JobOrder facts. A responsible-unit writer may
coordinate it; otherwise current grants for both the original and selected target Staffel are
required. The original Staffel's writer may remove its own false attribution. No obsolete
Inventory authority snapshot is reconstructed when stock is untouched. An existing ended matching
target may receive corrected historical credit while both promises remain ended; this neither
creates support nor restores open quantity. A writer for only the new target cannot relabel
another Staffel's historical contribution.

A physical correction additionally requires current Inventory authority and observed row versions
for the retained connected stock it changes. Correction options check that authority before
returning any row identity, version or label. A relocated or merged retained quantity remains
correctable through its known origins. An explicitly declared upward correction from recorded 3
to actual original 4 uses the same correction action and a selected retained connected row: admit
only the additional 1 as a new known physical quantity and apply once-only credit to that delta.
This does not turn a genuinely later provision into a correction or revive an ended promise.
If an exact downward inverse requires fully consumed quantity or production reversal, reject
that isolated physical correction with 409; attribution-only correction remains available. This
is the existing production-reversal boundary, not a general prohibition on historical correction.

**Authority and lifecycle.** The shared contextual writers, single participation, fixed Profit
boundary and strict invalid-responsibility gate in REQ-ORDERS-050/-055 apply. A Staffel has at most
one active promise per bucket; repeated editing addresses that same promise with the required
scoped version. Meaningful concurrent amount edits must not be silently retried as last-writer-wins.
Terminal rejection/completion/cancellation, support withdrawal and ineligibility end open promises
without deleting past contributions. Ineligibility also ends active Staffel participation even when
it has no open promises or only retained ended contributions; this mandatory prerequisite is part
of the current material workflow. Historical read remains concrete and current-unit scoped. Neither
returning eligibility nor reopening revives support or promises. Terminal completion alone does not
end an otherwise eligible participation. Explicit support enrollment/withdrawal remains the shared
workflow defined in REQ-ORDERS-051. Reopening does not revive ended promises.

**Concurrency and boundaries.** Guard capacity at order-plus-material scope spanning all of that
material's quality buckets; overlapping quality stock makes a single bucket or Staffel row too
narrow. Every relevant demand, stock-allocation, promise, attribution and correction mutation
participates, including initial inserts. Use independently scoped client versions and the
deterministic common resource-lock protocol; do not prescribe the legacy whole-order claim lock
as the new default. Inventory owns stock and its grants, JobOrder owns promise/capacity/credit;
synchronous narrow APIs keep their shared invariants in the same transaction. Exact SQL and
implementation classes remain implementation choices under ADR-0360 and REQ-ORDERS-046.

**Migration and history.** Migrate only verifiable historical unit credit. Existing stock without
such evidence reduces capacity as unattributed coverage and may cause proportional reduction.
Do not reconstruct contribution from today's ownership, membership, intent or executing Staffel.
Missing history remains missing. Distinguish real credit, derived reduction/requirement ending,
false-fact correction, later true stock changes and manual promise changes in readable history
and privacy-safe audit. In-place and peer updates refresh affected stock/capacity/promise views.
Audit, notices and bounded monitoring follow their canonical contracts.

Legacy records that prove only a credited subset remain explicitly marked as such. Their credited
quantity and requirement quality floor are lower-bound facts, not proof of the full actual
provision or its original quality. Do not present these as observed truth or permit a physical
quantity correction that relies on those unknown facts. New ordinary provision captures the
actual declared quantity, quality and known composition; historical label corrections retain the
legacy evidence distinction.

**Acceptance**

All amounts below are SCU unless stated otherwise. Alternative rows start from the stated initial
case rather than accumulating every alternative.

| Case | Demand D | Stock S | Open A / B | Historical credit | Meaning |
| --- | ---: | ---: | --- | --- | --- |
| Initial | 10 | 0 | 4 / 6 | 0 | Capacity 10 |
| A explicitly provides 3 | 10 | 3 | 1 / 6 | A: 3 | Credit before capacity cut |
| Those 3 are relocated | 10 | 3 | 1 / 6 | A: 3 | No repeat credit |
| Those 3 are handed over on MATERIAL, or consumed by ITEM manufacture reducing need by 3 | 7 | 0 | 1 / 6 | A: 3 | No second contribution |
| Initial, then 3 unattributed stock | 10 | 3 | 2.8 / 4.2 | 0 | No longer needed, not supplied by A/B |
| Initial, then demand reduced to 6 | 6 | 0 | 2.4 / 3.6 | 0 | Proportional reduction |
| Correct credited provision 3 to actual 2 | 10 | 2 | 2 / 6 | A: 2 | Connected false-fact correction |
| Genuine provision 3 later lost | 10 | 0 | 1 / 6 | A: 3 | Gap 3, no revived obligation |

- [ ] Each example holds atomically and a duplicate/relocation/relink/quality change cannot repeat credit.
- [ ] PIECE promises 4/6 reduced to capacity 6 become 2/4; ties use immutable participation identity.
- [ ] Concurrent first claims, stock and demand changes across overlapping quality tiers cannot
  leave negative or over-capacity promises; unrelated materials/orders retain independent versions.
- [ ] Manufacture without a credited provision adjusts demand/capacity but does not fulfill a promise.
- [ ] Excess provision cannot create a negative promise, foreign credit or invented new obligation.
- [ ] Requester need changes cause only prescribed derived cuts, without extra Staffel approval.
- [ ] Removing/changing a bucket preserves credit/history without duplicate participation.
- [ ] False-fact correction differs from genuine later loss and never revives an ended promise.
- [ ] The same correction action fixes false original supplied quality, preserves genuine later
  quality changes and unknown quantity evidence, and retains prior/corrected history without
  production reversal or ended-promise revival.
- [ ] Ineligible/terminal/reopened cases obey shared write and participation boundaries.
- [ ] Legacy stock covers demand without fabricated contribution attribution.

**Enforced by:** Pending implementation. Existing MaterialClaimConcurrencyTest and
MaterialClaimServiceTest cover the legacy model only; they do not prove this target.
**Issues:** #3207, #3202, #3203 · **ADR:** ADR-0360, amending ADR-0092

## Out of scope

Money, new material bank accounts/ledgers, a second enrollment or approval process, automatic
attribution from a recorder/owner, production reversal, a general settlement workflow and runtime
implementation. Actual inventory ownership and ordinary stock permissions stay with Inventory.

## Open questions

None for the approved quantity contract. Runtime checks remain pending implementation.

The existing explicit administrator hard-delete of a MATERIAL/ITEM order (REQ-ORDERS-042) removes
its dependent active and ended claim rows in the same transaction after the responsibility guard.
The normal `JOB_ORDER_DELETED` audit survives. Automatic eligibility ending remains distinct: it
retains every promise fact and never deletes its history.

The current SK-only claim workflow exposes writable Staffel options through
`GET /api/v1/orders/{jobOrderId}/claims/claiming-options`. The read requires access to the concrete
order; empty options confer no write authority. Options and existing claim writes share the same
claim-management predicate. A contextual Staffel writer can maintain its own existing claim
without receiving responsible-side processing authority. The existing responsible-SK grant
exception remains part of this workflow: `hasRoleInOrgUnit(SK, LOGISTICIAN)` differs from the
active-context check `canActAsLogisticianIn(Staffel)`. The complete shared writer conversion must
resolve that distinction against REQ-ORDERS-053 and the canonical leadership/context rules.
`JobOrderHierarchyAuthorityIntegrationTest` and the frontend `JobOrderClaimControlsMvcTest` cover
the current options, positive own-Staffel writes and foreign/ordinary/invalid denials.

## Material implementation foundation

The current implementation groups all material-quality promises of one order/Staffel under
`JobOrderParticipation`. Empty or fulfilled open amounts do not end support. Outstanding capacity
uses unmanufactured ITEM demand and the canonical once-only quality coverage. The material guard
spans every quality tier; Inventory plans actual source/destination scopes before lot/row locks
and rejects a changed plan instead of acquiring another guard after stock locks.
MATERIAL demand replacement uses the same planned editor for responsible-side and requester
edits. It captures immutable requested material lines, includes newly requested materials and
exact removed-material stock rows in its initial plan, and rechecks current authority, order
version and resource identities after locking. A changed plan conflicts before the mutation.
`MaterialOrderResourcePlanningIntegrationTest` observes a real new-material allocation waiting
for the demand transaction, then checks both committed operations and retained old promise
history through public boundaries.

`CreateClaimDto.version` is absent for a first promise and mandatory for an existing quantity edit.
Duplicate first creation conflicts; meaningful edits do not retry as last-writer-wins. A repeated
current-version save of the same canonical amount is a true no-op: it preserves the contribution
version, history and existing personal association. Stale versions still conflict before that
no-op decision. A repeated already-cleared historical attribution also preserves its contribution
and receipt facts after current authority and scoped-version checks. The returned claim version
includes its flushed history change. An explicit `provision` selection in the ordinary
Inventory allocation/check-in payload identifies a matching permitted promise and its version.
Only the action's genuinely new quantity budget can create credit; unchanged allocation relabelling
has no new budget. Inventory carries exact known physical origin intervals through transforms;
JobOrder keeps processed intervals independently of deletable stock, allocation and order rows.
No legacy stock creates inferred Staffel performance.

The focused implementation checks currently establish version conflicts, SCU proportional demand
reduction, selected provision before cuts, and known split/merge/loss/restore identity. The complete
correction, public history/UI, grouped notices and cross-path race acceptance above remains pending;
this foundation is not evidence that the complete shared workflow has shipped.
