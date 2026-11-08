# Refinery store choices — which Exchange resources offer what the web store dialog offers

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-11-08 against `origin/main` at `743093c0f` for issue #3139 (map #3128). Nothing here is
> binding; the contract that builds on it gets its own `REQ-…`.
>
> **Question:** a client's pickup may carry store lines exactly like the web's store dialog
> (`RefineryOrderStoreItemDto`: material, location, quality, amount, receiving user, personal, job
> order, org unit, note), checked by the same rules. Which Exchange resources give a client the same
> choices today, under which scopes, and where is there no source?

## 1. Short answer

| Choice | Exchange source today | Verdict |
|---|---|---|
| Location | `GET /exchange/v1/catalog/locations` (any granted exchange scope) | **Full match** — same filter as `OfferedLocations` |
| Org unit (receiver = the member) | `GET /exchange/v1/me/org-units` (`exchange.orgunits.read`) | **Full match** for the member's own direct memberships |
| Personal | none needed (a flag) | **Full match** |
| Note | none needed (free text, ≤ 1000) | **Full match** |
| Material / quality / amount | the order's own goods — no Exchange read of refinery orders exists yet; `catalog/resolve` resolves material refs | **Gap until the refinery read of #3129 ships** |
| Receiving user (another member) | `GET /exchange/v1/org-stock/members?unit=` (`exchange.orgstock.write`, Logistician of the unit) | **Partial** — and the web's server rule cannot run as-is on an exchange request |
| Org unit (receiver = another member) | only the roster's own `unit` | **Partial** — no read of another member's memberships |
| Job order | `orders[]` of `GET /exchange/v1/me/org-demand` (`exchange.demand.read` + `.details`) or `GET /exchange/v1/org-stock/targets?unit=&material=` (`exchange.orgstock.write`) | **Partial** — both only for a Logistician of the unit; a plain member has no order ids |

