> **Doc type:** Living plan — kept in sync with `main` while the Materialbörse epic is in flight;
> freeze and point at `docs/specs/materialboerse.md` once it ships. Last reviewed: 2026-07-07.
> **Owner area:** MARKET · **Related:** design proposal `.claude/skills/das-kartell-design/proposals/materialboerse-final.html` + `…/materialboerse-final.js`, ADR-0081 (to be written), REQ-MARKET-001…

# Materialbörse — Implementation Plan (Flotte & Logistik)

## 0. Binding rule (top of the order)

The final design (`proposals/materialboerse-final.html` + `.js`, master-detail is the locked
layout — `VARIANTS = ["md"]`) is the **binding spec**. Nothing in layout, spacing, colour, copy,
ordering or interaction is "improved", added or dropped. Every visible element maps to an
**existing KRT design-system class/token**. The prototype's inline `.mb-*` / `.mrow` / `.dp-*`
classes are **demo styling only** — they are re-expressed on the canonical DS component classes
(`.master-detail` / `.master-list` / `.master-row` / `.detail-pane`, `.markdown-content`,
`.tab-nav`, `.krt-combobox`, `.chip`, `.squadron-badge`, `.krt-modal*`, …). New CSS in
`materialboerse.css` is **page composition only** (grid/spacing), never new visual vocabulary.

**Abgleich-Schritt (mandatory, part of every step):** after each implementation step, compare the
rendered screen against `proposals/materialboerse-final.html` (open the prototype, diff region by
region — head+CTA, tabs, filter bar, master list row, detail pane, facts, remark, interessenten,
actions, release modal, Lager checkbox row). A visual divergence from the prototype is a defect —
the prototype wins.

---

## 1. Feature scope (verbatim from the Auftrag, bound to the prototype)

A central, all-visible marketplace for materials released for trade. It shows **only**: which
player offers which material, in which quality and quantity. Negotiation + handover happen
off-tool between players.

1. **Release in the Lager** — a per-row checkbox "Für Börse freigeben" on a Lager stock row.
   Activating opens the **remark dialog** (KRT modal): a read-only fact strip (Material · Qualität
   as a plain number · Menge SCU) + a Markdown textarea (max 20 000 chars, live counter). Release →
   the offer appears in the Börse immediately. Un-checking deactivates (removes it from the Börse).
   **The Standort/location is never transmitted to the Börse.**
2. **Börse view = master-detail** (locked): lean list left, full offer right. Head with one filled
   CTA "Material anbieten" (opens the same release dialog).
3. **Tabs**: "Alle Angebote" (count) · "Meine Angebote" (count). The count lives in the tab — no
   separate result-count row.
