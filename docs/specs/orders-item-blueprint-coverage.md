> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** ORDERS · **Related ADRs:** none

# Item-order blueprint coverage

## Context & goal

An `ITEM` job order (Auftrag) requests finished items to be crafted, each line pinned to a
specific SC Wiki blueprint. To plan who can actually build the order, the processing
squadron/SK needs to know **which of its members already own the blueprints** for the
ordered items — and, per member, **which** of those blueprints they hold.

This spec governs the **blueprint-coverage view** rendered in the item-order detail page,
directly after the *Bearbeiter* (assignees) section. It bridges three existing concepts: the
order's required item lines (`JobOrderItem.blueprint` → output name), the per-user blueprint
ownership of the personal-inventory feature (`PersonalBlueprint`, keyed by the normalized
`product_key`, see [`blueprint-import-name-matching.md`](blueprint-import-name-matching.md)),
and org-unit membership ([`org-unit-tenancy.md`](org-unit-tenancy.md)). It deliberately
mirrors the org-unit blueprint-availability overview (#364) but scopes to one order's
required products and its responsible org unit.

## Requirements

### REQ-ORDERS-015 — Blueprint-coverage view for item orders

The item-order detail page MUST show, for an `ITEM` order, who among the members of the
order's **responsible (processing) org unit** owns the blueprint for each required item **or for
any cosmetic variant of it**, and which concrete blueprint each member holds. The view is
**person-centric** — one row per owning member with the actual variant blueprints they own — and
additionally surfaces a **per-item coverage** summary (each distinct required item with the count of
members who own a matching blueprint, flagging items **no member** owns as a coverage gap).

In addition to the responsible org unit's members, users who opted into **global blueprint sharing**
(`REQ-INV-018`) are counted in the coverage and listed as owners even when they are **not** members
of the responsible org unit; such an owner is marked with a **discreet "not a unit member" hint** so
the processing unit can tell a cross-unit volunteer from its own members. This widens only *whose*
blueprints are counted; it does not change *who* may open the coverage view (`REQ-ORDERS-016` is
unaffected), and owners are still exposed by display name only.

Matching is by the **variant family key** (`REQ-INV-015`), not the raw `product_key`: a required
line's `blueprint.outputName` and each member's `PersonalBlueprint.productName` are reduced to a
family key by `BlueprintVariantFamilyResolver`, so a base item and its cosmetic variants
(`Fresnel Energy LMG` ↔ `Fresnel "Molten" Energy LMG`) match in **both directions** — ordering the
base counts owners of any variant, and ordering a variant counts owners of the base and the sibling
variants. **Magazines are never counted** toward a weapon (they are atomic; a required magazine
matches only an identical magazine). Distinct required items are de-duplicated by family key. A
`MATERIAL` order yields an empty view. A member's blueprints that are **not** in a required family
are never exposed, and owners are identified by display name only (never the Keycloak `sub` or
e-mail).

Family-key matching is the **default**, but it is governed by the per-order **variant-counting toggle**
(`REQ-ORDERS-021`): when an order is switched to *exact* counting, the match key becomes the exact
normalized name instead of the family key, so only owners of the exact ordered blueprint count and the
family's other variants are excluded. The two matching modes are otherwise identical (same scope, same
gate, same display).

Display: each coverage row shows the **ordered** item name (the variant the line requested, if any)
and, for every non-magazine row, a "counts variants" hint; each owner row shows the **actual** owned
variant blueprint names (so a lead sees which variant each member holds). The whole panel
(owners list + per-item coverage table) is rendered **collapsible** — its heading is the toggle, with
a `+`/`−` affordance — and starts **expanded**, so a lead can fold the (potentially long) availability
panel away without leaving the order page. Collapsing it is purely cosmetic and does not change the
data exposed.

**Acceptance**

- [ ] A member who owns a **cosmetic variant** of a required item appears in the owners list and is
  counted in that item's coverage, with the actual variant name shown.
- [ ] Ordering a **variant** counts owners of the base and the sibling variants (symmetric).
- [ ] A **magazine** (e.g. `… Magazine (NNN cap)`, `… Battery`, `… Ammo Box`) is never counted toward
  its weapon, and a member's magazine is not surfaced; a required magazine matches only itself.
- [ ] A required item no member can build shows owner count `0` (a gap) in the coverage summary.
- [ ] A member owning none of the required families does not appear in the owners list.
- [ ] A user who opted into global sharing (`REQ-INV-018`) and owns a required family is counted
  and listed as an owner even when they are not a member of the responsible org unit.
- [ ] A material order returns an empty coverage view.
- [ ] The coverage panel renders inside a collapsible container that starts expanded; toggling it
  open/closed changes only visibility, never the data shown.

**Enforced by:** `JobOrderItemBlueprintOwnersServiceTest`, `BlueprintVariantFamilyResolverTest`,
`JobOrderItemDetailRenderTest` ·
**Code:** `JobOrderItemBlueprintOwnersService`, `BlueprintVariantFamilyResolver`,
`JobOrderController.getItemBlueprintOwners` (`GET /api/v1/orders/{id}/item-blueprint-owners`) ·
**Issues:** —