The cleanest v1 cut: **self-receiver store lines** (location, own org unit, personal, note, the
order's goods) are fully covered once the refinery read exists. Foreign receivers and job-order
earmarks need either a decision to reuse the org-stock Logistician surfaces (roster refs, targets)
or new reads.

## 2. Per choice

### 2.1 Location

- **Web.** `RefineryOrderPageController#addOrderModel` presets every store line with the order's
  refinery location; the select lists `allLocations`, filled by `fetchAllLocations()` →
  `RefineryBackendClient#allLocations` → `CachedCatalog.LOCATIONS` = `GET
  /api/v1/locations?size=1000` (template `refinery-orders-details.html`, `storeLocation_*`).
  `LocationService#getAllLocations(…, includeHidden=false)` →
  `findByHiddenFalseAndReferenceOnlyFalse`.
- **Rule.** `RefineryOrderStoreService#storeRefineryOrder` →
  `OfferedLocations.requireOffered(…)`: neither `hidden` nor `referenceOnly`, else
  `LocationNotOfferedException` (REQ-P4K-039).
- **Exchange.** `GET /exchange/v1/catalog/locations` (`docs/exchange/resources/catalog.md`,
  REQ-XCH-018), served by `ExchangeCatalogService#locations` →
  `LocationRepository#findExchangeLocations` (`WHERE l.hidden = false AND l.reference_only =
  false`). Callable with any exchange scope the registry grants. A `location` ref in a write
  resolves by `guid`, `uex`, then exact `name` (`ExchangeLocationResolver`), all restricted to
  the same filter; an unknown place is `LOCATION_UNKNOWN`.
- **Verdict.** Same list, same rule. A new op takes a `location-ref` (no internal id) and resolves
  it with `ExchangeLocationResolver`, then still runs `OfferedLocations.requireOffered`.

### 2.2 Org unit

- **Web.** `storeOrgUnitOptions` = `fetchUserOrgUnitOptions(currentUserId)` →
  `GET /api/v1/org-units/members/{id}/memberships?allKinds=true`
  (`MemberMembershipController#getUserMemberships`). Each line is preset with the order's
  `owningOrgUnitId`. When a Logistician changes the receiver, `refinery-orders-details.js` reloads
  the options from `/refinery-orders/users/{userId}/org-units`, a proxy of the same backend call
  for the new receiver.
- **Rule.** `OrgUnitStampingService#resolveOrgUnitForPickerOutputNullable(assignee, id)`: a
  receiver without memberships takes no unit (a pick is 400); with memberships, a pick must be a
  **direct membership of the receiver** or a unit the caller may edit
  (`AccessGateService#canEditSquadron`); no pick auto-stamps a single membership, otherwise the
  active-context pin, otherwise `OwnerOrgUnitRequiredException`.
- **Exchange.** `GET /exchange/v1/me/org-units` (`exchange.orgunits.read`, REQ-XCH-048): the
  member's direct memberships of all four kinds — the same set the web offers when the receiver is
  the member. For another receiver there is no read of their memberships; the org-stock roster
  only proves membership of the roster's own `unit`.
- **Gaps.** An exchange request has no active-context pin and no contextual grants (REQ-XCH-009),
  so the `canEditSquadron` widening and the pin fallback never apply: a member with several
  memberships must name the unit, which matches the ground rule "client-named org unit" (#3129).

### 2.3 Receiving user

- **Web.** Each line is preset with the caller (`storeItem.setUserId(currentUserId)`). Only when
  `LogisticianGate#isGranted()` holds does the template render a `remote-users` combobox
  (`krt-user-search.js` → `/users/search` → `GET /api/v1/users/search`, every member); a plain
  member sees their own name in a disabled select that submits nothing, so the server falls back
  to the order's owner.
- **Rule.** `StockCommands#requireBookIn(target, caller, isLogistician, personal,
  BookInRule.refineryStore())`, implemented in `InventoryStockCommands`: the caller themselves
  passes; another member needs `BookInPolicy#mayBookInFor(target)` — admin, or a shared
  **editable** org unit with the target (REQ-SEC-039, amended); `refineryStore()` does not refuse
  `personal` for another member and needs Logistician+ only for an ownerless order (an own order
  always has an owner). `isLogistician` is `RefineryAccessPolicy#isLogisticianOfOrder` — a
  Logistician grant in the order's owning unit, or admin.
- **Exchange.** `GET /exchange/v1/org-stock/members?unit=` (`exchange.orgstock.write`,
  REQ-XCH-045): the direct members of a unit the member is a **Logistician of** (own flag or
  leadership seat in exactly that unit, decided from the database), as client-scoped pseudonym
  `ref`s with names; members withholding their name are left out.
- **Gaps.**
  1. On an exchange route the acting member holds only `ROLE_EXCHANGE_MEMBER` and capability
     authorities (REQ-XCH-009), so `BookInPolicy#mayBookInFor` answers `false` for every other
     member: today a store line for anyone but the member would always be refused. Foreign
     receivers need the ADR-0257 pattern of the org stock — decide from the database.
  2. The roster's reach (direct members of a Logistician unit) is narrower than the web's (any
     member sharing an editable unit, which includes leadership above the unit). Accepting the
     roster as the rule is a deliberate narrowing to record.
  3. The roster names members by `ref`, never by user id; a store line would carry `holder: <ref>`
     and the server maps it back, as `book-in` does (`HOLDER_UNKNOWN` when it is not on the
     roster).

### 2.4 Job order

- **Web.** `jobOrders` = `fetchActiveJobOrders()` → `GET /api/v1/orders/lookup`
  (`JobOrderQueryService#findAllActiveReference`: empty unless `canViewJobOrders`, then every
  active order with `canSeeJobOrderInScope`). The template shows per line only orders whose
  `requiredMaterialIds` contain the line's material; the JS disables the select while `personal`
  is ticked.
- **Rule.** `personal` with a job order is 400; the order must exist and
  `JobOrderItemService#requiredMaterialIds(order)` must contain the material
  (`assertMaterialRequiredByJobOrder`, REQ-ORDERS-018). The store path does **not** check that the
  caller may see the order — the visibility lives only in the web's list.
- **Exchange.**
  - `GET /exchange/v1/me/org-demand` with `exchange.demand.read` + `exchange.demand.details`
    (REQ-XCH-047): `orders[]` with `id` (the id an earmark names), `number`, `status`, per-material
    open needs — only for units the member is a Logistician of. Without `.details`, or for a plain
    member, the demand is anonymous: no ids.
  - `GET /exchange/v1/org-stock/targets?unit=&material=<bt>` (`exchange.orgstock.write`): the open
    and in-progress orders needing that material for which the unit, a special command or no unit
    is responsible — again only for a Logistician of the unit.
- **Gaps.** A plain member who sees job orders on the web and may earmark refinery output to one
  of them has no Exchange source of order ids. A new read would be the web's
  `/orders/lookup` projection filtered by material, scoped like `org-demand` (membership-based
  `canViewJobOrders`, from the database), under `exchange.refinery.read` or a demand scope. The
  missing visibility check on the store path should be closed for the exchange op either way
  (follow-up issue rather than a silent divergence).

### 2.5 Personal and note

- **Web.** Checkbox `storePersonal_*` (help text: personal rows are visible only to the owner and
  take no job order or mission); textarea `storeNote_*`, `maxlength=1000`.
- **Rule.** `personal` `null` = `false`; with a job order 400 (REQ-INV-035); `note` `@Size(max =
  1000)`, trimmed to null.
- **Exchange.** Nothing to list. The stock resource's split (`personal` vs pools) uses the same
  vocabulary (`docs/exchange/resources/stock.md`).

### 2.6 Material, quality, amount

- **Web.** One preset line per output good of the order (`addOrderModel`): output material,
  `quality`, and `outputQuantity` — pieces as-is, SCU as `outputQuantity / 100` rounded with
  `GET /api/v1/refinery-orders/settings` `roundingMode` to three decimals; a line with a known
  amount is `amountFixed` and cannot be split, otherwise `+` duplicates it.
- **Rule.** `@NotNull` material id (must exist; the service does not check it is one of the
  order's goods), `@QualityValue` 0…1000, `@ValidQuantityAmount`; the stored amount is written
  back into the matching good (`updateGoodOutputQuantity`, ×100 for SCU).
- **Exchange.** No Exchange read of refinery orders exists yet; the refinery draft
  (`POST /exchange/v1/me/drafts/refinery-orders`) is write-only. Material refs resolve with
  `POST /exchange/v1/catalog/resolve` (any exchange scope) or inline in a change set; quantities
  use the `quantity` format (`{amount, unit}`). The rounding mode has no Exchange source; a client
  sending exact `quantity` values does not need it.
- **Needed.** The refinery read of the ground rules (#3129, `exchange.refinery.read`) must carry
  each good's output material as an `item-ref` (`bt`), quality and output quantity so a client can
  build the lines. Whether a line may name a material outside the order's goods is a contract
  decision; the web server accepts it today.

## 3. What a contract has to decide

1. **Self-only v1 or foreign receivers.** Self-receiver lines are covered by existing reads.
   Foreign receivers need a database-decided rule (roster of a Logistician unit, `holder` by
   `ref`) because `BookInPolicy` cannot answer on an exchange request.
2. **Job-order source for plain members.** Reuse the Logistician-only `demand.details` /
   `org-stock/targets`, or add a material-filtered lookup of visible active orders.
3. **Org unit for a foreign receiver.** Only the roster's unit, or a new read of the receiver's
   memberships.
4. **Close the store path's job-order visibility gap** for the exchange op (and propose it for the
   web path as a follow-up issue).

## 4. Sources

- `backend/…/refinery/internal/RefineryOrderStoreService.java`, `RefineryOrderStoreDto.java`,
  `RefineryOrderStoreItemDto.java`, `refinery/internal/RefineryAccessPolicy.java`,
  `refinery/web/RefineryOrderController.java` (`POST /api/v1/refinery-orders/{id}/store`)
- `backend/…/catalogue/api/OfferedLocations.java`, `catalogue/api/LocationRepository.java`,
  `catalogue/internal/LocationService.java`, `catalogue/web/LocationController.java`
- `backend/…/inventory/api/StockCommands.java`, `inventory/api/BookInRule.java`,
  `inventory/api/BookInPolicy.java`, `inventory/internal/InventoryStockCommands.java`
- `backend/…/scope/api/OrgUnitStampingService.java`,
  `orgunit/web/MemberMembershipController.java`, `joborder/internal/JobOrderQueryService.java`,
  `identity/web/UserController.java`
- `backend/…/exchange/internal/ExchangeCatalogService.java`, `ExchangeLocationResolver.java`
- `frontend/…/refinery/web/RefineryOrderPageController.java`,
  `refinery/client/RefineryBackendClient.java`, `kernel/backend/CachedCatalog.java`,
  `templates/refinery/refinery-orders-details.html`,
  `static/js/refinery/refinery-orders-details.js`, `static/js/kernel/krt-user-search.js`
- `docs/exchange/resources/catalog.md`, `org-units.md`, `org-stock.md`, `org-demand.md`,
  `stock.md`, `drafts.md`
- `docs/specs/external-exchange.md` (REQ-XCH-001, -004, -009, -018, -045, -047, -048),
  `docs/specs/security-and-access.md` (REQ-SEC-039)