4. **Filter/sort**: search (material or player), min. quality (0–1000), min. quantity (SCU), sort
   (Qualität ↓ · Menge ↓ · Material A–Z · Neueste zuerst). **No "nur ohne Interessenten" filter**
   (deliberately removed — the prototype's `buildToolbar` does not render it).
5. **Detail pane**: Material (title) · "von {player}" + squadron badge · own offer → "Dein Angebot"
   marker · facts Qualität (plain number, no "/ 1000") · Menge (SCU) · Freigegeben. **No "Kategorie"
   field.** Below: full remark as rendered Markdown (`.markdown-content`) and the Interessenten.
6. **Anonymity (core)**: no location/handover anywhere. Interessenten names are visible **only to
   the offer's owner**; everyone else sees only the count ("N Interessenten" / "Keine Interessenten").
7. **Actions**: foreign offer → "Interesse anmelden" (`.btn-outline`, toggles to "zurückziehen").
   Own offer → "Bemerkung bearbeiten" (`.btn-ghost`) + "Angebot deaktivieren" (`.btn-quiet-danger`),
   shown only to the owner.

---

## 2. Screen inventory (each region of the prototype = one build unit)

Two screens are in scope.

### Screen A — `/materialboerse` (the Börse, master-detail)
- **A1 Page head** (`.s-head` in the proto) — eyebrow "Flotte & Logistik", h3 "Materialbörse", and
  the single filled CTA **"Material anbieten"** (`.btn.btn--cta`, plus-icon). Opens the release
  modal in `mode:new`.
- **A2 Tab row** — `.tab-nav[role=tablist]` with two `.tab`s ("Alle Angebote", "Meine Angebote"),
  each carrying a `.tab-count`. Plus the search box on the same row (right-aligned).
- **A3 Search** — `<input type="search">` "Material oder Spieler …" (material OR owner substring).
- **A4 Filter bar** — Min. Qualität (0–1000), Min. Menge (SCU), Sortierung select.
- **A5 Master list** — `.master-list` of `.master-row`s: material name, squadron badge + owner,
  "Q {qual} · {menge} SCU · {ago}", and a mini interessenten indicator; `is-active` = selected,
  own offers marked.
- **A6 Detail pane** — `.detail-pane`: head (h4 material, "von {owner}" + badge, "Dein Angebot"
  chip if own, mobile "← Liste" back button), facts strip (Qualität/Menge/Freigegeben, plain
  numbers), body split: **Bemerkung** (rendered `.markdown-content`) + anonymity note left,
  **Interessenten** + action buttons right.
- **A7 Empty state** — `.empty-state` "Keine Angebote" + reset-filter button (when filters exclude
  everything).
- **A8 Loading indicator** — `.krt-loading-indicator` during async list/detail swaps.

### Screen B — Lager release integration (`/inventory/my`, the tree table)
- **B1 "Für Börse" column** — a new checkbox column on the **leaf-entry** row (one `InventoryItem`)
  in the lazily-loaded fragment `fragments/inventory-stack-entries.html`.
- **B2 Status cell** — `.chip.chip--primary` "Auf Börse" when an active offer exists for that item,
  else a muted "privat".
- **B3 Release modal** — the shared KRT modal (fact strip + Markdown textarea + 20 000 counter +
  "Standort bleibt privat" note), opened from the checkbox (`mode:lager`) and from A1 (`mode:new`)
  and from the detail edit action (`mode:edit`).
- **B4 Info note** — the `.fb-note` line explaining the flow + "Standort bleibt privat".

---

## 3. Element → DS class / token mapping (authoritative; prototype demo class → real DS class)

| Prototype region / element | Prototype demo class | **Canonical DS class / token to use** |
| :-- | :-- | :-- |
| Page shell / head | `.s-head`, `.s-eyebrow`, `h3` | Standard page `<main>` head as in leitung/inventory: `.page-header`/`.panel-header` + `#{…}` title; eyebrow via existing head styling |
| Primary CTA "Material anbieten" | `.btn.btn--cta` | `.btn.btn--cta` (unchanged — the one filled action per context) |
| Tabs + counts | `.tab-nav`/`.tab`/`.tab-count` | `.tab-nav` / `.tab` / `.tab-count` (DS; `role=tablist`, arrow keys, `?tab=` deeplink) |
| Search field | `.mb-search input[type=search]` | plain DS input (`styles.css` input) inside a composition wrapper; icon from sprite |
| Min. Qualität / Min. Menge inputs | `.mb-filt input[range|number]` | DS number/range inputs; `.scu-hint` bubble on the SCU field; **composition** wrapper only |
| Sort select | `.chip-select` | `.chip-select` (DS uppercase chip select w/ orange chevron) |
| Master-detail shell | `.mb-md` | **`.master-detail`** |
| Master list | `.mb-mlist` | **`.master-list`** |
| Master row (active/own) | `.mrow`/`.active`/`.mine` | **`.master-row`** + `.is-active`; "own" marker via a `.chip`/left-rail composition class |
| Squadron badge | `.squadron-badge(-foreign)` | `.squadron-badge` / `.squadron-badge-foreign` (unchanged) |
| Mini interessenten count | `.int` + `icon(users)` | `.chip.chip--muted` / `.chip.chip--primary` with users sprite icon |
| Detail pane | `.mb-detailpane` | **`.detail-pane`** |
| Detail head title / "von {owner}" | `.dp-head`/`.dp-title` | `.detail-pane` head composition; title = existing headline styling |
| "Dein Angebot" marker | `chip chip--primary` | `.chip.chip--primary` |
| Facts strip (Qualität/Menge/Freigegeben) | `.dp-facts` + `.kvmini` | **`.facts-bar`** (or `.kv-compact`) — plain numbers, **no gauge, no "/1000", no Kategorie** |
| Remark (rendered Markdown) | `.remark .md` | **`.markdown-content`** (server-rendered via `@markdown`) |
| Anonymity note | `.anon-note` | small muted note composition + shield sprite icon |
| Interessenten (owner) name chips | `.int-names .chip` | `.chip` list |
| Interessenten (non-owner) count | `.intChip` | `.chip.chip--muted` / `.chip.chip--primary` |
| Action: Interesse anmelden | `.btn-outline` | `.btn-outline` |
| Action: Interesse zurückziehen | `.btn-ghost` | `.btn-ghost` |
| Action: Bemerkung bearbeiten | `.btn-ghost` | `.btn-ghost` |
| Action: Angebot deaktivieren | `.btn-quiet-danger` | `.btn-quiet-danger` |
| Empty state | `.empty-state` | `.empty-state` (DS) |
| Loading | (custom) | `.krt-loading-indicator` + `.krt-spinner` + `.krt-loading-label` |
| Toast feedback | `.notification-toast` | `.notification-toast` via `showFrontendSuccessToast` / `…ErrorToast` |
| Release/Bearbeiten modal | `.krt-modal*` | `.krt-modal-overlay` / `.krt-modal` (+ head/body/foot); focus-trap + Esc |
| Modal fact strip | `.fg-context`/`.fg-fact` | `.facts-bar` / `.kv-compact` composition (read-only) |
| Modal Markdown textarea + counter | `textarea` + `[data-charcount]` | DS textarea + a small counter element (composition) |
| Lager "Für Börse" checkbox | `.lg-cb input` | DS checkbox inside the existing tree-table leaf row |
| Lager status chip | `.chip.chip--primary` / muted | `.chip.chip--primary` "Auf Börse" / `.text-muted` "privat" |
| Material select (mode:new) | `<select data-f=mat>` | **`.krt-combobox`** (searchable select, REQ-FE-011) |

**Master list note:** the prototype's `.master-row` shows two stacked lines + a right-side count.
`.master-detail` in the DS specimen is a simpler `<span>…<span>` row. The row's internal
2-line-with-badge composition (name / badge+owner / meta line) is the one place we add composition
CSS (see §4) — no new colours, only layout inside `.master-row`.

---

## 4. New CSS — `frontend/src/main/resources/static/css/materialboerse.css` (composition ONLY)

Modelled on `leitung.css` / `promotion-admin.css` (page-scoped composition). Every rule is layout
(grid, gap, min-width, alignment) using existing tokens (`--color-*`, spacing) — **no new colours,
no rounded corners (square-first), no new component look.** Justified rule list:

1. `.mb-page` grid/toolbar composition — the head + tab-row + filter-bar + master-detail vertical
   rhythm (gaps/spacing). *Justification:* page layout, not a component.
2. `.mb-filters` flex wrap — lay out the 3 filter controls + labels responsively. *Layout only.*
3. `.master-detail` column template override (`grid-template-columns: 320px 1fr`) + the mobile
   collapse (`@media` → single column, list hidden when a row is active, back-button shown). *The
   DS specimen itself overrides `grid-template-columns` per page; the mobile collapse is composition.*
4. `.master-row` internal 2-line composition (`.mb-row-main` name / `.mb-row-sub` badge+owner /
   `.mb-row-meta` "Q… · … SCU · …" + the right-side count). *Layout inside the DS row; uses
   `--color-gray-2` etc. only.*
5. `.detail-pane` head + facts + body composition (`.dt-grid` 1.6fr/1fr → 1fr on narrow; the
   left Bemerkung / right Interessenten split). *Layout only.*
6. Modal field composition (`.fg-fields` grid for the mode:new material/menge/qualität row; the
   fact strip + counter row). *Layout only.*
7. `tabular-nums` on numeric spans (quality, SCU, counts) + German decimal via
   `#numbers.formatDecimal`. *Typography token, per Auftrag.*

Any rule that would introduce a colour, a radius, a shadow, or a new "look" is **forbidden** here —
if the prototype needs one that the DS lacks, that is a DS gap to raise with @greluc **before**
coding it (Auftrag §10).

---

## 5. Backend design

### 5.1 Data model (new area `MARKET`)
Build on **`InventoryItem`** (Lager — the only model with material / quality 0–1000 / SCU amount /
location; location stays private and is never joined into any Börse query).

- **`MaterialExchangeOffer`** (new entity, `model/`, extends `AbstractEntity<UUID>`):
  - `@ManyToOne(optional=false, LAZY) InventoryItem inventoryItem` — the source Lager row
    (FK `inventory_item_id`, `ON DELETE CASCADE`). Material / quality / amount / owner / squadron
    are read **live** from it (single source of truth, location never read; decision D1).
  - `@ManyToOne(optional=false, LAZY) User owner` — denormalised = `inventoryItem.user` at release
    (so the board and "Meine Angebote" filter never touch the item join for ownership).
  - `@ManyToOne(LAZY) OrgUnit owningOrgUnit` — for the squadron badge / foreign detection; stamped
    from the item's `owningOrgUnit`.
  - `@Column(length=20000) String remark` — Markdown source (raw; rendered server-side on display).
  - `@Enumerated(STRING) MaterialExchangeOfferStatus status` — `ACTIVE` / `DEACTIVATED`.
  - `Instant releasedAt` — "Freigegeben vor X".
  - inherited `@Version version`, `createdAt`, `updatedAt`.
  - **Uniqueness:** partial-unique on `inventory_item_id WHERE status='ACTIVE'` (one active offer per
    Lager row) so re-checking the box re-activates rather than duplicates.
- **`MaterialExchangeInterest`** (new entity, MaterialClaim-style **independent aggregate** — no
  mapped collection on the offer, so upsert/withdraw never bumps the offer `@Version`):
  - `@ManyToOne(optional=false, LAZY) MaterialExchangeOffer offer` (FK, `ON DELETE CASCADE`).
  - `@ManyToOne(optional=false, LAZY) User interestedUser`.
  - inherited fields. **Unique `(offer_id, interested_user_id)`** → "Interesse anmelden" is an
    idempotent upsert; withdraw deletes. Find-or-create-race handling per the CLAUDE.md
    "last-writer-wins → retry in REQUIRES_NEW" rule if needed (interest is insert/delete, so a
    unique-violation catch + treat-as-success is enough).

### 5.2 Endpoints (`/api/v1/material-exchange`, `MaterialExchangeController`)
All read = **members only**; per-user writes gated on ownership/edit-scope in the service.
- `GET  /offers?tab=alle|mein&q=&minQual=&minMenge=&sort=qual|menge|mat|neu&page=&size=` →
  `PageResponse<MaterialExchangeOfferDto>` (board list; interessenten redacted per §5.4).
- `GET  /offers/{id}` → `MaterialExchangeOfferDto` (detail; names only if caller == owner).
- `POST /offers` — release a Lager item: body `{ inventoryItemId, remark, version? }`; 201.
  (`mode:new` from the CTA also lands here — it first needs the user to pick one of **their own**
  Lager items via the combobox.)
- `PUT  /offers/{id}/remark` — edit remark: `{ remark, version }` → optimistic-lock via
  `OptimisticLock.check(...)`.
- `POST /offers/{id}/deactivate` — set `DEACTIVATED` (un-check / "Angebot deaktivieren").
- `POST /offers/{id}/interest` — register interest (upsert, idempotent).
- `DELETE /offers/{id}/interest` — withdraw interest.
- `GET  /my-releasable?q=` → the caller's own Lager items eligible for `mode:new` combobox (thin
  reuse of the inventory read, owner-scoped).