### REQ-ORDERS-016 — Coverage view is restricted to the responsible org unit's members

The blueprint-coverage view MUST be visible **only to members of the order's responsible
squadron/SK** (and to admins, who hold system-wide oversight). This is **stricter** than the
order's own visibility: an SK-responsible order is publicly readable by every profit-eligible
member ([`security-and-access.md`](security-and-access.md)), but the named per-member coverage
is restricted to members of that SK. The gate is
`@ownerScopeService.canSeeJobOrderBlueprintOwners`, which evaluates the standard org-unit
scope predicate against the order's responsible org unit (so a non-admin matches only org
units in their own membership set, with no SK-public escape). A non-member who can otherwise
open a public SK order's detail page receives HTTP 403 from the endpoint, and the frontend
simply omits the section. The global blueprint-sharing opt-in (`REQ-INV-018`) does **not** widen
this gate — it changes only which owners are counted *inside* the view, never who may open it.

**Acceptance**

- [ ] A member of the responsible org unit receives the coverage view (HTTP 200).
- [ ] A non-member who can read a public SK order's detail receives HTTP 403 from the
  coverage endpoint, and the detail page renders without the section.
- [ ] An admin receives the coverage view for any order.

**Enforced by:** `OwnerScopeServiceTest` (canSeeJobOrderBlueprintOwners),
`JobOrderControllerTest` (getItemBlueprintOwners auth) · **Code:**
`OwnerScopeService.canSeeJobOrderBlueprintOwners` (delegating to
`AccessGateService.canSeeJobOrderBlueprintOwners`) · **Issues:** —

### REQ-ORDERS-021 — Per-order toggle: count blueprint coverage with or without variants

An `ITEM` order MUST carry a per-order **variant-counting** flag (`countBlueprintsWithVariants`,
default **true**) that governs how its blueprint-coverage view (`REQ-ORDERS-015`) matches blueprints:

- **On (default)** — coverage counts cosmetic **variants** of each ordered item via the variant
  family key, the historic behaviour (a member owning any variant of an ordered item is counted).
- **Off** — coverage matches blueprints **exactly** by normalized name, so when a specific variant is
  ordered only owners of that exact blueprint count and the family's other variants are excluded; the
  per-row "counts variants" hint is suppressed.

The flag is **persisted on the order** and applies for **every** viewer (it is a property of the
order, not a per-viewer view preference), so a lead who needs the strict count for a one-specific-
variant order sets it once. The flag is toggled **live** from the coverage panel: a control visible
only to editors (`hasRole('LOGISTICIAN')` + the order's edit scope, `@ownerScopeService.canEditJobOrder`)
PATCHes the order and re-renders the coverage panel **in place** (no full-page reload, per
`REQ-FE-001`); the order's optimistic-lock `version` guards the write (a stale version yields HTTP 409),
and the bumped version is propagated to the order's other version-carrying controls so a subsequent edit
does not 409. Read-only members who may see the panel (`REQ-ORDERS-016`) do not see the control. The
toggle is a state-mutating activity in an audited area and records a `JOB_ORDER_BLUEPRINT_COUNTING_CHANGED`
audit event (`REQ-AUDIT-001`). The flag is ignored for `MATERIAL` orders (which have no coverage view),
and the PATCH endpoint rejects a non-`ITEM` order with HTTP 400.

**Acceptance**

- [ ] A new item order defaults to counting **with** variants, preserving the historic coverage.
- [ ] Switching an order to **without** variants makes a required variant count only owners of that
  exact blueprint; owners of sibling variants and the base are no longer counted, and the per-row
  "counts variants" hint disappears.
- [ ] Switching back to **with** variants restores the family-key coverage.
- [ ] The toggle persists on the order and is reflected for every viewer of the coverage panel.
- [ ] Toggling re-renders the coverage panel in place without a full-page reload, and a concurrent
  edit of the same order surfaces as a 409 rather than a lost update.
- [ ] The toggle control is shown only to editors; a read-only member sees the panel without it.
- [ ] Toggling records a `JOB_ORDER_BLUEPRINT_COUNTING_CHANGED` audit event; the PATCH on a material
  order is rejected with HTTP 400.

**Enforced by:** `JobOrderItemBlueprintOwnersServiceTest` (with/without-variants matching),
`JobOrderServiceTest` (toggle update: persistence, 409, audit, non-item 400),
`JobOrderControllerTest` (PATCH auth) · **Code:**
`JobOrderItemBlueprintOwnersService`, `BlueprintVariantFamilyResolver.matchKey`,
`JobOrderService.updateBlueprintVariantCounting`,
`JobOrderController.updateBlueprintVariantCounting` · **Issues:** #822

## Out of scope

- Editing blueprint ownership from the order page — ownership is managed in the Personal
  Inventory area (#327); this view is read-only.
- The org-unit-wide blueprint-availability overview (#364) — a separate leadership oversight
  surface scoped to oversight org units, not to a single order's required products.
- Material orders, which request raw materials rather than crafted items and have no blueprint
  requirement.

## Open questions

None.
