> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-03.
> **Owner area:** PROMO/UI · **Related ADRs:** [ADR-0242](../adr/0242-page-patterns-a-to-c-and-the-design-system-update-2026-10.md)
> (page patterns, REQ-UI-027)

# Promotion pages — rank path, own progress, topic and requirement administration

## Context & goal

The promotion feature (Beförderung) has four pages besides the evaluation matrix of
[`promotion-evaluation-matrix.md`](promotion-evaluation-matrix.md): two a member reads —
„Beförderungssystem" (`GET /promotion/overview`) and „Meine Bewertungen"
(`GET /promotion/my-evaluations`) — and two the leadership maintains — „Themenbereiche verwalten"
(`GET /promotion/admin/topics`) and „Rangvoraussetzungen verwalten"
(`GET /promotion/admin/rank-requirements`). Who reaches them, and the Staffel feature flag that
gates all of them, is in [`ROLES_AND_PERMISSIONS.md`](../ROLES_AND_PERMISSIONS.md); the in-place
writes of the two admin pages are REQ-FE-001 (#580). This spec pins what the pages show and how a
member and an officer move through them. All four wear the page head of REQ-UI-027 with the eyebrow
„Beförderung"; with „Alle Staffeln" active the admin pages show the hint to pick a Staffel instead
of their content.

## Requirements

### REQ-PROMO-002 — A member sees the rank path and their progress to the next rank

**„Beförderungssystem"** shows two cards. *Rangvoraussetzungen* is the rank path as an `.ablauf`
step list: one step per rank step („Rang 20 → 19"), ordered from the lowest rank upward (descending
`fromRank`), each listing its requirements — the category, or the topic, or „Alle Themen" for a
global one; the topic as a sub-line; the minimum level as a chip; the required count as „n×"; the
description. The step that starts at the member's rank is the current one (`step--now`,
`aria-current="step"`, chip „Du bist hier"); the steps below it are done („Erreicht"); the head
carries the chip „Dein Rang: n". *Bewertungskategorien* lists every topic with its categories and
their level texts A to C. Without rank steps or topics each card shows its `.empty-state`.

**„Meine Bewertungen"** opens with the accent card „Fortschritt zum nächsten Rang" for the **next
step** — the eligibility whose `fromRank` equals the member's rank: a status chip („Beförderbar",
„Voraussetzungen offen" or „Keine Regeln definiert"), four KPI tiles („Dein Rang", „Nächster
Rang", „Voraussetzungen erfüllt" as „met / all", „Fortschritt" in percent) and a progress bar.
The percent is `achieved / required × 100`, rounded down, where each check contributes at most its
own `requiredCount` (`achieved` capped per check, negative counts read as zero); a step without
any required count reads 100 % when eligible, else 0 %. Without a next step (no rank, or no step
configured for it) the card says so instead. Below, the table „Voraussetzungen" groups the checks
per rank step, the next step first and marked „Dein nächster Sprung"; each group head carries its
status chip and „m / n erfüllt", each row the requirement, its minimum level, the member's level
and „achieved / required". The segment „Alle · Offen" hides the met rows client-side and shows
„Alles erfüllt" when nothing stays open; the choice persists per browser
(`promotion_my_evaluations_filter`, REQ-UI-017). The card „Meine Stufen je Kategorie" follows.

**Acceptance**

- [x] The overview renders the rank steps as an ordered step list with the current step marked
  and the steps below it done.
- [x] „Meine Bewertungen" leads with the progress card of the next step, and its percent caps each
  check at its required count.
- [x] „Offen" hides every met row and survives a reload.

**Enforced by:** `PromotionAreaPagePatternRenderTest` (`overviewRendersRankSteps`,
`myEvaluationsLeadWithTheProgressBlock`), `PromotionPageControllerTest` (`findNextStep`,
`progressPercent`) · **Code:** `PromotionPageController#overview` / `#myEvaluations` /
`findNextStep` / `progressPercent`, `templates/promotion-overview.html`,
`templates/promotion-my-evaluations.html`, `static/js/promotion-my-evaluations.js`.

### REQ-PROMO-003 — Topics are kept as master-detail, rank requirements as a matrix

**„Themenbereiche verwalten"** is a master-detail page (REQ-UI-027): the master list holds one row
per topic with its category count, the detail pane the selected topic — its sort arrows, edit and
delete icons, „Neue Kategorie", and its categories, each with sort arrows, edit and delete and the
three level texts A to C (each saved by its own button; „Alle speichern" / „Verwerfen" in a banner
for every unsaved text). The page's one CTA is „Neuer Themenbereich" in the head. The selection is a
deep link: `?topic=<id>` opens that topic, an unknown or missing id opens the first one, every
selection rewrites the parameter in place (`history.replaceState`), the list moves with the arrow
keys and Home/End, and the in-place re-render after a write requests the selected topic again so it
stays open — a freshly created topic is selected.

**„Rangvoraussetzungen verwalten"** carries a toolbar search that filters the requirements
client-side, then the matrix „Rangsprung × Themenbereich": one row per rank step (with its
requirement count), one column for „Alle Themen" and one per topic. A cell with requirements shows
the highest minimum level among them (A, B or C); clicking it scrolls to that rank step's card and
highlights the cell's rows, clearing a search that hides them. An empty cell opens the „Neue
Anforderung" dialog with the rank step and the topic filled in. Below the matrix one card per rank
step lists its requirements with edit and delete, and deletes the whole step. The head's CTA is
„Neue Anforderung".

**Acceptance**

- [x] Topics render as a master-detail with the first topic open by default and `?topic=` honoured.
- [x] The rank requirements render as a matrix whose cells show the highest required level.

**Enforced by:** `PromotionAreaPagePatternRenderTest` (`topicsRenderAsMasterDetail`,
`topicsOpenTheFirstTopicByDefault`, `rankRequirementsRenderAsMatrix`),
`PromotionPageControllerTest` (`selectTopicId`), `PromotionInPlaceFragmentMvcTest`,
`PromotionTopicCrudE2eTest` · **Code:**
`PromotionPageController#adminTopics` / `#adminRankRequirements` / `selectTopicId` /
`matrixCellKey`, `templates/promotion-admin-topics.html`,
`templates/promotion-admin-rank-requirements.html`, `static/js/promotion-admin-topics.js`,
`static/js/promotion-admin-rank-requirements.js`.

## Out of scope

- The evaluation matrix `/promotion/manage` (REQ-PROMO-001).
- The backend rules that decide eligibility (`GET /api/v1/promotion/eligibility/**`).

## Open questions

None.