`@PreAuthorize`: class-level `@PreAuthorize("isAuthenticated()")`; reads narrowed to
`hasRole(Roles.KRT_MEMBER)` (exclude guests — decision D2); release/remark/deactivate gated
by `@ownerScopeService.canEditInventoryItem(#… )` / ownership of the offer; interest by
`isAuthenticated()` (any member, but not on your own offer — enforced in service).

### 5.3 DTOs + mapper (`dto/`, `mapper/`)
- `MaterialExchangeOfferDto(UUID id, MaterialReferenceDto material, String ownerHandle,
  SquadronReferenceDto squadron, boolean foreign, boolean mine, Integer quality, Double amount,
  Instant releasedAt, String remarkHtml?, int interestCount, List<String> interestedHandles?,
  boolean iAmInterested, Long version)` — read DTO echoes `version`; `interestedHandles` is **null
  for non-owners** (redaction), `remarkHtml` server-rendered via `@markdown` on the frontend (offer
  carries raw `remark`; see §6). **No location field. No category field.**
- `MaterialExchangeReleaseRequest(@NotNull UUID inventoryItemId, @Size(max=20000) String remark)`.
- `MaterialExchangeRemarkUpdateRequest(@Size(max=20000) String remark, @NotNull @Min(0) Long version)`.
- MapStruct `MaterialExchangeOfferMapper` (`@Mapper(config=CentralMapperConfig.class)`); interest
  count/handles + `mine`/`iAmInterested`/`foreign` computed in the service (viewer-relative), not
  the mapper.

