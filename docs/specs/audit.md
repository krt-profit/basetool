# Activity audit logs — Lager, Aufträge, Raffinerie, Mein Inventar

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-08-02.

Area: `AUDIT` · Related: [`bank.md`](bank.md) (the bank's own audit trail, REQ-BANK-012),
[`observability.md`](observability.md) (the log-stream PII rule), [ADR-0037](../adr/0037-shared-multi-domain-activity-audit-log.md).

## Context

The Kartell bank already keeps an immutable, admin-only audit trail (REQ-BANK-012). The same
guarantee is extended to nine more areas — **Lagerverwaltung** (`InventoryItem`),
**Auftragsverwaltung** (`JobOrder`), **Raffinerieverwaltung** (`RefineryOrder`), **Mein
Inventar** (`PersonalInventoryItem`), **Missionen** (`Mission`), **Operationen** (`Operation`),
**Rollen & Mitglieder** (`org_unit_membership`, epic #800), **Beförderung** (the promotion
catalogue + member gradings) and **Materialbörse** (`MaterialExchangeOffer` /
`MaterialExchangeInterest`). Every activity in each area is captured into a separate, admin-only
log; all ten logs (the nine here plus the bank's) are read on one page with a tab switcher, and
each can be exported as a PDF or JSON for a chosen period.

The eight new areas share **one** physical table (`audit_event`) with a `domain` discriminator; the
bank keeps its own `bank_audit_event` table (it has bank-specific reference columns and shipped
first). The storage choice and the unified-viewer architecture are recorded in
[ADR-0037](../adr/0037-shared-multi-domain-activity-audit-log.md).

---

### REQ-AUDIT-001 — Immutable, complete, admin-only activity audit log

Every state-mutating activity in the eight areas writes exactly **one** row to an **append-only**
audit table (`audit_event`, modeled after `bank_audit_event` — no `@Version`, never updated; the
sole deletion path is the explicit admin retention purge, REQ-AUDIT-004) **in the same transaction
as the business write**. An audit-insert failure rolls the mutation back, so the trail has **no
silent gaps**. Each event stores: timestamp (UTC), the acting user's id (FK `ON
DELETE SET NULL`) **plus** a denormalized actor-handle snapshot (the trail must survive user
deletion), the `domain`, the event type, the affected subject's id + a denormalized subject-label
snapshot, an optional target-user reference, a compact details payload, and the bounded
originating-client label of [REQ-AUDIT-005](#req-audit-005--the-trail-records-which-client-a-mutation-came-through).

Coverage is **complete**, including the cross-area writers and the system/automatic mutations:

- **Lager** — create / edit / note / book-out (consume, transfer, sell — a mission-earmarked `SELL`
  books the seller-chosen per-mission `INCOME` attributions, REQ-INV-027) / **quantity-split
  assignment** (add / change amount / remove a job-order or mission allocation —
  `INVENTORY_ALLOCATION_ADDED` / `INVENTORY_ALLOCATION_CHANGED` / `INVENTORY_ALLOCATION_REMOVED`,
  Variante C, REQ-INV-027) / personal-marker rebooking (Umbuchung — `INVENTORY_ITEM_DEPERSONALIZED`
  for personal→shared, `INVENTORY_ITEM_PERSONALIZED` for shared→personal, REQ-INV-007) / write-time
  stock merge (`INVENTORY_ITEM_MERGED` — `PIECE` automatically, `SCU` on the per-action opt-in,
  REQ-INV-026) / per-(entry, job-order) delivery-toggle (`INVENTORY_ITEM_DELIVERY_TOGGLED` —
  `delivered` lives on the job-order allocation since Variante C, REQ-INV-027) / bulk-checkout /
  **bulk rebooking** (Massen-Umbuchen — `INVENTORY_BULK_REBOOKED`, one summary event per action
  carrying the mode and the moved/skipped counts, REQ-INV-036; the individual moves are not audited
  separately, and a run that moved nothing records no event because it mutated no state) /
  global wipe; plus the cross-area writers (refinery store → `INVENTORY_RECEIVED_FROM_REFINERY`,
  job-order handover — material handover **and** item delivery consuming the order's earmarked item
  stock, REQ-ORDERS-030 → `INVENTORY_HANDED_OVER`, job-order item-production consumption →
  `INVENTORY_CONSUMED_BY_PRODUCTION`, job-order item-production book-in →
  `INVENTORY_RECEIVED_FROM_PRODUCTION`, REQ-INV-032), the org-unit re-stamp on membership change,
  and the purge of a deleted member's warehouse rows and hangar
  (`INVENTORY_PURGED_ON_USER_DELETION`, REQ-DATA-008 — a summary event carrying the affected-row
  counts, since the set-based DELETE exposes no per-row ids). The legacy
  `INVENTORY_OWNER_REASSIGNED` is retained for historical rows but no longer emitted.
- **Aufträge** — create (material/item) / edit / status / priority / blueprint-coverage variant-counting
  toggle / delete / completion (a single funnel — manual and auto-completion via handover both record
  exactly one `JOB_ORDER_COMPLETED`) / reassign / assignee add/remove/note / material+inventory unlink /
  material+item handover / item-production booking (`JOB_ORDER_PRODUCTION_BOOKED` — recording
  manufactured units and consuming the linked inventory, REQ-ORDERS-025) / claim upsert+withdraw. A requesting-owner edit (REQ-ORDERS-023) reuses the
  existing `JOB_ORDER_UPDATED` / `JOB_ORDER_ITEM_UPDATED` / `JOB_ORDER_MATERIAL_UNLINKED` events with a
  bounded `byRequester=true` details flag (no new event type; the actor already identifies who edited).
- **Raffinerie** — order create / update / cancel / store; refining-method reference CRUD; the
  scheduled UEX method+yield sync (one summary event per run, actor `system`); owner-reassignment on
  user deletion.
- **Mein Inventar** — create / update / delete (admin-on-behalf carries the target user); and the
  purge of a deleted member's personal stores — "Mein Inventar", personal blueprints,
  notifications, notification-rule selectors and promotion evaluations — as a single summary event
  (`PERSONAL_DATA_PURGED_ON_USER_DELETION`, REQ-DATA-008) carrying the five row counts.
- **Missionen** — mission create (incl. sub-mission; any goals/steps seeded on the create form each
  additionally record their own `MISSION_OBJECTIVE_ADDED` / `MISSION_STEP_ADDED`, REQ-MISSION-015) /
  edit (core, schedule, flags) / delete;
  participant add / remove / edit / check-in / check-out; unit add / edit / remove; crew add / edit /
  remove; frequency change / remove (typed and custom mission-specific channels alike,
  REQ-MISSION-014); owner change; owning-org-unit reassignment (REQ-ORG-018, from/to
  org-unit `kind:id` refs only); party-lead change; manager add / remove; finance
  entry create / edit / delete; Ablauf step add / edit / remove / reorder / done-toggle; goal (Ziel)
  add / edit / remove / reorder (the goal's non-personal `kind` enum may appear in the details). Free-text
  (mission/external participant names beyond the snapshot label, notes, **step titles and
  time/place hints, goal
  titles, and custom-frequency labels**) is never written to the details payload — only ids, counts,
  the goal kind enum and the non-personal mission name snapshot.
- **Operationen** — create / edit (incl. status change) / delete (missions are unlinked, not
  deleted); per-participant payout toggle.
- **Rollen & Mitglieder** (`org_unit_membership` + `kommando_group`, epic #800) — every org-unit
  membership / role mutation: membership grant + revoke (SK join/leave, Staffel assign/move/remove);
  leadership-rank grant / change / revoke (Bereichsleitung, OL, SK-Lead and the squadron ranks
  Staffelleiter / Kommandoleiter / stellv. / Ensign); Grand Admiral designation / vacation
  (`ROLE_CHANGED` carrying a `grandAdmiral` boolean, REQ-ORG-021); Logistician / Mission-Manager
  capability-flag changes; **role permission-set changes** (`ROLE_PERMISSIONS_CHANGED` — the admin
  role editor's permission grid, previously the one mutation in this area that left no trace); and
  Kommandogruppe create / rename+reorder / delete. For membership/rank events the
  subject is the org unit (its shorthand/name snapshot) and the affected user is the target
  reference; for Kommandogruppe events the subject is the group (its name snapshot). For a
  permission change the subject is the **role's stable `code`** carried as the subject *label* with a
  `null` `subjectId`: `Role.id` is a `Long` while `AuditEvent.subjectId` is a `UUID`, so the code is
  the only usable subject key. Its details payload renders
  `added=… removed=… unknownAdded=N unknownRemoved=N`: the two named sides are filtered through the
  closed `Permissions` vocabulary and rendered `-` when empty, and the two counts tally the changed
  members that vocabulary cannot name. The write endpoint accepts a raw string set, so an
  out-of-vocabulary value is still applied but **never named** — only counted — which keeps client
  free text out of both the trail and the log line while making the case observable at all (before
  2026-08 such a change produced an audit row reading `added=- removed=-`, indistinguishable from no
  change). The counts are always emitted so the shape is stable and `unknownAdded>0` is greppable; a
  value present on **both** sides of the edit is not counted, so leaving an out-of-vocabulary
  permission in place does not report a phantom change. The vocabulary itself is **derived
  reflectively** from the `Permissions` constants rather than hand-listed, so a new permission is
  audited by name the moment it is declared — pinned by the `RoleServiceTest` parity test, which also
  asserts every public constant in `Permissions` is a `String` whose value equals its field name.
  The details payload carries only the rank/kind enum names, the two flag booleans and the squadron
  label —
  never a user handle or free text (the group name is a non-personal structure label, like an order
  title). Account deletion by an admin records `USER_DELETED` here (REQ-DATA-008): the
  deletion mutates several audited areas at once, so this marker is written unconditionally and
  anchors the per-area purge events above; its payload holds row counts and ids only. The admin
  account merge records `USER_MERGED` here the same way (REQ-SEC-046) — one marker for an operation
  that moves rows across two dozen tables, naming **both** account ids and the per-table counts, and
  never the callsign: a shared username is what makes a merge necessary, so writing it into the
  payload would put a member's handle in the trail.
- **Beförderung** (`PromotionTopic` / `PromotionCategory` / `PromotionLevelContent` /
  `RankRequirement` / `MemberEvaluation`) — every promotion-catalogue and member-grading mutation:
  topic create / edit / delete; category create / edit / delete; level-content create / edit /
  delete; rank-requirement create / edit / delete; and member-evaluation create / change / delete.
  For catalogue events the subject is the mutated entity (its non-personal title / name / rank-step
  label, e.g. `20->19`); for an evaluation the subject is the **graded category** (its
  `topic / category` label) and the **evaluated member** is the target reference. The details
  payload carries only the assigned `PromotionLevel` enum (evaluations) or the minimum level + count
  (rank requirements) — never a user handle, a member name or the free-text rubric description.
- **Materialbörse** (`MaterialExchangeOffer` / `MaterialExchangeInterest`, `AuditDomain.MARKET`,
  REQ-MARKET-008) — every trade-board mutation: offer release (`MARKET_OFFER_RELEASED`), offer
  edit — offered amount + remark (`MARKET_REMARK_UPDATED`), offer deactivate
  (`MARKET_OFFER_DEACTIVATED`), interest register (`MARKET_INTEREST_REGISTERED`) and interest withdraw
  (`MARKET_INTEREST_WITHDRAWN`). Both offer **kinds** reuse these five events (REQ-MARKET-012). The
  subject is the offer, labelled by the **material name** for a material offer or the **item name** for
  an item offer (both non-personal game-asset values); the anbieter is the target reference. The
  details payload carries only bounded facts — the offer `kind`, plus for a material offer the item id
  / quality / **offered amount** (and the item's current stock on release) / re-release flag and for an
  item offer the blueprint `product` key / `qty`, and always the remark **length** — **never** the
  remark body, the anbieter/interessent handle, or the item's location. **Requests (Gesuche)** on the
  same board (REQ-MARKET-015…020) add five sibling event types under the same domain: request post
  (`MARKET_REQUEST_CREATED`), request edit (`MARKET_REQUEST_UPDATED`), request deactivate
  (`MARKET_REQUEST_DEACTIVATED`), fulfilment signal (`MARKET_REQUEST_INTEREST_SIGNALLED`) and its
  withdrawal (`MARKET_REQUEST_INTEREST_WITHDRAWN`), listed in the same Materialbörse viewer tab. Their
  details carry only bounded facts — the request `kind`, the material id or blueprint `product` key,
  the `minQuality`, the desired `amt` / `qty`, and the description **length** — never the description
  body, the requester/supplier handle, or any location.

The audit table is **business data, not logging** — the [`observability.md`](observability.md) rule
(never write names, emails or tokens to the **log stream**) is unaffected and still applies. User
**free text** (inventory/assignee notes, handover recipient handles) is **never** written into the
details payload — only ids, counts and lengths (the actor handle and non-personal subject labels
such as a material name or order title are snapshotted, exactly as the bank trail snapshots holder
handles).

**Details payload format & the `AuditDetails` builder (S8, #914).** The common `details` shape is a
space-separated list of `key=value` pairs (e.g. `section=full status=PLANNED`). Those payloads are
composed through the shared [`support.AuditDetails`](../../backend/src/main/java/de/greluc/krt/profit/basetool/backend/support/AuditDetails.java)
builder — `AuditDetails.of("section", "full").with("status", status)` — instead of being
hand-concatenated at each call site. The builder fixes the one-space separator and the `key=value`
grammar in one place and validates that keys carry no `=` or whitespace, so a copy-paste can no
longer silently fuse two pairs or drift the format. It stringifies every value through
`String.valueOf` — byte-identical to the Java `+` it replaced (an enum renders as its name, a
`UUID`/number/boolean/`null` as its `toString()`), so the emitted trail is unchanged. The builder is
the **type-level seam**: `AuditService.record(...)` / `BankAuditService.record(...)` take the
`details` argument as `CharSequence` (which `AuditDetails` implements), so a call site hands the
composed builder directly — no trailing `.toString()` — and `record` renders it before persistence.
It deliberately does **not** throw on value *content*: `record(...)` runs inside the business
transaction and must never roll it back (it truncates rather than throws), so the "no free text / no
PII in a value" rule above remains a review-time discipline — the uniform `key=value` structure
simply makes a stray free-text value obvious. A minority of details strings are not `key=value` at
all (a bare token such as an account number, or a free-form `'old' -> 'new'` label on the bank
trail); those keep their bespoke composition and, being plain strings (also a `CharSequence`), pass
through `record` unchanged without the builder.

The log is readable **only by admins**: the `/api/v1/audit/**` URL matcher requires
`hasRole('ADMIN')`, the controller carries a matching method-level `@PreAuthorize`, and the
`/admin/audit-log` page is admin-gated. Audit rows are never exposed through any non-admin endpoint.

Reference columns are plain UUIDs (no FKs) so audit rows **outlive** every referenced aggregate
(job orders are hard-deleted, inventory rows are depleted) without delete-ordering constraints.
`audit_event.target_user_id` and `bank_audit_event.target_user_id` are the two deliberate exemptions
from ADR-0142 point 3's "every user-id column carries a foreign key" rule, and V235 states that in a
`COMMENT ON COLUMN` rather than leaving it as an absence: a foreign key would either delete the
evidence with the member or block the deletion outright. Both tables snapshot a `NOT NULL` handle
beside the id, so a dangling target still renders. `actor_user_id` is *not* exempt — it carries a
foreign key on both tables.

**Acceptance**

- [ ] For a representative mutation in each area a test asserts exactly one matching audit event
  (domain, type, actor, subject).
- [ ] Audit write failures fail the business transaction (same TX — no silent gaps); the optimistic-
  locking landmine paths (book-out, handover, store, delete, completion, claim) record without a 409.
- [ ] Non-admin access to `/api/v1/audit/**` and `/admin/audit-log`: 403; the sidebar link is hidden.

A new event type is only half-wired until the viewer can filter for it: the per-area event-type list
in `AdminAuditLogPageController.EVENT_TYPES_BY_DOMAIN` and the `admin.audit.event.<TYPE>` label in
all three message bundles are the two mirror points, and `AdminAuditLogPageControllerTest` pins them
by reading the `AuditEventDto.eventType` enum out of the committed `openapi.json` and asserting that
**every** produced type is offered by one of the ten tabs *and* carries a label.

**Enforced by:** `AuditServiceTest`, `AuditQueryIntegrationTest`, `AuditAdminControllerSecurityTest`,
`RoleServiceTest`, `AdminAuditLogPageControllerTest`,
per-domain emission assertions in the service tests · **Code:** `service/AuditService`,
`model/AuditEvent`, `model/AuditDomain`, `model/AuditEventType`, `controller/AuditAdminController`,
`db/migration/V179` · **Decision:** [ADR-0037](../adr/0037-shared-multi-domain-activity-audit-log.md)

### REQ-AUDIT-002 — Unified admin audit viewer

All ten logs are read on **one** admin page (`/admin/audit-log`) with a **ten-way tab switcher**
(Bank · Lager · Aufträge · Raffinerie · Mein Inventar · Missionen · Operationen · Rollen ·
Beförderung · Materialbörse) built from the design-system `.tab-nav` component. The bank tab reads
the existing `/api/v1/bank/admin/audit` endpoint; the nine area tabs read `/api/v1/audit/{domain}`;
both DTO
shapes are adapted into one uniform row view so a single
template renders every tab. Each tab is paginated and filterable by **period** (the
`datetime-split-group` picker), **actor**, **event type** (the per-area type list) and
**originating client** (REQ-AUDIT-005, on every tab — both trails record it), which also renders as
its own column. Filtering and
paging swap **in place** (`krtFetch`, the epic #571 pattern); the legacy `/admin/bank-audit` URL
redirects here with the bank tab preselected.

**Acceptance**

- [ ] An admin sees ten tabs; switching a tab loads that area's log; filtering/paging stays in place.
- [ ] `/admin/bank-audit` redirects to `/admin/audit-log?domain=BANK`.
- [x] The client filter is offered on **every** tab, the bank included; selecting it reaches the
  backend as a query parameter and survives paging.

**Enforced by:** `AdminAuditLogPageControllerTest`, `AuditLogE2eTest` · **Code:**
`controller/AdminAuditLogPageController`, `templates/admin/audit-log.html`, `static/js/audit-log.js`

### REQ-AUDIT-003 — Per-area period export (PDF + JSON)

For each tab, an admin can export that log for a **user-selected period** (from/to) in two formats:

- **PDF** — generated backend-side with OpenPDF through the shared `KrtPdfSupport` /
  `AuditLogPdfFormat` layer, following the KRT design system (dark page background, KRT orange
  `#E77E23`, **embedded Lato**, A4, footer + logo) exactly like the bank statement/report PDFs. The
  document prints the **raw, language-neutral event code** (the on-screen viewer shows the localized
  label) so the trail stays unambiguous and the backend bundle is not duplicated. The
  `X-User-Time-Zone` header localizes the document timestamps.
- **JSON** — the period's events as the same DTOs the viewer consumes, delivered as a downloadable
  `*.json` attachment (UTC instants verbatim, no time-zone header).

Both are admin-gated and delivered via the established `ResponseEntity<…>` + frontend-proxy +
fetch/blob download pattern (no native dialogs; period validation + failures render inline). Each
export is itself audit-logged (`*_AUDIT_EXPORTED`, and the bank's `AUDIT_LOG_EXPORTED`), with the
chosen `format=pdf|json` in the details. The export queries are **unpaged** (one document per
period), so a period whose row count would exceed a generous cap (100 000) is **rejected with 400**
rather than risking an out-of-memory render — the admin narrows the period or purges older entries
(REQ-AUDIT-004). The proxy validates the `domain` path segment against the known tabs before
forwarding (defense-in-depth).

**Acceptance**

- [ ] Each area's PDF export renders the period's events, pinned by a `PdfTextExtractor` test, and
  the JSON export returns the period's DTOs; both write one matching `*_AUDIT_EXPORTED` audit event.
- [ ] An inverted period is rejected (400); both export endpoints are admin-gated.

**Enforced by:** `AuditReportServiceTest`, `BankAuditReportServiceTest`, `AuditAdminControllerSecurityTest`
· **Code:** `service/AuditReportService`, `service/BankAuditReportService`,
`service/pdf/AuditLogPdfFormat`, `controller/AuditReportProxyController`

### REQ-AUDIT-004 — Admin retention purge (delete entries older than a cutoff)

Each log can be **pruned** by an admin: a per-log action deletes that log's entries **older than an
admin-chosen cutoff** (`occurredAt < before`). It is available **separately for every log**,
including the bank — the four generic areas via `DELETE /api/v1/audit/{domain}`, the bank via
`DELETE /api/v1/bank/admin/audit`, both gated to `hasRole('ADMIN')` at the URL matcher (and a
method-level `@PreAuthorize` on the generic controller). The purge is scoped to the selected log
only — purging one area never touches another.

The deletion is **irreversible**, so the UI **warns the admin to export a PDF/JSON backup
(REQ-AUDIT-003) first** before confirming: a prominent warning in the design-system delete modal (no
native dialogs). The backend does **not** force a prior export — the warning is advisory.

The purge **is itself audit-logged**: it writes one `*_AUDIT_PURGED` event (the bank's
`AUDIT_LOG_PURGED`) carrying the deleted count and the cutoff in its details. That marker's timestamp
is newer than the cutoff, so it survives its own purge — a deletion always leaves a trace. The
endpoints return the deleted count, which the page reports back to the admin. There is **no automatic
retention sweep**; purging is always an explicit admin action.

**Acceptance**

- [ ] An admin purges one log older than a cutoff: older rows are gone, newer rows remain, the other
  logs are untouched, and exactly one `*_AUDIT_PURGED` marker (with count + cutoff) is written.
- [ ] The delete modal shows the backup-recommended warning; non-admins get 403 on every purge
  endpoint.

**Enforced by:** `AuditServiceTest`, `BankAuditServiceTest`, `AuditAdminControllerSecurityTest` ·
**Code:** `service/AuditService#purgeBefore`, `service/BankAuditService#purgeBefore`,
`controller/AuditAdminController`, `controller/BankAdminController`, `model/dto/AuditPurgeResultDto`,
`templates/admin/audit-log.html`, `static/js/audit-log.js` · **Decision:**
[ADR-0038](../adr/0038-admin-retention-purge-of-audit-logs.md)

### REQ-AUDIT-005 — The trail records which client a mutation came through

Every row **either** audit trail writes carries the **originating client**: which client software
the request that caused the mutation was made from, stored in `audit_event.client_id` and
`bank_audit_event.client_id` in the same transaction as the row itself.

**Why the actor is not enough.** "Who did this" and "from where" are two questions, and the second
one only became askable when a second first-party client appeared. While one client could reach a
given mutation, its answer was implied by the row's own domain. It is not implied any more: the
realm roles on a client's Keycloak scope decide which mutations it can reach, and wherever two
clients' scopes overlap the same mutation can arrive from either. The case this exists for is
narrower than "an app row looks like a browser row": an **access token replayed inside its
lifetime** acts with its member's full authority, so its rows would be indistinguishable from the
same person working in the browser — and "which client did this" is the first question of the review
that follows.

**The value is the token's `azp`, mapped through a bounded allowlist.** `azp` is a claim Keycloak
signs and a client cannot set — the same handle `IngestGatewayProperties` uses for the far more
dangerous on-behalf-of decision (ADR-0129) — so recording it introduces no new trust. It is
**never written verbatim**:

|                                        Recorded value                                         |                                                                                     Means                                                                                     |
|-----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| a configured client id (`basetool-frontend`, `basetool-android`, a configured ingest gateway) | that client                                                                                                                                                                   |
| `other`                                                                                       | an authenticated caller whose `azp` names no client this deployment knows                                                                                                     |
| `none`                                                                                        | a caller with **no token at all** (a scheduled job — the row's actor handle then reads `system`), or a token carrying no `azp` (a Keycloak mapper regression)                 |
| `NULL`                                                                                        | **only** a row written before the column existed (V237 for `audit_event`, V238 for `bank_audit_event`) — see below, and note the two tables' nulls do not mean the same thing |

The bound is not decoration. The claim's *content* is trustworthy; its *range* is not, because a
client id exists in the realm the moment somebody registers one. Writing it unbounded would put an
unreviewed, externally-chosen string into the one table whose value rests on carrying none — the
same instinct as the "no user free text, no PII in the details payload" rule above — and would grow
an unbounded metric label on the other consumer (REQ-OBS-006).

**One mapping, two consumers.** The bounded vocabulary is `support.ClientAttribution`, shared with
the `client_id` label of `basetool_api_client_requests_total` (REQ-OBS-018). They must agree: an
operator who sees a burst on that counter and then filters the audit log for the same client is
joining two answers that only mean the same thing if one rule produced both. Two copies of the
mapping is exactly how that stops being true. The two are complements, not duplicates — the counter
finds the window, the column attributes the act; a counter can never say that *this* role grant came
from the app.

**`NULL` means different things on the two tables, and the difference matters.** Neither is
backfilled — the claim was never stored, so there is nothing to backfill *from*, and an audit trail
that guesses is no longer evidence. But a reader must not treat the two alike:

- **`audit_event`** (pre-V237): the nulls are **unambiguous anyway**. While those rows were written,
  the authority they record could come from one client only, so the row's own domain implies the
  answer.
- **`bank_audit_event`** (pre-V238): the nulls mean **not recorded**, and nothing more. `Bank
  Employee` and `Bank Management` have been on the mobile client's Keycloak scope since it was
  provisioned — REQ-SEC-035's role list named them from its first revision — so a bank row has been
  reachable from two clients for as long as that client has existed, independent of whether a
  shipped app screen ever exercised it, because the authority rides on the token and a replayed
  token carries it. **A null client on a pre-V238 bank row must never be read as "the web
  frontend".**

Each migration states its own case in a `COMMENT ON COLUMN`, so the distinction survives without
this document. New rows on both tables always carry a value; `none` is an answer, not an absence.

**Both trails, one seam.** The bank keeps a physically separate table (ADR-0037), but not a separate
rule: `BankAuditService.record(...)` stamps the column through the same `ClientAttribution` and the
same bounded vocabulary. A filter that meant something different per tab would be a defect rather
than a feature, so the viewer offers the identical list everywhere.

**Acceptance**

- [x] On **both** trails: a mutation made with a known client's token records that client id
  verbatim; an unregistered client records `other`; a caller with no token, and a token-less
  authentication, record `none` — never `null`, and never by throwing (the write is inside the
  business transaction).
- [x] On **both** trails: two rows differing only in their client are separable by the viewer's
  filter, and a blank filter value means "no filter" rather than "matches nothing".
- [x] The bounded vocabulary is the same object the `client_id` metric label uses.
- [x] The filter is offered on all ten tabs and every offered value carries a DE/EN label.

**Enforced by:** `AuditServiceTest`, `BankAuditServiceTest`, `ClientAttributionTest`,
`AuditQueryIntegrationTest`, `BankAuditQueryIntegrationTest`, `AdminAuditLogPageControllerTest`,
`AdminAuditLogModalRenderMvcTest`, `ApiClientMetricsFilterTest`, `AuditLogE2eTest` · **Code:**
`support/ClientAttribution`, `service/AuditService#record`, `service/BankAuditService#record`,
`model/AuditEvent#clientId`, `model/BankAuditEvent#clientId`, `controller/AuditAdminController`,
`controller/BankAdminController`, `controller/AdminAuditLogPageController`,
`db/migration/V237`, `db/migration/V238` · **Decision:**
[ADR-0152](../adr/0152-the-audit-row-records-which-client-a-mutation-came-through.md),
[ADR-0153](../adr/0153-the-bank-trail-records-the-client-through-the-same-seam.md) ·
**Source:** private security advisory GHSA-2vq5-8p8w-5r64
