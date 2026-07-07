> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-27.
> **Owner area:** MISSION/UI · **Related ADRs:** [ADR-0044](../adr/0044-mission-ablauf-procedure-steps.md),
> [ADR-0057](../adr/0057-mission-goals-classified-ordered-children.md)

# Mission detail page — tab layout (Variante B)

## Context & goal

The mission detail page (`/missions/{id}`) uses the **tab layout (Variante B)** approved in the
DAS KARTELL design system. The binding visual sources are the mockups in the design-system
submodule (`.claude/skills/das-kartell-design/proposals/mission-page-tabs-gesamt.html`,
`mission-tab-crew-board.html`, `mission-tab-finanzen.html`, `mission-tab-uebersicht-verwaltung.html`,
`mission-modals.html`) and the canonised components in `krt-components.css` (`.tab-nav`,
`.facts-bar`, `.krt-modal*`, `.person-row`, `.drop-zone`, `.chip-select`).

The rebuild is a **pure presentation restructure**: every backend contract (endpoints, DTOs,
optimistic-locking versions) and every permission gate of the previous panel layout is preserved.

## Requirements

### REQ-MISSION-004 — Tab structure, deeplink, and state

The detail page renders a sticky head (title, owning-squadron badge, status pill, and a full-size
"Anmelden" CTA — #818 follow-up: the primary action is no longer a `btn-xs2`), a high-signal
`.facts-bar`, and a `.tab-nav` with up to four tabs. The facts bar (#818 follow-up) shows five
icon-led facts at larger type — TS meeting time (headset), server join = planned start (clock),
planned end (clock), party lead (user) and a combined participants fact (users) that folds the
checked-in count into the registered count (`registered · N eingecheckt`). The three time facts
show **time only** (`data-format="time"` on the `.krt-local-dt` span, localised client-side); the
full date stays in the Übersicht details. The standalone
finance-total fact was dropped (it stays on the Finanzen tab). Its `#facts-ts` /
`#facts-planned-start` / `#facts-planned-end` / `#facts-party-lead` / `#facts-registered` /
`#facts-checked-in` ids are patched in place by the overview / crew / party-lead live-update
handlers (carried on `#overview-head-meta` + `#crew-count-meta`) so a peer's schedule, party-lead
or check-in change never leaves the bar stale (REQ-FE-010). The four tabs:

1. **Übersicht** — read-only landing tab, re-split per the final Einsatz design (owner decision
   2026-06-27, superseding the 2026-06-11 consolidated single-`.kv-list` layout; the column sides were
   swapped and a "Ziele" box added by the REQ-MISSION-012 goals change). Two columns of stacked
   panels: **left** = a "Ziele" box (the structured, classified mission goals grouped Hauptziel →
   Nebenziel → Nicht-Ziel, REQ-MISSION-012), the read-only **Ablauf** checklist (REQ-MISSION-009), a
   "Teilnehmer" attendance meter (registered count + a checked-in progress bar derived from
   `checkedInParticipants/registeredParticipants`) and a "Kalender" open card; **right** = "Mission auf
   einen Blick" (planned/actual times, meeting time, `Treffpunkt`, operation, internal chip, party lead
   as a `.kv-list`, plus the caller's personal participation chip — the former single short objective
   `Ziel` row was removed, replaced by the Ziele box), "Weitere Leads" (the leadership-position rows)
   and "Funk" (the dynamic frequencies — the central, unit-less frequencies that carry a value, then
   the custom mission-specific frequencies (REQ-MISSION-014), followed by the per-unit frequencies of
   the units that have one, each unit row tagged with a muted "Einheit" qualifier; central types
   without a value and units without a frequency are omitted and the whole panel collapses when
   nothing is set, #816). The long **Markdown**
   description moves into a collapsible gray-card `<details class="more" open>` below the grid — the same
   `--color-bg-dark-gray` panel surface as the other overview cards (owner request 2026-06-27, #818
   follow-up), **expanded by default** (owner request 2026-07-01) so the briefing is visible without a
   click, with a chevron that flips on open, replacing the former bare `hud-details` summary that
   sat directly on the honeycomb backdrop (member+ gate unchanged; rendered
   server-side via the `@markdown` bean — raw HTML escaped, unsafe link protocols stripped, so
   `th:utext` never emits user-controlled markup; the same renderer feeds the home-page next-mission
   banner). The `#overview-actual-start` / `#overview-actual-end` / `#overview-party-lead` ids and the
   `freq-value-display` markers are preserved so the schedule / party-lead / frequency live-update
   patches keep working; because the Funk panel now also omits empty entries (#816), setting a
   central frequency additionally re-renders the overview section in place
   (`krtRefreshMissionSection('overview')`, not just a peer notify) so a first-ever value surfaces a
   fresh row, and every unit add/edit/delete refreshes `['crew','overview']` so the per-unit Funk
   mirror tracks the board (REQ-FE-010). The Wirtschaft jump card is dropped (the Finanzen tab is one click away). The
   page content is capped at 1800px — 1.5× the app's regular `--content-max` (1200px), because the
   board and finance grids carry side-by-side columns (owner decision 2026-06-11).
2. **Teilnehmer & Einheiten** — the crew board (REQ-MISSION-005).
3. **Finanzen & Auszahlung** — summary strip + finance ledger (member+ gate unchanged), payout
   table (public; participation % authenticated-only), and the Wirtschaft `<details>` sections
   (authenticated + data-present gates unchanged). The summary strip's totals come from a single
   aggregate endpoint (`GET /api/v1/missions/{id}/finance-entries/summary`, same member+ /
   `canSeeMission` gate as the ledger), and the ledger table is fetched as a bounded page rather than
   loading every entry — so a live-update finance render costs one small query, not a full-ledger
   load-all (ADR-0078). The Wirtschaft `<details>` refinery/inventory lists are **no longer embedded
   in the mission DTO** (#1138): they are fetched with the rest of the finance section from
   `GET /api/v1/refinery-orders/mission/{id}` and `GET /api/v1/inventory/mission/{id}` (both
   member-gated), so the hottest mission GET no longer drags an unbounded, recursive economy payload.
4. **Verwaltung** — role-gated (`canEdit` or `canManageManagers`); hidden otherwise. The left column is
   the mission **details** form (the "Link zum Kalendereintrag" sits **last**, after the actual
   start/end); the right column stacks four cards in the order **Ziele → Ablauf → Organisation →
   Verwaltungsrechte** (owner decision). "Organisation" holds the party lead, the typed
   "Frequenzübersicht" (per-mission values for the global "Frequenztypen") and the custom
   "Weitere Frequenzen" editor (REQ-MISSION-014). "Verwaltungsrechte" carries owner & manager
   administration, the **owning-org-unit reassignment** control ("Verantwortliche Einheit" — re-homes
   the mission to a different Staffel/SK/Bereich/OL or to ownerless; REQ-ORG-018), and the delete
   action (ADMIN only). The reassignment select offers the caller's assignable org units plus "Keine";
   saving it swaps the `#mission-mgmt-results` panel in place and repaints the sticky-head
   owning-squadron badge without a reload (REQ-FE-001).

The active tab synchronises with a `?tab=` URL parameter (`ueb|crew|fin|verw`); `?tab=` takes
precedence over `#tab=`, then a server-side validation-error hint (re-renders land on Verwaltung),
then the last tab from `localStorage`, then `ueb`. Browser back/forward re-applies the URL state.
Tabs use the WAI-ARIA tabs pattern (`role="tablist"/"tab"/"tabpanel"`, `aria-selected`, arrow-key
navigation). Switching away from a Verwaltung tab with unsaved form input asks for confirmation.
The create page (`/missions/new`) renders the details form without tabs; it additionally carries the
optional create-time Ziele + Ablauf editors above the description and a floating Speichern, and on a
successful create lands the user on the new mission's Verwaltung tab (REQ-MISSION-015).

### REQ-MISSION-005 — Crew board replaces the assign-crew modal (same backend)

Participants and units render as an assign board: a "Ohne Einheit" pool plus one open
`.drop-zone` per unit (units have **no fixed size**). Assignment works via drag & drop **and** a
click fallback (select person → click target) **and** keyboard (rows and zones are focusable;
Enter/Space selects and assigns) — all three drive the **existing** crew endpoints
(`POST/PUT/DELETE …/units/{u}/crew[/{c}]/ajax`); a drop equals the action of the former
"Crew zuweisen" modal. Moving between units is remove + add; dropping on the pool removes the
assignment. Releasing a drag **outside every `.drop-zone`** (over no unit and not over the pool)
also removes the unit assignment — the participant falls back into "Ohne Einheit", so a row deep in
a long board can be unassigned by dragging into empty space without scrolling to the pool; a pool
row dragged into empty space is a no-op. While a crew row is being dragged, holding the pointer
near the **top or bottom viewport edge auto-scrolls the page** (speed eases with depth into the
edge band) so units scrolled out of view stay reachable as drop targets; the scroll stops on drop /
drag-end.

Each person row shows: check-in status dot, name (+ guest chip), org-unit badges (incl. SK),
desired job, planned job, comment as a tooltip mark, on-board function(s), check-in/check-out
(only while the mission is running and the participant's time state matches), edit, and
unregister. Row actions keep their previous gates (`canEdit` or own row or guest row). The
on-board function is a `.chip-select` per person (canEdit only): a quick single-function setter
against the crew update endpoint, with an "Edit functions…" entry opening the multi-select crew
modal (multiple functions per person stay supported). Unit heads show name, ship type, responsible
(ship owner), HVU chip (`.chip--warning`), an "x an Bord" counter, and edit/delete (canEdit).

A unit's crew list carries only participant ids; the board resolves the full participant payload via
a `participantsById` lookup and renders **no row** for a crew entry whose participant is unresolvable.
The backend keeps crew and participants in sync (removing a participant scrubs its crew), so this is
not reachable in practice, but the guard is defensive — and because Thymeleaf resolves `th:replace`
(attribute precedence 1) **before** `th:if` (3), the `cp != null` condition must gate an **outer**
`<th:block>` and never share the element with `th:replace`, or the null-safe person-row fragment
renders a ghost row (empty name, empty `data-participant-id`) past the dead guard.

### REQ-MISSION-006 — KRT modals with danger confirms naming the consequence

All page modals (participant add/edit, unit add/edit, frequency, finance add/edit, crew functions,
delete confirm) use the `.krt-modal*` frame: one filled CTA per modal, ghost cancel, close-X with
`aria-label`, focus trap, Esc closes, backdrop click closes.

The **sign-up modal** carries an "Auszahlungsart" select: an explicit choice is stored on the new
participant and wins over the user's profile default; the empty "Standard" option keeps the
existing default chain (profile default for members per REQ-MISSION-002, `PAYOUT` for guests).

**Org-unit assignment is guest-only.** The participant modals' "Org-Einheiten" multi-select is offered
only when signing up / editing a **guest**; a registered member's org units are derived from their
account and are never selected — the edit modal shows them **read-only** (all of them, as badges) and
hides the picker (owner request 2026-07-03). The add modal already hides the picker once a registered
user is matched; the edit modal toggles picker vs. read-only badge list from the row's `data-guest` /
`data-org-unit-names`.

The **unit modal** matches the approved mock: ship type and hangar ship are offered
**separately** (hangar select filtered by type, with an explicit "— keines · nur Typ verwenden —"
option), the display name (Anzeigename) is the unit's **single required field** — marked with a `*`
and `@NotBlank` on `AddUnitRequest` / `UnitForm` (owner decision 2026-07-03, superseding the earlier
name-optional / derive-from-ship-or-type rule); ship type and ship stay optional. (The service's
name-from-ship/type derivation survives for internal callers below the validated API boundary.) A
**Verantwortlich** select pins an explicit responsible person from the registered participants
(empty = automatic fallback to the assigned ship's owner, also in the board's unit head), an HVU
checkbox, the frequency field (existing function, kept beyond the mock), and a free-text **Notiz**
(shown as a tooltip mark in the unit head). `responsible_user_id` / `note` live on `mission_unit`
(V149); the responsible person is exposed as a PII-free `UserReferenceDto`.

The finance modal's type is an income/expense segment control mirroring into the classic `type`
form field. The delete confirmation uses the `--danger` variant and names the consequence per
sub-section (participant / unit incl. crew fallback to the pool / crew / mission / finance entry).

### REQ-MISSION-007 — No regression of permissions, contracts, or concurrency behaviour

Every `sec:authorize` / `th:if` permission gate of the previous layout carries over 1:1 (finance
panel member+; participation % authenticated; payout-select disable logic; participant actions
canEdit/own/guest; check-in/out time-state conditions; Wirtschaft authenticated + data; Verwaltung
by edit permission). Backend endpoints, DTOs and the optimistic-locking flow (`version` echo,
`data-version` DOM sync, 409 handling via `MissionSubresource`) are unchanged. Mission data shown
read-only to non-editors in the old Details panel remains visible via the Übersicht tab.

### REQ-MISSION-016 — Crew-board write concurrency: idempotent check-in and versioned unit/crew edits

Two crew-board writes that REQ-MISSION-007 assumed already lock-safe were not, and are hardened here
(round-2 audit, epic #1109):

- **Check-in is idempotent (#1134).** A participant's `startTime` feeds the credited-time payout
  breakdown, so it MUST NOT be overwritten by a repeated check-in. `checkIn` sets `startTime` to
  `now()` **only** when it is still `null`; a second check-in (a duplicate delivery, or a stale crew
  board — REQ-FE-010 window — that still renders the "Einchecken" button) is a **no-op** that
  preserves the original arrival time and emits **no** second `MISSION_PARTICIPANT_CHECKED_IN` audit
  event (a no-op is not a state mutation). `checkOut` keeps its opposite, override-on-repeat semantics
  (late check-out corrections) on purpose.
- **Unit and crew edits carry a real optimistic-lock version (#1131).** REQ-MISSION-007's "`version`
  echo / `data-version` DOM sync" did **not** in fact exist for `MissionUnit` / `MissionCrew`: their
  `@Version` was never surfaced in a DTO, the edit forms echoed nothing, and because the update
  rewrites every field from the caller's snapshot the entity `@Version` could never fire on a stale
  form — a two-manager edit silently lost one update. The unit/crew edit paths now echo the child
  `@Version` end-to-end (`MissionUnitDto` / `MissionCrewDto` expose it, the edit buttons render
  `th:data-version`, `UpdateUnitRequest` / versioned `UpdateCrewRequest` carry it, the service checks
  it via `OptimisticLock.checkOptionalClient`), so a stale full-form save returns `409` instead of
  clobbering; the fresh version rides back on the `crew` fragment re-render. Client-side contract:
  REQ-FE-003.

**Acceptance**

- [ ] A repeated check-in preserves the original `startTime` and records no second `CHECKED_IN` audit
  event; a first check-in still stamps `startTime` and audits once.
- [ ] A stale mission unit or crew edit (client-echoed `@Version` behind the persisted row) returns
  `409`; a matching version succeeds and the follow-up edit does not 409 (fresh version from the
  fragment re-render).
- [ ] A per-unit / per-crew edit never 409s a concurrent core/schedule/flags/participant edit
  (per-row lock scope preserved).

**Enforced by:** `MissionServicePayoutTest` (repeated check-in preserves `startTime`),
`MissionServiceCrewTest` (unit/crew version-mismatch 409, matching-version success). **Code:**
`MissionParticipantService#checkIn`, `MissionStructureService#updateMissionUnit`/`#updateCrewInShip`,
`MissionUnitDto` / `MissionCrewDto` / `UpdateUnitRequest` / `UpdateCrewRequest`, `mission-detail.html`,
`mission-detail.js`. **Issues:** #1134, #1131 (epic #1109).

### REQ-MISSION-017 — DB-enforced mission section-lock counters and row-version decoupling

The per-section optimistic-lock counters (`coreVersion` / `scheduleVersion` / `flagsVersion` /
`partyLeadVersion` / `stepsVersion` / `objectivesVersion` / `owningOrgUnitVersion`, REQ-MISSION-009 /
-012 / -016) were checked and bumped **in memory** (`assertSectionVersion` + `bumpSectionVersion`).
That read-then-write has a TOCTOU window under concurrency, and the mission's business scalars were
not excluded from the row lock. Three concrete defects (round-2 audit, epic #1109):

- **Silent lost update on the fully-excluded sections (#1112).** `setPartyLead` and
  `updateOwningOrgUnit` mutate only `@OptimisticLock(excluded = true)` fields, so their `save`
  issued a non-versioned UPDATE; two overlapping reassignments both passed the in-memory check and
  the later commit silently overwrote the other — no 409.
- **Cross-section 409 on the header sections (#1114).** `name` / `description` / `meetingPoint` /
  `calendarLink` / `status` / the schedule timestamps / `isInternal` were **not** excluded, so any
  core/schedule/flags edit bumped the row `@Version` and 409-ed an *unrelated* concurrent
  section edit — the coarse, screen-wide lock CLAUDE.md flags as a defect.
- **Duplicate `orderIndex` on steps/goals (#1147).** Two concurrent `addStep`/`addObjective` both
  computed `orderIndex = max + 1` on the same snapshot and both committed, so `@OrderBy` rendered a
  nondeterministic tie; concurrent reorders/deletes interleaved into gaps.

**Fix.** Each section's check-and-bump is now a **single DB-enforced atomic conditional**
`UPDATE Mission … SET xVersion = xVersion + 1 WHERE id = :id AND xVersion = :expected`
(`MissionRepository.bump*VersionIfMatches`, dispatched by the private `MissionSection` enum through
`MissionSectionVersions.enforceSectionVersion`); **0 rows affected → 409**. The statement row-locks
the mission, so two racing same-section writers genuinely serialise — the loser blocks, re-reads the
bumped counter and 409s. This is safe only because **every mutable `Mission` scalar and association is
`@OptimisticLock(excluded = true)` and the entity is `@DynamicUpdate`**: a section edit dirties only
its own columns, so it never bumps the row `@Version` (no cross-section 409) and the column-narrowed
flush never clobbers a concurrent other-section change. The legacy full-replace `updateMission`
(`PUT /missions/{id}`), whose only remaining guard once the scalars are excluded is the row
`@Version`, force-increments it via JPA `OPTIMISTIC_FORCE_INCREMENT` (`findByIdForFullReplace`), so
two concurrent whole-mission overwrites still 409. Steps and goals additionally gain a **deferrable
unique `(mission_id, order_index)` constraint** (V208) as the DB backstop — `DEFERRABLE INITIALLY
DEFERRED` so a reorder's transient in-flush collision is tolerated, yet a genuinely duplicate ordinal
is rejected at commit. The two unconditional cross-section pokes with no client echo keep the
in-memory `bumpSectionVersion`: the legacy full-replace and the activation auto-stamp of
`actualStartTime`.

**Acceptance**

- [ ] Two concurrent same-section writers (append step / reassign party lead) against the same start
  version: exactly one commits, the rest 409 (no duplicate `orderIndex`, no lost party-lead).
- [ ] A core edit and a concurrent schedule edit on the same mission both succeed — neither 409s the
  other, and neither bumps the row `@Version`.
- [ ] A stale section-version echo returns 409; a matching echo advances only that section's counter.
- [ ] A reorder that swaps ordinals commits without a unique violation; a duplicate `(mission_id,
  order_index)` is rejected once the deferred constraint is checked.
- [ ] Two concurrent legacy full-replace `updateMission` saves: exactly one wins, the rest 409.

**Enforced by:** `MissionSectionLockConcurrencyTest` (real-contention same-section one-winner +
cross-section no-collision), `MissionSectionLockDbEnforcementTest` (conditional bump, row-version
decoupling, deferred-constraint tolerance + backstop), `MissionUniqueIndexBackstopTest`,
`ConcurrencyTest` (full-replace one-winner), and the extended `MissionServiceSectionPatchTest` /
`MissionStepServiceTest` / `MissionObjectiveServiceTest`. **Code:** `Mission` (`@DynamicUpdate` +
per-scalar `@OptimisticLock(excluded = true)`), `MissionRepository.bump*VersionIfMatches` /
`findByIdForFullReplace`, `MissionSectionVersions.enforceSectionVersion`, `MissionService` /
`MissionTimelineService` / `MissionParticipantService`, migration `V208`. **Issues:** #1112, #1114,

# 1147 (epic #1109).

### REQ-MISSION-009 — Ablauf (procedure timeline) steps

A mission carries an ordered, reorderable list of **Ablauf** steps — a procedure timeline. Each step
is a persisted `MissionStep` child of the mission (`title` required ≤500 chars, optional free-text
`meta` "Zeit / Ort" hint ≤200 chars, a shared `done` flag, an explicit `orderIndex`). The Ablauf is
authored in the **Verwaltung** tab through a drag-sortable editor (`#mission-step-list`: per-row
title + meta inputs, up/down + drag reorder, delete, "Schritt hinzufügen", a live "N Schritte"
counter) and shown **read-only** in the Übersicht as an `<ol class="ablauf">` checklist whose single
**current phase** (`step--now`) is *derived* as the first not-done step (never stored). **When no
steps are authored the whole Ablauf tile is omitted** from the Übersicht — no empty
"Noch keine Schritte." placeholder — and reappears in place through the `['steps','overview']`
section swap once the first step is added (owner request 2026-07-01). Edit-authorised
users (`mission.canEdit` / `@missionSecurityService.canManageMission`) toggle a step's shared `done`
check directly on the overview checklist; the state is visible to every viewer. Outsiders/guests see
the Ablauf read-only (it is non-PII planning data, forwarded like units/frequencies; ADR-0044).

All five mutations (add / edit / remove / reorder / done-toggle) go through dedicated slim endpoints
`…/missions/{id}/steps[/{stepId}][/reorder|/done]/slim` (`@PreAuthorize canManageMission`), each
echoing the mission's dedicated **`stepsVersion`** section counter — a manual `@OptimisticLock(excluded
= true) Long` in the `coreVersion`/`scheduleVersion`/`flagsVersion`/`partyLeadVersion` family — so an
Ablauf edit never 409s a concurrent core/schedule/flags edit, and a stale `stepsVersion` surfaces as
HTTP 409. Reorder reassigns `orderIndex` over the managed children by dirty-checking (no per-child
save, no clearing bulk query mid-loop) and records **one** event. Mutations re-render the editor +
overview-checklist fragments in place via `krtFetch`/`krtRefreshMissionSection(['steps','overview'])`
(no reload) and propagate to peers over the presence socket (REQ-FE-010, ADR-0031). Missionen is an
audited area: each mutation records a `MISSION_STEP_*` event (`ADDED` / `UPDATED` / `REMOVED` /
`REORDERED` / `DONE_CHANGED`) carrying only ids/counts/the done flag — **never** the step title or
meta (free text), per REQ-AUDIT-001. Migration: V192 (`mission_step` table + `mission.steps_version`). Steps may additionally be **seeded at
mission-create time** (each still recording `MISSION_STEP_ADDED`) — REQ-MISSION-015.

### REQ-MISSION-010 — Rally point (Treffpunkt)

A mission carries the short free-text core-section field **`meetingPoint`** (Treffpunkt, ≤200 chars —
the rally point), edited in the Verwaltung details form and belonging to the **core** section (guarded
by `coreVersion`, persisted via the existing `/core` patch; no new lock). It is non-PII planning data,
forwarded to outsiders/guests like the units and frequencies (the long Markdown description remains the
one free-text field hidden from outsiders, capped at **20,000 chars** — owner request 2026-07-03;
the `mission.description` column is already `TEXT`, so the cap moved only on the DTOs / form, no
migration). Migration: V192 (`mission.meeting_point`).

> The former single short **`objective`** (Ziel, ≤250 chars, shown first in "Mission auf einen Blick")
> was **superseded by the structured, classified mission goals** of REQ-MISSION-012. V199 drops
> `mission.objective`, migrating each existing non-empty value into one Hauptziel so no planning data
> is lost.

### REQ-MISSION-011 — Operation detail page adopts the Variante B tab shell

The operation detail page (`/operations/{id}`) — the umbrella over missions, also an "Einsatz-Seite"
under #818 — is restructured from the legacy collapsible-column layout to the **same tab shell**
(sticky head + `.facts-bar` + `.tab-nav`) with five tabs: **Übersicht** (read-only landing: "Operation
auf einen Blick" status/mission-count/result/donations, an "Ergebnis je Einsatz" proportional result
bar per linked mission from the operation finance breakdown, an Einsätze preview list, and a
collapsible Markdown description), **Einsätze** (the paginated linked-missions table — REQ-FE-002 AJAX
pager unchanged — with an "Einsatz hinzufügen" shortcut that opens `/missions/new?operationId={id}`
with the operation preselected, editor-only), **Auszahlung** (the operation payout table + paid-out
toggle, unchanged), **Finanzen** (a summary strip + the per-mission finance breakdown as native
`<details>`), and **Verwaltung** (the details form — name / status / description — with the delete +
single Speichern CTA). This is a **frontend-only** restructure: operations have **no** owner or
per-operation managers (the mockup's owner/manager panels were clones of the mission design and are
deliberately omitted — edit access stays the role-based `canEdit`), and the read-only details form
remains visible (disabled) to non-editors as before.

The description field gains a **Markdown editor** (editor-only): a B / I / heading / list / link
formatting toolbar that wraps the textarea selection client-side, and a "Bearbeiten / Vorschau"
toggle whose preview is rendered **server-side** via `POST /operations/markdown-preview` through the
same `@markdown` (`MarkdownRenderer`) bean the page uses on save — so the preview is byte-identical to
the persisted render (raw HTML escaped, unsafe link protocols stripped). No backend, DTO, migration or
permission change; every existing operation contract (save / delete AJAX twins, payout paid-out
asymmetric authorization, missions pager) is preserved.

### REQ-MISSION-012 — Mission goals (Ziele) as classified, ordered children

A mission carries an ordered, reorderable list of **goals** (Ziele) that **replaces** the former
single short `objective` (REQ-MISSION-010). Each goal is a persisted `MissionObjective` child of the
mission (`title` required ≤500 chars, a `kind` classification, an explicit `orderIndex`). The
classification is one of three kinds — **Hauptziel** (`PRIMARY`), **Nebenziel** (`SECONDARY`) and
**Nicht-Ziel** (`NON_GOAL`, an explicit *non*-goal the operation deliberately does not pursue, stated
to bound the scope). A goal has **no** `done` flag (it is a scope statement, not a progress item like
an Ablauf step) and **no** free-text `meta`.

Goals are authored in the **Verwaltung** tab through a drag-sortable editor (`#mission-objective-list`:
per-row title input + a kind `<select>`, up/down + drag reorder, delete, "Ziel hinzufügen", a live "N
Ziele" counter) and shown **read-only** in the Übersicht as a dedicated **"Ziele" box** — the first
panel of the left column — that **groups** the goals by kind: all Hauptziele first, then Nebenziele,
then Nicht-Ziele, each group under its localized header, empty groups omitted. **When the mission has
no goals at all the whole Ziele box is omitted** — no empty "Noch keine Ziele." placeholder — and
reappears in place through the `['objectives','overview']` section swap once the first goal is added
(owner request 2026-07-01). Edit access is the mission's `canManageMission` gate (no new permission).
Outsiders/guests see the Ziele box read-only — it is non-PII planning data, forwarded like the Ablauf
steps, units and frequencies (ADR-0057).

All four mutations (add / edit / remove / reorder) go through dedicated slim endpoints
`…/missions/{id}/objectives[/{objId}][/reorder]/slim` (`@PreAuthorize canManageMission`), each echoing
the mission's dedicated **`objectivesVersion`** section counter — a manual `@OptimisticLock(excluded =
true) Long` in the `coreVersion`/`scheduleVersion`/`flagsVersion`/`partyLeadVersion`/`stepsVersion`
family — so a goal edit never 409s a concurrent core / schedule / flags / Ablauf edit, and a stale
`objectivesVersion` surfaces as HTTP 409. Reorder reassigns `orderIndex` over the managed children by
dirty-checking (no per-child save, no clearing bulk query mid-loop) and records **one** event.
Mutations re-render the editor + overview-Ziele fragments in place via
`krtFetch`/`krtRefreshMissionSection(['objectives','overview'])` (no reload) and propagate to peers
over the presence socket (REQ-FE-010, ADR-0031). Missionen is an audited area: each mutation records a
`MISSION_OBJECTIVE_*` event (`ADDED` / `UPDATED` / `REMOVED` / `REORDERED`) carrying only ids / counts /
the **kind enum** — **never** the goal title (free text), per REQ-AUDIT-001. Migration: V199
(`mission_objective` table + `mission.objectives_version`), which also drops the legacy
`mission.objective` column after migrating each existing non-empty value into one `PRIMARY` goal.
Goals may additionally be **seeded at mission-create time** (each still recording
`MISSION_OBJECTIVE_ADDED`) — REQ-MISSION-015. Decision:
[ADR-0057](../adr/0057-mission-goals-classified-ordered-children.md).

### REQ-MISSION-013 — Facts-bar leader (Einsatzleiter) and Treffpunkt

**Leader.** The sticky facts-bar **"Leiter"** cell shows the mission's **Einsatzleiter** — the
participant whose `plannedMissionJobType` is the single designated **mission-lead** job type
(`JobType.isMissionLead`) — falling back to the mission **owner** when no Einsatzleiter is assigned,
and to "none" otherwise (the owner is redacted for outsiders, so a guest with no Einsatzleiter sees
"none"). This **replaces** the former behaviour where the facts bar mirrored the built-in
**Partyleiter** (`partyLeadUser`); the Partyleiter remains a separate field shown in the "Mission auf
einen Blick" panel and is no longer reflected in the facts bar. The same Einsatzleiter is **also**
surfaced as a dedicated **"Einsatzleiter" row directly above the Partyleiter** in that overview panel
(`#overview-einsatzleiter`), reusing the identical `factLeaderName` value, so it live-updates through
the overview fragment swap (owner request 2026-07-03). The leader name is computed
server-side, rendered into the facts cell and exposed on the `#overview-head-meta` fragment as
`data-leader`; the `krt:swapped` handler patches the cell (which lives outside the overview fragment)
on every overview refresh. Because the leader derives from a participant's planned job type, the
participant **edit** and **unregister** flows additionally refresh `['…','overview']` so the cell never
goes stale (REQ-FE-010).

**Einsatzleiter designation.** "Einsatzleiter" is **not** hard-coded: it is a single, admin-set
designation on the job-type reference data. `JobType` carries a `isMissionLead` flag; **at most one**
job type may hold it (DB-enforced by a partial unique index, V200) and only a `MISSION`-archetype
**leadership** role may be designated. The flag is set on the job-type admin page (`/admin/mission-data`);
re-designating moves it (the service clears the previous holder, and rejects a non-MISSION/non-leadership
designation with 400).

**Single Einsatzleiter per mission.** A mission may have only **one** Einsatzleiter: assigning the
designated mission-lead `plannedMissionJobType` to a second participant is rejected with **HTTP 409**
(`BusinessConflictException`) — the editor must first clear the existing one. JobType is not an audited
area, so no audit event is added.

The in-memory reject is only the friendly fast path; it has a TOCTOU window where two managers make
two *different* participants the Einsatzleiter at once (each write hits a different row, so nothing
serializes them). The **DB backstop** is a derived `is_mission_lead_participant` flag (maintained
whenever the planned job type changes, and cleared for a job type that loses the designation) plus a
**partial unique index** `uq_mission_participant_single_lead ON mission_participant (mission_id) WHERE
is_mission_lead_participant` (V206): the raced second assignment fails the index as a
`DataIntegrityViolationException`, which `GlobalExceptionHandler` maps to the same **409** — so at
most one Einsatzleiter per mission is guaranteed even under concurrency (#1113, following the V96 /
V200 precedent). The sibling per-crew invariant — a participant sits in at most one crew — gets the
same treatment: a **unique index** `uq_mission_crew_participant ON mission_crew
(mission_participant_id)` (V207) backstops the in-memory `anyMatch` in `MissionStructureService`, so
two managers dragging the same participant onto two units concurrently no longer double-seat them
(#1132).

**Treffpunkt.** The mission's `meetingPoint` (Treffpunkt) is surfaced in two more places: a facts-bar
cell (map-pin icon) **after the planned-end time** on the detail page, and on the **home-page mission
tile** (Einsatzkachel) **between the status and the TeamSpeak meeting time**. The latter requires
`meetingPoint` on `MissionListDto` (backend + frontend, auto-mapped from the entity). Both render only
when a meeting point is set. Migrations: V200 (`job_type.is_mission_lead`), V206
(`mission_participant.is_mission_lead_participant` + `uq_mission_participant_single_lead`), V207
(`uq_mission_crew_participant`).

### REQ-MISSION-014 — Custom (mission-specific) radio frequencies

Beyond the shared "Frequenztypen" reference data (the global `FrequencyType`s a mission can assign a
per-mission value to), a mission may carry any number of **custom, mission-specific radio
frequencies** — a free-text label plus a value. This makes the `MissionFrequency` row **dual-mode**:
it is either **typed** (references a global `FrequencyType`, no `name`) or **custom** (carries a
`name`, no `frequencyType`). The invariant is DB-enforced by a `frequency_type_id XOR name` check
constraint (V201, which also makes `frequency_type_id` nullable and adds the `name VARCHAR(100)`
column); the existing `(mission_id, frequency_type_id)` unique constraint still bounds typed rows to
one per type, while multiple custom rows are allowed (each NULL `frequency_type_id` is distinct).

**Typed upsert is atomic and last-writer-wins (#1148).** The **typed** channel's set-or-update
endpoint takes no client version by design and now goes through a single atomic `INSERT … ON
CONFLICT (mission_id, frequency_type_id) DO UPDATE`
(`MissionFrequencyRepository.upsertTypedFrequency`) instead of a find-in-memory-then-save. That
removes the check-then-act TOCTOU where two managers setting a never-set channel near-simultaneously
both INSERTed and the loser got an unresolvable 409 (a plain retry would have won); a concurrent
first-time-set can no longer conflict, and last-writer-wins holds for an existing row too — matching
the frequencies collection being `@OptimisticLock(excluded = true)` (a frequency change never 409s a
concurrent core/schedule/flags edit). The former per-row `data-version` sync in the typed edit
handler was dead state the payload never sent and is removed. The **custom** channel keeps its real
client-version optimistic-lock check (it echoes a `data-version`), so the two paths are now coherent.

The **value carries the same input limits as the typed frequencies** — up to three integer digits and
two decimals (0 – 999.99), matching the `precision = 5, scale = 2` column and the frontend
`^\d{1,3}([.,]\d{1,2})?$` pattern; the label is required and ≤100 chars. Custom channels are authored
in the **Verwaltung** tab's "Organisation" card under a "Weitere Frequenzen" editor (a list with an
"Frequenz hinzufügen" button plus per-row edit/delete, add/edit through a shared KRT modal — no native
dialogs) and shown **read-only** in the Übersicht "Funk" panel alongside the typed and per-unit
channels. They are non-PII planning data, forwarded to outsiders/guests like the typed frequencies,
units and Ablauf steps.

The three mutations go through dedicated slim endpoints: `POST …/missions/{id}/frequencies/custom/slim`
(add) and `PUT …/missions/{id}/frequencies/custom/{freqId}/slim` (edit) each return the updated slim
frequency list; delete reuses the generic `DELETE …/missions/{id}/frequencies/{freqId}/slim`. All are
`@PreAuthorize @missionSecurityService.canManageMission`. The edit path optimistic-locks on the
frequency row's own `@Version` (a stale echo surfaces as HTTP 409) and rejects reaching a typed row
through the custom path; the mission's `frequencies` collection is `@OptimisticLock(excluded = true)`,
so a frequency change never 409s a concurrent core/schedule/flags edit. The editor and overview Funk
fragments re-render in place via `krtFetch`/`krtRefreshMissionSection(['frequencies','overview'])` (no
reload) and propagate to peers over the presence socket (REQ-FE-010, ADR-0031). Missionen is an audited
area: add/edit record `MISSION_FREQUENCY_CHANGED` and delete records `MISSION_FREQUENCY_REMOVED`,
carrying only the row id — **never** the free-text label — per REQ-AUDIT-001. Migration: V201
(`mission_frequency.name` + nullable `frequency_type_id` + the XOR check constraint).

### REQ-MISSION-015 — Create-time Ziele/Ablauf seeding, Verwaltung landing, and floating Speichern

**Seeding goals + steps at create.** The create form (`/missions/new`) carries the Ziele
(REQ-MISSION-012) and Ablauf (REQ-MISSION-009) editors **above** the description field so a planner can
lay out goals and steps in the same action instead of a follow-up per-item call. Both are **optional**
(an empty section seeds nothing) and can equally be added later through the Verwaltung section editors.
Because the mission has no id yet, these are **client-side rows** — no per-row AJAX, no section version
— reusing the same `.ae-row` markup, the Klassifizierung `<select>` and the time/place `meta` field as
the Verwaltung editors. On submit the rows are serialized (blank-title rows dropped) into two hidden
JSON carriers (`objectivesJson` / `stepsJson`) bound to the `MissionForm`; the write controller parses
them into the backend `CreateMissionRequest`'s nested `objectives` / `steps` lists, which the service
persists onto the just-created mission at a contiguous `orderIndex` with **no** version check/bump (no
concurrent editor exists at create). The rows survive a validation-failure re-render (the JSON carriers
round-trip through the form binding and re-hydrate the editors on load). Missionen is an audited area:
each seeded goal/step records the same `MISSION_OBJECTIVE_ADDED` / `MISSION_STEP_ADDED` event as its
post-create counterpart, carrying only the id (plus the goal kind) — **never** the title — per
REQ-AUDIT-001. `CreateMissionRequest` stays the create-path security boundary (audit finding C-3):
`objectives` / `steps` are an explicit, `@Valid`-checked addition (non-blank title, valid kind), and
the id / orderIndex / step done-state remain server-stamped.

**Landing on Verwaltung after create.** A successful create redirects to the new mission's detail page
on its **Verwaltung** tab (`redirect:/missions/{newId}?tab=verw`, via the REQ-MISSION-004 `?tab=`
deeplink), so the planner keeps working (crew, refine goals/steps) instead of being dropped on the
list. The frontend create handler reads the created mission's id from the backend `MissionDto` response
for the redirect (replacing the former `redirect:/missions`).

**Sticky action row in Verwaltung.** In the Verwaltung tab — also the sole pane on the create page —
the whole action row (**Löschen · Speichern · Zurück**) is pinned to the bottom of the viewport
above the fixed footer while the tiles scroll behind it (owner request 2026-07-03, superseding the
earlier float-just-the-save-button design). The `.footacts` bar is `position: fixed` above the footer
(z-index above the footer, below the modal overlay and the sidebar drawer), with a solid bar
background + top border; the Save keeps its `form="mission-form"` binding (the classic no-JS submit is
unchanged). The rule is scoped to the **active** Verwaltung pane so it never pins on another tab, and
the pane reserves extra `padding-bottom` so the last tile is never hidden behind the bar.

**Full-width Details card on the create page.** The Verwaltung pane's two-column grid (`.pane-grid-2`)
carries the Details card in the left column and the edit-only editors (Ziele/Ablauf/Organisation/
Manager, all `th:if="${!isNew}"`) in the right. On the create page the right column has no content, so
the grid collapses to a **single full-width column** (`.pane-grid--single`, applied only when `isNew`)
and the empty right column is not rendered — the Details form spans the full page width and stays
centred beneath the greeting header instead of being stranded in the left half.

**Enforced by:** `MissionTimelineCreateSeedTest` (create-time seeders: contiguous orderIndex, no version
bump, no re-fetch, id-only / kind-only audit) + `MissionServiceTest` /
`MissionControllerCreatePathTest` (create request wiring) + `MissionPageControllerTest` (create form) ·
**Code:** backend `CreateMissionRequest` (nested `NewObjective` / `NewStep`),
`MissionService.createMission` / `MissionTimelineService.addObjectiveAtCreate` / `addStepAtCreate`;
frontend `MissionForm` (`objectivesJson` / `stepsJson`), `CreateMissionRequest`,
`MissionWriteController.createMission`, `mission-detail.html` (create editors + floating-save rule),
`mission-detail.js` (create-form editor module).