### 5.4 Visibility & anonymity (server-enforced)
- **Board is org-wide** — every `ACTIVE` offer is visible to every member regardless of
  `owningOrgUnit` (no org-scope filter; it is a global trade board — decision D3). The `foreign`
  flag = offer's squadron ≠ viewer's squadron (drives `.squadron-badge-foreign`).
- **Interessenten names**: the service returns `interestedHandles` **only when** the viewer is the
  offer owner; otherwise only `interestCount`. Mirrors the existing guest-field-redaction pattern.
  Location is **never** in the DTO or any query projection.

### 5.5 Optimistic locking
- Offer remark edit / (item version echo on release) → `OptimisticLock.check(...)` (skip-when-null
  semantics) — **not** the Mission section-counter family.
- Interest upsert/withdraw is a boolean-ish toggle → **no client version**; idempotent via the
  unique constraint (+ unique-violation-as-success), per the CLAUDE.md find-or-create rule.

### 5.6 Audit (new `AuditDomain.MARKET`, REQ-AUDIT-001)
Add to `AuditDomain` (10th value) and `AuditEventType`:
`MARKET_OFFER_RELEASED`, `MARKET_OFFER_DEACTIVATED`, `MARKET_REMARK_UPDATED`,
`MARKET_INTEREST_REGISTERED`, `MARKET_INTEREST_WITHDRAWN`, plus the standard
`MARKET_AUDIT_EXPORTED` / `MARKET_AUDIT_PURGED` pair. `auditService.record(...)` at each mutation,
inside the write tx, **PII-free details** (ids / `materialId` / `quality` / `remarkLen` — never the
remark body, never usernames). Add the `case MARKET ->` branch to the exhaustive purge switch.
Wire the frontend audit viewer: `DOMAINS` + `EVENT_TYPES_BY_DOMAIN` (still ≤10 → `Map.of` OK) in
`AdminAuditLogPageController`, the `audit-log.html` filter, and DE/EN labels
(`admin.audit.domain.MARKET`, `admin.audit.event.MARKET_*`). Reconcile REQ-AUDIT-001/002/003/004 text.

