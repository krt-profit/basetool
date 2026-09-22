> **Archived 2026-09-22.** Doc type: Historical plan — frozen, kept as a record and no longer updated. All five phases shipped with epic #556 (2026-06-13).
>
> **Current truth:** [`bank.md`](../specs/bank.md). Index of the archive: [`README.md`](README.md).

> **Doc type:** Historical plan — frozen. All five phases shipped with epic
> [#556](https://github.com/krt-profit/basetool/issues/556) (2026-06-13). The living truth is
> the spec [`docs/specs/bank.md`](../specs/bank.md) (REQ-BANK-001..020, status *Implemented*);
> this document is kept for the rationale and phase history only and is no longer updated.
> Last reviewed: 2026-06-13.
> **Epic:** [#556](https://github.com/krt-profit/basetool/issues/556) · **Spec:**
> [`docs/specs/bank.md`](../specs/bank.md) (REQ-BANK-001..020) · **ADRs:** 0009 / 0010 / 0011

# Kartell bank — implementation plan

A phase-by-phase, AI-executable plan for the bank feature: org/area/cartel/special
accounts with a per-player **holder distribution** (which player physically holds which
part of each balance), an append-only double-entry ledger, per-employee per-account
grants, an admin-only audit log, KRT-design PDF exports, dashboards, and an admin wipe
reset. Five
phases, each independently deployable, each with its own sub-issue carrying the phase
spec **and the deployment steps for that phase**.

Requirements language ("must hold") lives in the spec; this document says **how and in
what order**. Where the two disagree, the spec wins.

## 1. Verified repo facts (grounding — checked 2026-06-12)

These facts were verified against the codebase and pin the integration points. Re-verify
the volatile ones (migration tip, ADR/REQ numbering) against `origin/main` at phase
start.

- **Migrations:** tip is `V149__add_mission_unit_responsible_and_note.sql` → Phase 1
  starts at `V150` (*always* re-check `max(version)+1` after fetching `main`).
  Conventions: `backend/src/main/resources/db/migration/README.md` (PostgreSQL-only
  syntax, up-only, one logical change per file, mandatory why-comment header, new
  indexes registered in `DatabaseIndexMigrationTest`). The test profile runs against a
  Testcontainers PostgreSQL 18 container with Flyway enabled and `ddl-auto: validate` —
  `./gradlew :backend:test` therefore validates the new `V150+` migrations; booting the
  dev stack remains an optional extra check. (The migration README's formerly stale H2
  claims were fixed in #558.)
- **Money:** `MissionFinanceEntry.amount` is the precedent — `BigDecimal`,
  `NUMERIC(19,4)`, `CHECK (amount >= 0)`; whole-aUEC via value-based `@WholeNumber`
  (ADR-0002, REQ-MISSION-001); display HALF_UP through the frontend `MoneyFormat` bean
  (never inside `th:data-*`).
- **Append-only precedent:** inventory (ADR-0003) — append-only enforced in service
  code + pinned by tests; aggregates computed with JPQL constructor projections;
  nullable to-one dims need LEFT JOIN on aliases (silent-inner-join trap).
- **Event-log precedent:** `ExternalSyncReport` — insert-only entity that deliberately
  does **not** extend `AbstractEntity` (no `@Version`); indexes on the query paths;
  retention in the application layer.
- **Grant-table precedent:** `org_unit_membership` — composite-PK flag rows
  (`is_logistician`/`is_mission_manager`/`is_lead`), security-service bean
  (`SpecialCommandSecurityService.canManageMembers`) referenced from `@PreAuthorize`.
- **Roles:** Keycloak realm role name → `ROLE_<UPPER_SNAKE>` in both modules
  (`CustomJwtGrantedAuthoritiesConverter`; frontend `userAuthoritiesMapper` +
  `BackendRoleSyncFilter`). Seeding by **code** in `DataInitializer.createRoleIfNotFound`.
  Role hierarchy = static beans in **both** `SecurityConfig`s (keep in sync).
  E2E realm: `frontend/src/e2e/resources/realm-export.e2e.json`. Production
  `realm-export.json` is gitignored, host-installed, and **not** auto-imported in prod
  (`start` without `--import-realm`) → prod realm changes are **manual admin-console
  steps + updating the host export file** (see `docs/deployment.md` §5.3).
- **Org-unit independence (REQ-BANK-008):** bank gates consult only the two bank roles
  and `bank_account_grant` rows. `OwnerScopeService` scoping, contextual
  `ROLE_X@orgUnitId` authorities and the `X-Active-Org-Unit-Id` admin pin must have
  **zero** influence on bank decisions — bank staff may or may not be org-unit members.
- **PDF:** OpenPDF 3.0.5 (`org.openpdf.*`), used by `JobOrderHandoverReportService` +
  `JobOrderItemHandoverReportService` — KRT page background (`KrtPageBackground`),
  orange `#E77E23`, logo `backend/src/main/resources/META-INF/resources/logos/krt.png`
  (PNG — OpenPDF cannot decode WebP), `setCompressionLevel(0)` enables raw-byte test
  assertions, `PdfReader`/`PdfTextExtractor` for content tests. Styling helpers are
  **duplicated** across both services (no shared base yet). Fonts: built-in Helvetica —
  **no Lato anywhere on the backend classpath**; Lato TTFs exist at
  `design/fonts/Lato-*.ttf` and in the Keycloak theme. Delivery:
  `ResponseEntity<byte[]>` + `X-User-Time-Zone` header + frontend proxy
  (`JobOrderHandoverReportProxyController`) + fetch/blob download with client-side
  filename.
- **Admin UI:** sidebar group `data-group-key="admin"` gated
  `sec:authorize="hasRole('ADMIN')"`; destructive-action pattern =
  `data-trigger="open-modal-display"` + danger confirm modal + `submit-form-by-id`
  (canonical: `ship-data.html` reset-fitted); PRG with `successToast`/`errorToast`
  flash keys; AJAX via `/api/proxy/**` frontend controllers with CSRF meta headers.
- **Dashboards/charts:** no charting library exists; 30-day trend = server-computed
  inline SVG sparkline (only `--color-*` tokens). The design system (submodule pin
  ≥ `2ba5678`) now **ships the bank component layer**: `.kpi-total`/`.kpi-card`
  (+ `--closed`, `.kpi-value`, `.kpi-delta--pos/--neg`), `.holder-row`/`.holder-bar`/
  `.holder-sum` + `.stack-bar`/`.stack-legend`, `.matrix-flag(.on)` + `tr.is-inert`,
  the formalized `.krt-modal` head/body/foot frame and `.confirm-input`
  (type-to-confirm). Specimens: `preview/components-kpi-sparkline.html`,
  `preview/components-bank-patterns.html`; documented in the submodule `README.md`
  ("Bank patterns" block). Do NOT hand-roll these — reuse the shipped classes.
- **Pagination:** `PageResponse<T>` + `PaginationUtil.createPageRequest` with
  whitelisted sort fields (PII-path defense). Fragments
  `pagination`/`pageSizePicker` (freshest example: hangar squadron overview, #553).
- **Date-range picker:** `datetime-split-group` from/to pattern in `missions.html` /
  `operations-index.html` + `static/js/datetime-splitter.js`.
- **User selection:** `GET /api/v1/users/lookup` → `UserReferenceDto`, rendered into a
  `<select>` enhanced by `krt-searchable-select.js`. **Gate caveat:** the endpoint is
  `hasAnyRole('ADMIN','OFFICER','KRT_MEMBER')` — bank management is none
  of these, so Phase 1 must widen the gate by `'BANK_MANAGEMENT'` (URL matrix +
  `@PreAuthorize`) or the grants UI gets 403 on user lookup.
- **E2E:** Java Playwright under `frontend/src/e2e/java/...e2e/`, `@Tag("e2e")`,
  `E2eStackExtension` + `BackendSeeder`; CI runs the suite only when the PR carries the
  `e2e` label; `:frontend:e2eTest` is input-cached on the e2e source set — use
  `--rerun-tasks` after main-code changes; never add `org.gradle.test-retry` (Java 25
  incompatible).
- **ArchUnit gates to extend:** register bank services in
  `staffelScopedServicesMustWireOwnerScopeOrAuthHelper` where applicable, add
  `bankSecurityService` to the accepted gates of
  `staffelScopedWriteEndpointsMustGateOnOwnerScopeService`, respect
  `noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities` (use
  `org_unit_id`, never `squadron_id`), and note that `deposit`/`withdraw` are **not**
  in `MUTATING_METHOD_PREFIXES` — name service mutators with covered prefixes
  (`book…`, `create…`, `transfer…` is also uncovered → prefer `book…`/`create…`) or
  annotate `@Transactional` explicitly.

## 2. Domain model (tables land in Phase 1)

```text
bank_account            id UUID PK · account_no TEXT UNIQUE (KB-0001…) · name · type
                        (ORG_UNIT|AREA|CARTEL|CARTEL_BANK|SPECIAL) · status
                        (ACTIVE|CLOSED) · org_unit_id UUID NULL FK org_unit ·
                        area_name TEXT NULL · version/created_at/updated_at
                        CHECKs: owner-ref matches type; partial UNIQUEs: one CARTEL,
                        one CARTEL_BANK, one per org_unit
bank_holder             id UUID PK · user_id UUID NULL UNIQUE FK app_user
                        ON DELETE SET NULL · handle TEXT NOT NULL (snapshot, survives
                        user deletion) · active BOOL · version/timestamps
                        (bank-local registry of players physically holding org money,
                        REQ-BANK-003; created via the user lookup)
bank_account_grant      PK (user_id, account_id) · can_deposit · can_withdraw ·
                        can_transfer · granted_by UUID FK SET NULL ·
                        version/timestamps
bank_transaction        id UUID PK · type (DEPOSIT|WITHDRAWAL|TRANSFER|WIPE_RESET|
                        REVERSAL) · initiated_by UUID FK SET NULL · note TEXT ·
                        reversed_transaction_id UUID NULL FK UNIQUE · created_at
                        (insert-only; no version)
bank_posting            id UUID PK · transaction_id FK · account_id FK ·
                        holder_id UUID NOT NULL FK bank_holder (ON DELETE RESTRICT) ·
                        amount NUMERIC(19,4) NOT NULL (signed, != 0) · created_at
                        (insert-only; no version)
                        INDEX (account_id, created_at) · INDEX (transaction_id) ·
                        INDEX (account_id, holder_id)
bank_audit_event        id UUID PK · occurred_at · actor_user_id UUID FK SET NULL ·
                        actor_handle TEXT (snapshot) · event_type TEXT ·
                        account_id UUID NULL · transaction_id UUID NULL ·
                        target_user_id UUID NULL · details TEXT
                        (insert-only; no version) · INDEX (occurred_at DESC) ·
                        INDEX (account_id, occurred_at DESC)
```

Entities: `BankAccount`, `BankHolder`, `BankAccountGrant` extend
`AbstractEntity` (mutable, versioned); `BankTransaction`, `BankPosting`,
`BankAuditEvent` are insert-only (no `AbstractEntity`, `ExternalSyncReport` style).
Holder sub-balances are never stored — `SUM(amount) GROUP BY account_id, holder_id`
(same compute-on-read contract as the account balance, ADR-0010).

## 3. API surface (backend, `/api/v1/bank/**`)

|                      Endpoint                      |         Method          |                    Gate (inner, `@PreAuthorize`)                    |
|----------------------------------------------------|-------------------------|---------------------------------------------------------------------|
| `/bank/accounts`                                   | GET (paged)             | `hasRole('BANK_EMPLOYEE')` + service filters to visible accounts    |
| `/bank/accounts`                                   | POST                    | `hasRole('BANK_MANAGEMENT')`                                        |
| `/bank/accounts/{id}`                              | GET                     | `@bankSecurityService.canSee(#id, authentication)`                  |
| `/bank/accounts/{id}/close` · `/reopen`            | POST                    | `hasRole('BANK_MANAGEMENT')`                                        |
| `/bank/holders` (+ item ops)                       | GET/POST/PATCH          | GET `hasRole('BANK_EMPLOYEE')`, writes `hasRole('BANK_MANAGEMENT')` |
| `/bank/accounts/{id}/holders`                      | GET (distribution)      | `canSee` (derived per-holder sub-balances)                          |
| `/bank/accounts/{id}/transactions`                 | GET (paged)             | `canSee`                                                            |
| `/bank/deposits` · `/bank/withdrawals`             | POST                    | `canDeposit(#req.accountId)` / `canWithdraw(...)`                   |
| `/bank/transfers`                                  | POST                    | `canTransfer(#req.sourceAccountId)` + destination-visibility rule   |
| `/bank/transactions/{id}/reversal`                 | POST                    | `hasRole('BANK_MANAGEMENT')` (open question 3)                      |
| `/bank/grants` (+ per-account list, delete, patch) | GET/POST/PATCH/DELETE   | `hasRole('BANK_MANAGEMENT')`                                        |
| `/bank/dashboard`                                  | GET                     | `hasRole('BANK_EMPLOYEE')` (content scoped by visibility)           |
| `/bank/accounts/{id}/statement`                    | GET → PDF               | `canSee` (Phase 3)                                                  |
| `/bank/export/three-month-report`                  | GET → PDF               | `hasRole('BANK_MANAGEMENT')` (Phase 3)                              |
| `/bank/admin/wipe-reset`                           | POST                    | `hasRole('ADMIN')` (+ URL gate) (Phase 4)                           |
| `/bank/admin/audit`                                | GET (paged, filterable) | `hasRole('ADMIN')` (+ URL gate) (Phase 4)                           |

URL matrix addition (backend `SecurityConfig`):
`.requestMatchers("/api/v1/bank/admin/**").hasRole("ADMIN")` before the catch-all; the
rest rides `anyRequest().authenticated()` + method gates. `BankSecurityService`
evaluates only bank roles + grants — org-unit memberships, contextual authorities and
the admin pin have no effect on bank gates (REQ-BANK-008).

## 4. Frontend surface

|                                                                          Page                                                                          |         Route         |  Audience  |
|--------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------|------------|
| Bank dashboard (cards: balance + 30-day delta + SVG sparkline; totals row for management)                                                              | `/bank`               | employee+  |
| Account detail (booking history paged, deposit/withdraw/transfer + holder-rebooking modals, holder distribution, statement export with from/to picker) | `/bank/accounts/{id}` | per grant  |
| Account administration (create/close/reopen) + holder registry                                                                                         | `/bank/manage`        | management |
| Grants administration (user lookup via `krt-searchable-select`, flag matrix)                                                                           | `/bank/grants`        | management |
| Admin: wipe reset (danger section + confirm modal)                                                                                                     | `/admin/bank`         | admin      |
| Admin: audit log (paged, filter: period/actor/account/type)                                                                                            | `/admin/bank-audit`   | admin      |

Sidebar: new group `data-group-key="bank"` gated
`sec:authorize="hasRole('BANK_EMPLOYEE')"` (hierarchy lets management/admin see it);
admin pages join the existing admin group. New i18n key families: `nav.bank.*`,
`bank.*`, `admin.bank.*` in all three `messages*.properties` (umlauts as `\uXXXX`).
Page CSS: `static/css/bank.css` via the `extraLinks` head slot.

### 4.1 Binding design mockups (design-system submodule, pin ≥ `2ba5678`)

The design system contains **final-draft mockups for all four bank pages** under
`.claude/skills/das-kartell-design/proposals/`. They are the **visual source of truth**
for Phases 2 and 4 (REQ-BANK-017) — build the pages to match them, using the shipped
component classes instead of bespoke CSS:

|                Page                 |                          Mockup (final draft)                           |                                                                                                                                                                                               Key components / decisions                                                                                                                                                                                                |
|-------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/bank` dashboard                   | `proposals/bank-dashboard-varianten.html` — **D1 card grid**            | one `.kpi-card` per visible account (balance · sign-colored ±30-day `.kpi-delta` · server-SVG sparkline · type chip; click opens detail; closed = `.kpi-card--closed`); management-only `.kpi-total` aggregate strip (sum, 30-day in/out); employees: granted accounts only, no totals strip                                                                                                                            |
| `/bank/accounts/{id}` detail        | `proposals/bank-konto-detail-varianten.html` — **K1 two-column**        | actions rendered per capability flags; holder distribution via `.holder-row`/`.holder-bar` + `.stack-bar`/`.stack-legend` + `.holder-sum`; booking modals = `.krt-modal` (head/body/foot, one filled CTA each, 409 surfaced as inline field error)                                                                                                                                                                      |
| `/bank/manage` + `/bank/grants`     | `proposals/bank-verwaltung-varianten.html` — **W1 + G1 with G2 toggle** | management page with clickable tabs *Konten \| Halter* (lifecycle + holder registry); grants flag matrix (`.matrix-flag(.on)`) grouped **per account** by default, toggleable to per-employee; `tr.is-inert` marks grants whose grantee currently lacks the `Bank Employee` role (the only inert case — the mockup's "ausgesetzte Grants" tweak predates the org-unit-independence correction and maps to exactly this) |
| `/admin/bank` + `/admin/bank-audit` | `proposals/bank-admin-varianten.html` — **A1 + A2**                     | danger section (danger-card) with **type-to-confirm** wipe-reset modal (`.confirm-input`, danger-styled `.krt-modal`); audit log as paged, filterable table (period/actor/account/type); admin chrome (accent-dark header); bank management sees neither page                                                                                                                                                           |

Component specimens: `preview/components-bank-patterns.html`,
`preview/components-kpi-sparkline.html`; class documentation: submodule `README.md`
("Bank patterns (KPI, custody, grants)" block).

## 5. Phases

Every phase = one sub-issue (created on the epic) = one or more PRs. **Every phase PR
updates `docs/specs/bank.md` in the same PR** (tick acceptance boxes, fill
`Enforced by`), updates `CHANGELOG.md` (implementation phases only), regenerates
`openapi.json` when the API changes, and passes
`./gradlew check` + `spotlessApply`. PR labels: map type + area (`enhancement`,
`backend`/`frontend`/`database`/`security`/`design/ui`/`i18n`) and add **`e2e`**
whenever frontend flows, auth or migrations are touched.

### Phase 1 — Backend: roles, domain, ledger, grants, audit, API

**Objective.** Everything server-side: the two Keycloak roles wired through both
modules, all six tables + entities + repositories, the booking service with double-entry
invariants and no-overdraft guard (account **and** holder level), the holder registry,
grant management, the audit core, and the REST API — everything in §3 **except** the two
PDF endpoints
(Phase 3) and the two `/bank/admin/**` endpoints (Phase 4). The `WIPE_RESET` and
`REVERSAL` transaction types and their ledger invariants are implemented here; the
reversal endpoint ships in this phase, the wipe-reset endpoint and UI in Phase 4. No UI
beyond what exists.

Deliverables:

- Migrations `V150+` (re-check tip): tables per §2, partial unique indexes, seed
  nothing (accounts are runtime data) — plus `DataInitializer` role seeding
  (`BANK_EMPLOYEE`, `BANK_MANAGEMENT`) and `RoleHierarchy` updates in **both**
  `SecurityConfig`s.
- `model/` entities + enums (`BankAccountType`, `BankAccountStatus`,
  `BankTransactionType`, `BankAuditEventType`), `repository/`, `service/BankAccountService`,
  `service/BankLedgerService` (booking + invariants incl. per-holder sub-balance guard;
  mutators named with covered prefixes or explicit `@Transactional`),
  `service/BankHolderService` (registry, handle snapshot), `service/BankGrantService`,
  `service/BankAuditService` (same-TX append), `service/BankSecurityService`
  (role + capability checks only — deliberately blind to org-unit memberships, the
  admin pin and contextual authorities, REQ-BANK-008), `controller/Bank*Controller`, `dto/` +
  `dto/request/` records (server-managed fields excluded from request DTOs), MapStruct
  mappers, Javadoc everywhere.
- Backend `SecurityConfig`: `/api/v1/bank/admin/**` URL gate.
- ArchUnit updates (gate whitelist + package-dependency rule for REQ-BANK-019).
- `ROLES_AND_PERMISSIONS.md`: bank section; `realm-export.e2e.json`: roles + synthetic
  `test-bank-employee` / `test-bank-management` users.
- OpenAPI regenerated; backend `messages*.properties` problem keys for the new 409
  codes (`BANK_OVERDRAFT`, `BANK_ACCOUNT_NOT_EMPTY`, `BANK_GRANTEE_MISSING_ROLE`, …).
- Widen the `GET /api/v1/users/lookup` gate (URL matrix + `@PreAuthorize`) by
  `'BANK_MANAGEMENT'` so the Phase 2 grants UI can resolve users (see §1 gate caveat).

Tests (Gradle only): `BankLedgerServiceTest` (double-entry invariants, no-overdraft
concurrency at account and holder level, append-only pin, holder-on-every-posting rule,
holder-distribution sums), `BankSecurityServiceTest` (capability matrix; org-unit
membership, admin pin and contextual authorities have no effect — both directions),
`BankHolderServiceTest` (registry, handle snapshot on user deletion),
`BankGrantServiceTest`, `BankAccountServiceTest` (lifecycle, uniqueness 409s),
`BankAuditServiceTest` (one event per mutation, same-TX), controller tests per
controller, `DatabaseIndexMigrationTest` registration, `ArchitectureTest` green.

**Deployment (Phase 1).**

1. Merge → release PR (two-phase release flow, `docs/deployment.md`) → promote.
2. Flyway migrations apply automatically on backend container start — verify via
   startup log (`Successfully applied … migrations`) and `/api/v2/system/ping`.
3. **Manual Keycloak step (prod is not auto-imported):** in the admin console create
   realm roles `Bank Employee` and `Bank Management` (final names per owner decision),
   assign them to the initial bank staff, and update the host-side
   `/var/iri/code/realm-export.json` so a future bootstrap re-creates them.
4. Smoke: log in as a bank-role user → `GET /api/v1/bank/accounts` returns 200 with an
   empty page; a member-role user receives 403.
5. Rollback: standard image rollback (`deploy.sh --tag <prev>`); migrations are
   additive-only in this phase, so no schema rollback is needed.

### Phase 2 — Frontend: bank area, dashboards, booking flows, grants UI

**Objective.** The complete bank UI per §4 except admin pages and PDFs: sidebar group,
employee/management dashboard (cards + totals + SVG sparkline), account detail with
deposit/withdraw/transfer/holder-rebooking modals (KRT modals, `data-version` sync or
reload-on-success) and the holder-distribution section, account administration + holder
registry, grants administration with the user lookup, full i18n de+en, `bank.css`.

Deliverables: `BankPageController`, `BankManagePageController`, `BankGrantsPageController`
(+ `/api/proxy/bank/**` AJAX proxy controllers where needed), templates `bank/*.html`,
`static/js/bank.js`, sparkline rendered server-side into the template (no vendor lib),
i18n keys, `data-testid` hooks for e2e (`nav-bank`, `bank-account-row`,
`bank-transfer-submit`, …).

Design / UI acceptance (binding, REQ-BANK-017): pages match the §4.1 mockups —
dashboard = D1 card grid, detail = K1 two-column, management = W1 tabs, grants = G1
matrix with G2 toggle — using the design-system component classes (`.kpi-*`,
`.holder-*`, `.stack-*`, `.matrix-flag`, `.krt-modal`) from `krt-components.css`
instead of bespoke CSS; the bank-pattern classes are consumed via `bank.css` in sync
with the pinned submodule. Deviations from a mockup need an owner decision before
implementation.

Tests: frontend controller unit tests (MockWebServer for error paths), Playwright e2e:
`BankDashboardE2eTest`, `BankBookingE2eTest` (deposit/withdraw/transfer incl. 409 paths),
`BankPermissionsE2eTest` (visibility matrix incl. the member-without-bank-role-sees-nothing
row and a bank employee who is also an org-unit member working normally), seeded via
`BackendSeeder`.

**Deployment (Phase 2).**

1. Merge (PR labeled `e2e`) → release → promote; no migrations, no Keycloak changes.
2. Smoke: bank user sees the Bank sidebar group and dashboard; member/guest/anonymous
   see neither nav entry nor pages (direct URL → 403/redirect).
3. Rollback: image rollback only.

### Phase 3 — PDF exports: shared KRT PDF layer, statement, quarter report

**Objective.** Extract the duplicated PDF styling into a shared `KrtPdfSupport`
(page background, fonts, table helpers) used by both existing handover reports **and**
the two new documents; bundle and embed Lato (`Lato-Regular`/`Lato-Bold` TTFs into
backend resources, loaded via `BaseFont`); implement the account statement
(REQ-BANK-014: period picker → opening balance, postings with running balance and
holder, closing balance, closing holder distribution) and the management three-month
report (REQ-BANK-015: all accounts, rolling 3-month window, per-account summary +
closing holder distribution **plus** itemized bookings). PDF labels from backend message bundles (German). Frontend: export
modal with the `datetime-split-group` from/to picker + fetch/blob download; audit
events for both exports.

Deliverables: `service/pdf/KrtPdfSupport`, `BankStatementReportService`,
`BankManagementReportService`, the two GET endpoints (§3), frontend proxy +
download JS, font resources + license note (Lato is OFL — include `OFL.txt`).

Tests: `PdfTextExtractor` content tests (balance math, holder-distribution sections,
period filter, label keys), regression tests proving the two existing handover reports
render unchanged on the shared layer, proxy controller tests, access-matrix tests
(employee/management/admin × statement/three-month-report).

**Deployment (Phase 3).**

1. Merge → release → promote; no migrations, no Keycloak changes.
2. Smoke: download a statement for a granted account (verify Lato + KRT background +
   correct balances) and the three-month report as management; employee → 403 on the
   three-month report; both exports appear in the audit log.
3. Rollback: image rollback only.

### Phase 4 — Admin area: wipe-reset button & audit-log viewer

**Objective.** The admin carve-out surfaces: `/admin/bank` (danger section with the
wipe-reset button + **type-to-confirm** danger modal + idempotent no-op notice) and
`/admin/bank-audit` (paged, filterable audit viewer: period, actor, account, event
type). Admin sidebar entries in the existing admin group. Finalize
`ROLES_AND_PERMISSIONS.md` matrix rows.

Design / UI acceptance (binding, REQ-BANK-017): both pages match the §4.1 A1 + A2
mockup (`proposals/bank-admin-varianten.html`) — danger-card section, danger-styled
`.krt-modal` with `.confirm-input` type-to-confirm hurdle (reserved for
wipe-reset-grade actions), audit log as filterable table.

Deliverables: `AdminBankPageController`, `AdminBankAuditPageController`, templates,
backend wipe-reset service path (`WIPE_RESET` transactions per REQ-BANK-013) + audit
summary event, audit query endpoint with whitelisted sort/filter fields.

Tests: wipe-reset service test (all account balances and holder sub-balances zero,
history intact, idempotency),
audit filter/pagination tests, admin-only access tests (management → 403 on both
pages/endpoints), e2e `BankAdminResetE2eTest` + `BankAuditLogE2eTest` (PR labeled
`e2e`).

**Deployment (Phase 4).**

1. Merge → release → promote; no migrations, no Keycloak changes.
2. Smoke: admin sees both pages; bank management does **not** see the audit page;
   wipe reset on a seeded staging stack zeroes balances and writes the audit summary.
3. Operational note: agree with the owner that wipe reset is only pressed after a
   confirmed SC wipe; the audit event records the actor either way.
4. Rollback: image rollback only.

### Phase 5 — Hardening & acceptance

**Objective.** Close the loop on the non-functional requirements: the scheduled
integrity job (REQ-BANK-020; `task/` pattern, property-configurable interval), the
seeded volume test (≥ 100 accounts / ≥ 100k postings — dashboard & statement stay
single-statement), an accessibility + device-class pass over all bank pages
(REQ-BANK-017), documentation finalization (spec boxes all ticked, `ROLES_AND_PERMISSIONS.md`
verified against the real `@PreAuthorize` set), and the **plan freeze**: flip this
document's header to `Historical plan — frozen`, pointing at the spec; verify the three
ADRs were flipped to `Accepted` at the Phase 1 sign-off (their stated trigger).

**Deployment (Phase 5).**

1. Merge → release → promote; integrity job starts with the backend (verify the first
   run line in the logs, `orgUnitId`-free, correlation-id present).
2. Post-deploy acceptance with the owner: walk the REQ-BANK acceptance checklists
   against production; sign off the epic; close #556.

## 6. Risks & countermeasures

|                                Risk                                |                                                                                Countermeasure                                                                                |
|--------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Keycloak prod realm drift (roles exist in dev export but not prod) | explicit manual step in Phase 1 deployment + smoke test that a bank-role login passes a bank gate                                                                            |
| Concurrency bugs in booking (the project's known trap class)       | insert-only ledger avoids `@Version` churn; no-overdraft via atomic guard + dedicated concurrency test; CLAUDE.md `…WithinTransaction` rules honored in handover-style flows |
| Org-unit scoping leaking into bank gates (pin / contextual roles)  | `BankSecurityService` evaluates only bank roles + grants; independence is test-pinned in both directions (matrix e2e)                                                        |
| PDF refactor regresses the two shipped handover reports            | golden-text regression tests before switching them to `KrtPdfSupport`                                                                                                        |
| Audit gaps (mutation without event)                                | audit append in the same transaction + per-endpoint "exactly one event" tests                                                                                                |
| Sparkline scope creep toward a chart library                       | spec fixes server-rendered inline SVG; anything more is a new requirement                                                                                                    |

## 7. Open questions

Mirrored from the spec (decide at latest at each phase's review): final role names
(before Phase 1), transfer destination visibility rule (Phase 1), reversal permission
level (Phase 1), persisted/numbered statements (post-v1), holders without a basetool
account (Phase 1).
