# Shared briefing for every audit agent (read this first, completely)

## The task this audit serves

The owner (@greluc) wants a report on the **Profit Basetool main repository** (all modules) that:

1. identifies improvement and optimisation potential, with the explicit goal of a **refactor that
   cleanly separates the business domains (Fachbereiche) from each other**, so that they interact only
   through **well-defined, complete and stable APIs** — for maintainability and modularisation. The
   clean separation of the domains is the most important part.
2. checks where **modern language features** could be used as fully as possible (e.g. current *final*
   Java JEPs; also modern JavaScript, CSS, SQL, Gradle), within the project's rules.
3. presents every improvement with its implementation, **pros and cons**, and examines **risks and
   regressions**.
4. guarantees that **no security weakness of any kind** is introduced by any proposed work. Every
   proposal must state its security impact explicitly.
5. may propose **new or different frameworks or dependencies** if they add real value (e.g. Spring
   Modulith, jMolecules, OpenRewrite, Error Prone/NullAway) — but only with evidence.
6. re-evaluates the findings of the **previous audits** (2026-07-11 modularity audit, PR #1256; the
   2026-09-22 Improvement Audit, 176 findings) under the current conditions and rules.

**Nothing is implemented by this audit.** It is read-only analysis that feeds one report.

## Hard rules for you (agents)