### 5.7 Monitoring (REQ-OBS-011)
- `basetool_audit_events_total{domain=MARKET}` is auto-covered by adding the `AuditDomain`. Decide
  whether `MARKET` joins the `AuditDomainSilenceAnomaly` exclusion list (a young board may be quiet).
- Add a queue gauge `basetool.material.exchange.active.count` (active offers) + optional
  `…oldest_age_seconds`, name constants in `MetricNames`, registered in `BusinessMetricsCollector`
  with a `countByStatus`-style repo method. Update `monitoring/prometheus/alerts/business.yml` +
  Grafana dashboard `07-*.json`. No new public endpoint → no blackbox probe.

### 5.8 Migration `V210__add_material_exchange.sql` (next free version confirmed = V210)
`CREATE TABLE material_exchange_offer` (UUID pk; `inventory_item_id UUID NOT NULL REFERENCES
inventory_item(id) ON DELETE CASCADE`; `owner_id`, `owning_org_unit_id` FKs; `remark VARCHAR(20000)`;
`status VARCHAR(16) NOT NULL`; `released_at TIMESTAMP WITH TIME ZONE NOT NULL`; `version BIGINT NOT
NULL DEFAULT 0`; `created_at`/`updated_at TIMESTAMP WITH TIME ZONE NOT NULL`) + partial-unique index
`… ON material_exchange_offer(inventory_item_id) WHERE status='ACTIVE'` + status/owner indexes.
`CREATE TABLE material_exchange_interest` (UUID pk; `offer_id … ON DELETE CASCADE`; `interested_user_id`;
version/timestamps) + **unique `(offer_id, interested_user_id)`**. `ddl-auto=validate` — columns must
match the entities exactly.

