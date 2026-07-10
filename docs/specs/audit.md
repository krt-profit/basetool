# Activity audit logs — Lager, Aufträge, Raffinerie, Mein Inventar

> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-23.

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
snapshot, an optional target-user reference, and a compact details payload.

Coverage is **complete**, including the cross-area writers and the system/automatic mutations:

- **Lager** — create / edit / note / book-out (consume, transfer, sell) / personal-marker rebooking
  (Umbuchung — `INVENTORY_ITEM_DEPERSONALIZED` for personal→shared, `INVENTORY_ITEM_PERSONALIZED` for
  shared→personal, REQ-INV-007) / delivery-toggle /
  bulk-checkout / global wipe; plus the cross-area writers (refinery store → `INVENTORY_RECEIVED_FROM_REFINERY`,
  job-order handover → `INVENTORY_HANDED_OVER`), the org-unit re-stamp on membership change, and the
  owner-reassignment on user deletion.
- **Aufträge** — create (material/item) / edit / status / priority / blueprint-coverage variant-counting
  toggle / delete / completion (a single funnel — manual and auto-completion via handover both record
  exactly one `JOB_ORDER_COMPLETED`) / reassign / assignee add/remove/note / material+inventory unlink /
  material+item handover / claim upsert+withdraw.
- **Raffinerie** — order create / update / cancel / store; refining-method reference CRUD; the
  scheduled UEX method+yield sync (one summary event per run, actor `system`); owner-reassignment on
  user deletion.
- **Mein Inventar** — create / update / delete (admin-on-behalf carries the target user).
- **Missionen** — mission create (incl. sub-mission; any goals/steps seeded on the create form each
  additionally record their own `MISSION_OBJECTIVE_ADDED` / `MISSION_STEP_ADDED`, REQ-MISSION-015) /
  edit (core, schedule, flags) / delete;
  participant add / remove / edit / check-in / check-out; unit add / edit / remove; crew add / edit /
  remove; frequency change / remove (typed and custom mission-specific channels alike,
  REQ-MISSION-014); owner change; owning-org-unit reassignment (REQ-ORG-018, from/to
  org-unit `kind:id` refs only); party-lead change; manager add / remove; finance
  entry create / edit / delete; Ablauf step add / edit / remove / reorder / done-toggle; goal (Ziel)
  add / edit / remove / reorder (the goal's non-personal `kind` enum may appear in the details). Free-text
  (mission/guest names beyond the snapshot label, notes, **step titles and time/place hints, goal
  titles, and custom-frequency labels**) is never written to the details payload — only ids, counts,
  the goal kind enum and the non-personal mission name snapshot.
- **Operationen** — create / edit (incl. status change) / delete (missions are unlinked, not
  deleted); per-participant payout toggle.
- **Rollen & Mitglieder** (`org_unit_membership` + `kommando_group`, epic #800) — every org-unit
  membership / role mutation: membership grant + revoke (SK join/leave, Staffel assign/move/remove);
  leadership-rank grant / change / revoke (Bereichsleitung, OL, SK-Lead and the squadron ranks
  Staffelleiter / Kommandoleiter / stellv. / Ensign); Logistician / Mission-Manager capability-flag
  changes; and Kommandogruppe create / rename+reorder / delete. For membership/rank events the
  subject is the org unit (its shorthand/name snapshot) and the affected user is the target
  reference; for Kommandogruppe events the subject is the group (its name snapshot). The details
  payload carries only the rank/kind enum names, the two flag booleans and the squadron label —
  never a user handle or free text (the group name is a non-personal structure label, like an order
  title).
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
  (`MARKET_OFFER_DEACTIVATED`), interest register (`MARKET_INTEREST_REGISTERED`) and interest
  withdraw (`MARKET_INTEREST_WITHDRAWN`). The subject is the offer, labelled by the **material name**
  (a non-personal value); the anbieter is the target reference. The details payload carries only
  bounded facts — the item id, quality, the **offered amount** (plus the item's current stock on
  release), the remark **length**, and a re-release flag — **never** the remark body, the
  anbieter/interessent handle, or the item's location.

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

**Acceptance**

- [ ] For a representative mutation in each area a test asserts exactly one matching audit event
  (domain, type, actor, subject).
- [ ] Audit write failures fail the business transaction (same TX — no silent gaps); the optimistic-
  locking landmine paths (book-out, handover, store, delete, completion, claim) record without a 409.
- [ ] Non-admin access to `/api/v1/audit/**` and `/admin/audit-log`: 403; the sidebar link is hidden.

**Enforced by:** `AuditServiceTest`, `AuditQueryIntegrationTest`, `AuditAdminControllerSecurityTest`,
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
`datetime-split-group` picker), **actor** and **event type** (the per-area type list). Filtering and
paging swap **in place** (`krtFetch`, the epic #571 pattern); the legacy `/admin/bank-audit` URL
redirects here with the bank tab preselected.

**Acceptance**

- [ ] An admin sees ten tabs; switching a tab loads that area's log; filtering/paging stays in place.
- [ ] `/admin/bank-audit` redirects to `/admin/audit-log?domain=BANK`.

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