- **Paths.** The repository is the git worktree
  `$REPO` (branch
  `claude/basetool-refactor-modularization-68184f`, identical to `origin/main` at `95e945326`).
  Always read files **under this path**, never under `$MAIN_CHECKOUT\` directly (that
  checkout may be on another branch). The knowledge vault is
  `$VAULT` (read-only for you; start at
  `00 Maps/Basetool.md`).
- **Read-only.** Do not edit, create or delete any file in the repository or the vault. Do not run
  `git` commands that change anything (no commit, checkout, stash, reset, branch). Do not run
  Gradle (`./gradlew`) — the modules are already compiled under `*/build/classes/java/main`. Do not
  start containers or servers.
- **Write exactly one output file** (plus optional data files with the same prefix) into the
  scratchpad directory
  `$SCRATCHPAD`
  under the file name your prompt gives you. Never write to another agent's file. Helper scripts
  you write go to the same directory, prefixed with your file prefix.
- **Shell.** The Bash tool is Git Bash on Windows. Do **not** use heredocs for scripts or content —
  write scripts with the Write tool and run them by path (`python <file>`). Backslashes in heredocs
  get mangled.
- **Evidence or nothing.** Every claim cites `path:line`, a count together with the exact command
  or script that produced it, a git commit, a PR number, or a URL with the date you read it. If you
  cannot verify something, write **UNKNOWN** and say what would settle it. Do not rely on memory
  for facts about libraries, JEPs or versions — read the code, the docs in the repo, or (only if
  your prompt allows web access) the current official documentation.
- **Security first.** For every proposal, name what could weaken authorization
  (`@PreAuthorize`, SpEL bean references, ArchUnit gates), tenancy scoping (`OwnerScopeService`,
  org-unit stamping), redaction (`MissionPeerRedactor`, owner redaction), audit completeness
  (REQ-AUDIT-001), CSRF/CSP, session deserialisation allow-lists (ADR-0206), secrets handling,
  input validation, rate limits — and how the proposal keeps each intact.
- **Output language: English.** Be precise and specific; no filler. Prefer tables for inventories.

## Project facts you can rely on (verified by the coordinator today)

- Modules: `backend` (1389 main classes, ~175k LOC, **package-by-layer**: `controller`, `service`,
  `repository`, `model`, `model.dto`, `mapper`, `support`, `config`, `event`, `task`, …),
  `frontend` (554 main classes, ~66k LOC; Thymeleaf; 120 templates ~24k lines; 100 JS files ~40k
  lines; 64 CSS files ~16k lines), `ingest` (75 classes), `keycloak-spi` (16 classes, Java-21
  bytecode), `logging-support` (4), `test-support` (6). `keycloak-theme` is FreeMarker, not a
  Gradle module. Spring Boot 4.1.1, Java 25 toolchain, Gradle 9.8.0, version catalog
  `gradle/libs.versions.toml`, PostgreSQL 18, Redis, Keycloak 26.7.4.
- Class-level dependency graphs (jdeps, `-filter:none`, i.e. **including same-package edges**) are
  in the scratchpad: `jdeps-backend.txt` (7155 edges), `jdeps-frontend.txt` (1984),
  `jdeps-ingest.txt` (290). Line format:
  `   <fromClass> -> <toClass>   main`. Inner classes appear as `Outer$Inner`.
- The September audit's 176 findings are extracted to `sept_audit_findings.json` in the
  scratchpad (fields: id, prio, dims, area, title, where, recommendation, guard, risk, effort,
  verification, note — German text). The vault's `80 Plans/Improvement Audit 2026-09.md` records
  which PRs implemented them.
- Binding rules (root `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md`, and the ADRs):
  - **ADR-0223**: only *final* Java features; no `--enable-preview`, no incubator modules, no
    `import module`; JEP 513 allowed in new code outside keycloak-spi; frontend request-context
    holders stay `ThreadLocal`.
  - **ADR-0214**: no code comments at all besides Javadoc/JSDoc/docstrings and tool directives;
    Javadoc short, no history.
  - **ADR-0047**: backend package graph acyclic (ArchUnit `slices().beFreeOfCycles()`), `support`
    is a dependency leaf.
  - **ADR-0020 / ADR-0028**: the bank's org-unit access seam is pinned to one class.
  - **ADR-0032**: the frontend has a single Resilience4j pass at the WebClient filter;
    `BackendApiClient` is "the single seam" (arc42 §4.1). The July audit *rejected* splitting it.
  - **ADR-0161**: REST/JSON over HTTP stays the wire format.
  - **ADR-0125 / ADR-0130**: typed JavaScript via `checkJs`, not TypeScript; own OpenAPI `d.ts`
    emitter; `docs/TYPESCRIPT_MIGRATION_PLAN.md`.
  - **ADR-0205**: `logging-support` is the only shared runtime library and must stay free of
    domain meaning; the frontend hand-mirrors backend DTOs instead of sharing a module (arc42
    §4.1), watched by `FrontendDtoContractTest` / `GeneratedDtoAgreementTest`.
  - Concurrency rules (optimistic locking with `@Version`, Mission per-section counters,
    `…WithinTransaction` MANDATORY hops, find-or-create REQUIRES_NEW retry, bulk updates after
    loops, advisory locks on exchange lots) — see `backend/CLAUDE.md`. These are load-bearing.
  - Every mutation in an audited area records an audit event in the same transaction
    (REQ-AUDIT-001). Every feature change keeps monitoring in sync (REQ-OBS-005…011). Every UI
    mutation updates the DOM in place (live update, REQ-FE-001…010). i18n for every string.
  - Lombok maximised, records for DTOs, constructor injection, JetBrains nullness annotations.
- ArchUnit: backend `ArchitectureTest` has 38+ rules, many **keyed on package names**
  (`..controller..`, `..service..`, `..repository..`, `..support..`); frontend has 7, ingest 4.

## Preliminary domain taxonomy (refine it if the code says otherwise, and say why)

Business domains: `mission` (Einsätze: missions, participants, steps, objectives, frequencies,
finance entries, payout preference), `operation` (Operationen: umbrella, payout), `joborder`
(Aufträge: job orders, items, material claims, demand, handover, production), `inventory` (Lager:
stock, holders, allocations, checkout, rebook), `personalinventory` (Mein Inventar),
`blueprint` (personal/default blueprints, craftability, import), `hangar` (ships, fleet import),
`materialexchange` (Materialbörse: offers, requests), `refinery` (Raffinerie: orders, goods,
screenshot import), `bank` (Kartellbank), `notification` (Benachrichtigungen: rules, inbox, SSE,
mail), `catalogue` (materials, categories, locations/star systems/stations/cities/outposts/POIs/
terminals, ship types, manufacturers, refining methods, job types, frequency types; UEX, SC Wiki
and P4K imports), `audit`, `promotion` (Beförderung: member evaluation), `orgchart` (Organigramm)
and `leadership` (Leitung: appointments), `orgunit` (squadrons, special commands, Bereiche, OL,
memberships), `identity` (users, registration, Discord linking, profile, terms consent, data
export, deletion), `dashboard` (announcements), `admin`/system settings, `exchange` (external
client exchange: registry, installations, change feed, journal, exchange writes).
Cross-cutting candidates: security/authorization (`AuthHelperService`, `OwnerScopeService`,
`AccessGateService`, `*SecurityService`, role/permission catalogue), live sync, metrics, logging,
error handling, configuration, i18n.

## Target-architecture options every agent evaluates against

- **A — Modular monolith by package**: inside the existing `backend` (and `frontend`) Gradle
  module, one top-level package per domain (`…backend.mission`, `…backend.bank`, …) with a small
  public API (facades/query services, commands, DTOs, domain events) and `internal` packages;
  enforced by tests (ArchUnit and/or Spring Modulith `ApplicationModules.verify()`); cross-domain
  reactions via domain events after commit where transactional coupling is not required; entity
  references across domains gradually replaced by id references; a small shared kernel.
- **B — Gradle subproject per domain** (`backend-mission-api`, `backend-mission-impl`, …): the
  compiler enforces the boundaries; heavier build, CI, dependency-verification, AOT and image
  implications.
- **C — Hybrid**: A first, then extract individual stable domains to Gradle modules where A has
  proven the boundary.
- **D — Separate deployable services**: listed for completeness; evaluate only briefly (distributed
  transactions, security surface, operations cost).
- Frontend counterparts: per-domain packages (controllers, DTO mirrors, forms, view services), and
  per-domain typed backend clients (e.g. Spring HTTP interface clients) that still pass through the
  single resilience filter chain; per-domain template and JS folders.

## Owner update 2026-09-29 (binding for every agent)

- **The backend REST API (`/api/v1/...`) may be re-cut where that serves the domain separation**
  (paths, resource shapes, controller boundaries, DTOs per domain). It is no longer a frozen
  constraint for this audit.
- **The Android app (`basetool-android`, separate repository) can be adapted** to a re-cut API. Any
  proposal that changes the API must still name the Android impact and the transition for already
  installed app versions (ADR-0136's external contract set for shipped clients exists for exactly
  that), without assuming how the owner will decide.
- **External clients are not affected**: they use the **exchange API** (`/exchange/v1` on the ingest
  gateway, `exchange-v1.openapi.json`, ADR-0216/ADR-0219), and **that contract does not change**.
  Treat the exchange contract as frozen; the backend endpoints that serve the ingest relay may only
  change if the external exchange contract stays byte-identical in behaviour.
- **Decided by the owner: installed Android app versions get a hard cut with a forced update** — no
  parallel old and new paths, no sunset window on the server. The forced update runs through the
  existing version gate `GET /api/v1/app/version-policy` (`REQ-API-010`, anonymous per
  `REQ-SEC-037`, the one unauthenticated path on the API vhost): it must stay **unchanged** by any
  re-cut, together with whatever an old app needs to reach it and render the update prompt. Plans
  must give the release sequence (new app published, server re-cut, `minimumVersionCode` raised) and
  what an old app shows in the gap.
- A re-cut API path also touches the edge (the `api.*` vhost allow-list and deny rules, ADR-0135),
  the blackbox probes, the E2E suite, `openapi.json`, the frontend's calls and the monitoring that
  keys on paths — list these consequences wherever a re-cut is proposed.

## Output format (Markdown)

1. **Summary** — the ten most important conclusions, each one sentence with its evidence pointer.
2. **Findings** — one block per finding with a stable ID in your prefix (e.g. `DOM-01`):
   title · evidence · impact on domain separation / modern features · proposed change · pros · cons
   · risks and regressions (incl. **security**) and the guard (test/gate) that would catch them ·
   effort (S/M/L/XL) · prerequisites (ADR/REQ to amend, ordering dependencies).
3. **Data appendix** — tables, counts, and the commands/scripts used.