### 5.9 Validation
`@Size(max=20000)` on remark (paired with `@Column(length=20000)`), `@NotNull` on
`inventoryItemId`, `@NotNull @Min(0) Long version` on remark-update. Messages resolve from DE/EN
bundles.

---

## 6. Frontend design

### 6.1 Page (mirrors the Leitung slice)
- `MaterialboersePageController` (`@Controller @RequestMapping("/materialboerse")`,
  `@PreAuthorize(hasRole KRT_MEMBER)`): `@GetMapping` serves full page or `?fragment=…` for AJAX
  swaps; `/ajax` `@ResponseBody` write proxies to `backendApiClient` relaying RFC-7807 as
  `{code,detail}` (the Leitung `proxy(...)` pattern, incl. explicit 409 branch).
- `templates/materialboerse.html` — head fragment with `additionalLinks=~{::extraLinks}` loading
  `/css/materialboerse.css` + CSRF metas; sidebar replace; a `th:fragment` for the swappable
  board region; i18n bootstrap `<script th:inline>` + `<script th:src="@{/js/materialboerse.js}"
  defer nonce>`; toast fragment.
- Fragments: `fragments/materialboerse-list.html` (master list), `…-detail.html` (detail pane),
  `…-modal.html` (release/edit modal). The master list + detail are separate swap targets so a
  select re-renders only the detail (REQ-FE-005/013).
- **Markdown**: the remark renders server-side — `th:with="html=${@markdown.render(offer.remark)}"`
  then `th:utext` into `.markdown-content` (the operation-detail pattern). **No client Markdown lib.**

### 6.2 Static JS `static/js/materialboerse.js` (IIFE, `window.krtFetch`)
- Tabs (arrow keys, `?tab=`), search/filter/sort → `krtFetch.swap` of the list fragment
  (per-container sequence guard REQ-FE-013).
- Select a row → `krtFetch.swap` of the detail fragment.
- Interesse / zurückziehen / deaktivieren / remark-save → `krtFetch.write` (JSON), `syncVersion`
  into `[data-version]`, `serialize:'materialboerse'`, toast on success, 409→reload-confirm.
- Release modal (mode new/lager/edit): KRT modal markup, focus-trap + Esc, 20 000 live counter,
  material picker = `krt-searchable-select` for `mode:new`. No `confirm/alert/prompt`;
  destructive deactivate uses `showKrtConfirm`.
- i18n strings via `window.materialboerseI18n` (`/*[[#{…}]]*/` idiom).

### 6.3 Lager checkbox integration (Screen B)
- Add the "Für Börse" checkbox + status cell to `fragments/inventory-stack-entries.html` leaf rows
  (the fragment is lazily loaded on stack-expand — the checkbox must live **there**, not in
  `inventory-my.html`). Extend `--tree-cols` for the new column; keep numbers `tabular-nums`.
- Toggle → open the shared release modal (checked, `mode:lager`) or POST `/deactivate` (unchecked,
  `showKrtConfirm`). On success, swap the status cell (`chip--primary "Auf Börse"` ↔ `text-muted
  "privat"`) in place; echo the offer/item `data-version`.
- Reuse `materialboerse.js`' modal, loaded on the inventory page too (or a shared small module).

### 6.4 Live update & multi-user sync (REQ-FE-001…014)
- **Single-user live update**: every mutation updates the DOM in place via `krtFetch` — no full
  reload (only the sanctioned 409-confirm / bfcache). Derived UI (tab counts, Lager status cell)
  refreshed in the same flow.
- **Multi-user peer sync (REQ-FE-010)**: the board is a shared surface, so a peer releasing/
  deactivating/registering interest should propagate. The existing presence WebSocket is
  **mission-scoped only**. Decision D4: build a generic `/ws/materialboerse` presence relay
  mirroring the 3 mirror points (client broadcast map ↔ `BROADCASTABLE_SECTIONS` ↔ receiver apply
  map — derived from one source to avoid the objectives/frequencies drift), scoped as a single
  board channel with opaque section keys `board` / `detail:{id}`. Fully satisfies REQ-FE-010.

### 6.5 Navigation
Add one anchor under `data-group-key="logistics"` in `fragments/sidebar.html`:
`<a th:href="@{/materialboerse}" data-testid="nav-materialboerse" th:text="#{nav.materialboerse}">`.
Gate `sec:authorize="hasRole('KRT_MEMBER')"` (or `isAuthenticated()` per D2).

---

## 7. i18n keys (all three bundles; DE `\uXXXX`-escaped, EN literal)

Namespace **`materialboerse.*`** (+ audit keys under `admin.audit.*`). Non-exhaustive list to add:
- Nav: `nav.materialboerse`.
- Page: `materialboerse.title`, `.eyebrow`, `.intro`, `.cta.offer`.
- Tabs: `.tab.all`, `.tab.mine`.
- Filters: `.filter.search.placeholder`, `.filter.minQuality`, `.filter.minAmount`, `.filter.sort`,
  `.sort.qual`, `.sort.menge`, `.sort.mat`, `.sort.neu`.
- List/detail: `.row.meta` pattern, `.detail.from`, `.detail.mine`, `.facts.quality`,
  `.facts.amount`, `.facts.released`, `.remark.label`, `.anon.note`, `.interest.label`,
  `.interest.count.zero`, `.interest.count.one`, `.interest.count.many`, `.interest.ownerNote`,
  `.interest.viewerNote`.
- Actions: `.action.interest`, `.action.interest.withdraw`, `.action.remark.edit`,
  `.action.deactivate`, `.action.deactivate.confirm.title/body`.
- Modal: `.modal.release.title`, `.modal.edit.title`, `.modal.remark.label`, `.modal.remark.hint`,
  `.modal.remark.counter` (`{0} / 20.000`), `.modal.privacy.note`, `.modal.submit.release`,
  `.modal.submit.save`, `.modal.material.label`.
- Lager: `inventory.forExchange` (column), `inventory.forExchange.on` ("Auf Börse"),
  `inventory.forExchange.private` ("privat"), `inventory.forExchange.note` (the `.fb-note`).
- Toasts: `.toast.released`, `.toast.deactivated`, `.toast.remarkSaved`, `.toast.interest.added`,
  `.toast.interest.removed`; errors reuse `notification.error.*` + `frontend.ajax.conflict.*`.
- Empty: `.empty.title`, `.empty.text`, `.empty.reset`.
- Audit: `admin.audit.domain.MARKET`, `admin.audit.event.MARKET_OFFER_RELEASED` (+ the 4 others +
  export/purge).

---

## 8. Docs-as-code deliverables (same PR)

1. **Spec** `docs/specs/materialboerse.md` (new area `MARKET`, `REQ-MARKET-001…`): the offer/
   interest model, org-wide visibility, interessenten anonymity, no-location invariant, 20 000-char
   Markdown remark, master-detail UI binding, release-in-Lager flow. Register the row in
   `docs/specs/INDEX.md` and add `MARKET` to the area vocabulary.
2. **ADR** `docs/adr/0081-materialboerse-offer-model.md` — the data-model + visibility + anonymity
   decision (offer-over-InventoryItem vs columns; live-read vs snapshot; org-wide public board;
   signal-only no stock mutation). Add the index row in `docs/adr/README.md`.
3. **Audit** `docs/specs/audit.md` — new Materialbörse bullet in REQ-AUDIT-001; 10-way viewer
   (REQ-AUDIT-002); export/purge (003/004).
4. **Observability** `docs/specs/observability.md` — the MARKET metric/label decision (REQ-OBS-011);
   `monitoring/` alert + dashboard reconciliation.
5. **Frontend AJAX** `docs/specs/frontend-ajax-mutations.md` — record the board's live-sync channel
   (or the amended REQ-FE-010 scope per D4).
6. **Roles** `ROLES_AND_PERMISSIONS.md` — new `### 3.x Materialbörse` matrix subsection.
7. **README.md** — feature overview line under Flotte & Logistik.
8. **Wiki** (`basetool.wiki`, German) — new "Materialbörse" page (committed/pushed in that repo,
   German content, English commit msg).
9. **CHANGELOG.md** — `## [Unreleased]` bullet(s), German, literal umlauts.
10. **`openapi.json`** — regenerated by `OpenApiGeneratorTest` (never hand-edited).

---

## 9. Tests (every feature ships with tests)
- Backend: `MaterialExchangeOfferServiceTest` (release/deactivate/remark opt-lock, interessenten
  redaction by viewer, org-wide visibility, **no location in any projection**, interest upsert
  idempotency + unique-violation-as-success), `MaterialExchangeInterestServiceTest`,
  `MaterialExchangeControllerTest` (MockMvc: `@PreAuthorize`, `@Valid` 400s, 409), mapper test,
  audit-recording test, `ArchitectureTest` stays green, `OpenApiGeneratorTest` regenerates the doc.
- Frontend: controller MockMvc (fragment vs full page, proxy 409 branch), i18n bundle test (umlaut
  escaping), and the E2E label so Playwright covers the flow.

---

## 10. Commit plan (small, checkable; `./gradlew check` + Abgleich after each)

0. ✅ **Submodule pointer bump** (done — commit `f2f3836`).
1. **Backend model + migration** — entities, `V210`, repositories; `./gradlew :backend:test`.
2. **Backend service + DTOs + mapper + opt-lock + audit + metrics** — `MaterialExchangeService`,
   redaction, audit events, `MetricNames`/collector; tests.
3. **Backend controller + security + validation + openapi** — endpoints, `@PreAuthorize`,
   regenerate `openapi.json`; MockMvc tests.
4. **Frontend page skeleton** — controller, `materialboerse.html`, empty fragments, nav item,
   `materialboerse.css` (composition), i18n keys; renders full page.
5. **Frontend master-detail + filters/tabs + krtFetch swaps** — list/detail fragments, JS;
   **Abgleich vs prototype** (list row, detail, facts without "/1000"/Kategorie, "Dein Angebot").
6. **Frontend release/edit modal + Markdown render + interessenten + actions** — modal, `@markdown`,
   owner-only names, action ladder; Abgleich.
7. **Lager checkbox integration** — `inventory-stack-entries.html` column + status cell + modal
   wiring; Abgleich vs the "Freigabe im Lager" section.
8. **Live multi-user sync** (per D4 outcome).
9. **Docs-as-code** — spec/ADR/audit/observability/roles/README/CHANGELOG/wiki + monitoring.
10. **Full sweep** — `spotlessApply` (+ `:frontend:prettierApply`), `./gradlew check`, checklist
    fully ticked; PR assigned `greluc`, labels `enhancement`+`backend`+`frontend`+`e2e`(+`database`).

---

## 11. Decisions (locked by @greluc 2026-07-07)

- **D1 — Offer facts = live-read.** Material/quality/amount are read **live** from the linked
  `InventoryItem` (single source of truth, no drift; location never read). On item delete the offer
  is deactivated (`ON DELETE CASCADE` removes offer rows).
- **D2 — Read audience = `KRT_MEMBER`.** Authenticated-but-roleless guests do **not** see the board.
- **D3 — Board scope = org-wide public board.** Every `ACTIVE` offer is visible to every member
  regardless of `owningOrgUnit`; `foreign` flag drives the foreign squadron badge. Interessenten
  names stay owner-only.
- **D4 — Multi-user peer sync = dedicated board WebSocket relay.** Build a generic
  `/ws/materialboerse` presence relay mirroring the 3 mirror points (client broadcast map ↔
  `BROADCASTABLE_SECTIONS` ↔ receiver apply map), derived from one source to avoid drift. Opaque
  section keys `board` / `detail:{id}`. Fully satisfies REQ-FE-010 as written.

## 12. Definition of Done (Auftrag)
Submodule bumped ✅ · `/materialboerse` visually identical to the prototype (master-detail, tabs,
filters incl. Material A–Z, facts without "/1000" and without Kategorie, "Dein Angebot", owner
actions, interessenten anonymity) · Lager release (checkbox → Markdown dialog, 20 000 counter) +
deactivate work, location stays private · only DS classes/tokens, `materialboerse.css` = pure
composition · i18n de/en, KRT modals/toasts, responsive, a11y · `./gradlew check` green · conformity
checklist fully ticked.
