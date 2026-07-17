> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-17.
> **Owner area:** PROMO · **Related ADRs:** ADR-0102 (shared page-walk helper), ADR-0100
> (no-silent-truncation defect class)

# Promotion evaluation matrix — complete rendering, no silent truncation

## Context & goal

The Bewertungsverwaltung (`GET /promotion/manage`, ADMIN or OFFICER) renders the squadron's
promotion **evaluation matrix**: one row per evaluatable member, one column per promotion
category, each cell the member's stored level for that category. Officers filter the matrix
client-side (member search, "nur Beförderbare", "nur ohne Bewertung") over the fully-rendered
rows — there is no server-side pagination UI.

The two axes are fetched from paginated backend endpoints: the members axis from
`GET /api/v1/promotion/evaluations/members` and the cell data from
`GET /api/v1/promotion/evaluations/all`. Historically the page pulled each in **one** bounded
request (`members?size=1000`, `evaluations/all?size=10000`) and presented the result as the
complete matrix. That is a silent-truncation defect: **evaluations grow multiplicatively**
(members × categories), so a mid-sized squadron crosses the evaluation bound long before either
axis looks large, and past the bound a cell simply does not arrive — rendering as an empty cell
**indistinguishable from "not yet evaluated"**. There is no pagination control and no hint, so a
missing grade reads as a member the officers still have to assess. The client-side filters only
narrow what was fetched, so a truncated row cannot be searched back into view.

This is the same defect class ADR-0100 named for the catalog pickers and ADR-0102 fixed for the
admin reference-catalog pages; this spec pins the promotion matrix onto the same shared page-walk.

## Requirements

### REQ-PROMO-001 — The evaluation matrix renders every member and every evaluation

The `/promotion/manage` matrix MUST assemble **both** axes completely, not one bounded page each:
the members axis and the evaluation-cell axis are each fetched by walking every backend page until
the last one, through the shared `CatalogPages.fetchAll` helper (REQ-ADMIN-001, ADR-0102) — the
per-request `size` is the walk's chunk size, so a squadron that fits one chunk still costs exactly
one request per axis. No member row and no evaluation cell may be silently dropped at any squadron
size.

Should either walk stop at its safety cap (`CatalogPages.MAX_CATALOG_PAGES`) while the backend
still reports more pages, the page MUST render a prominent warning banner
(`promotion.manage.truncated`, DE + EN) instead of presenting a partial matrix as complete — the
same loud-truncation rule as REQ-ADMIN-002. The banner lives **inside** the `matrixBody` fragment
so it is present on both the full-page render and the in-place matrix re-render (the
optimistic-lock conflict recovery swap, REQ-FE-005), and clears the same way. A backend failure
keeps the page's established fail-soft-to-empty behaviour (empty matrix, no banner).

The read-only self-view (`/promotion/my-evaluations`) and the public overview
(`/promotion/overview`) are out of scope: they render the caller's own evaluations
(`evaluations/my`, a bounded per-user list) and the rank-requirement catalogue, neither of which
is the cross-member matrix this requirement governs.

**Acceptance**

- [ ] With evaluations spanning more than one page chunk, every cell renders (multi-page
  responses are concatenated in order); with members spanning more than one chunk, every member
  row renders.
- [ ] With both axes fitting one chunk, exactly one backend request is made per axis (no
  regression for the common case).
- [ ] When either page walk hits `CatalogPages.MAX_CATALOG_PAGES`, `matrixTruncated` is `true`
  and the `promotion.manage.truncated` banner renders on both the full-page and the `matrixBody`
  fragment render path; a completed walk renders no banner.
- [ ] A backend failure on either axis degrades to an empty matrix with no banner (the page's
  prior fail-soft contract), never a 500.

**Enforced by:** `PromotionManagePageControllerMvcTest` (multi-page concatenation, single-request
common case, cap-hit banner), `CatalogPagesTest` (the shared walk + `truncated` flag),
`MessageBundleConsistencyTest` (`promotion.manage.truncated` in every bundle) · **Code:**
`frontend controller.PromotionPageController#fetchAllEvaluations` / `#fetchMembers` / `#manage`,
`frontend support.CatalogPages.fetchAll`, `templates/promotion-manage.html` (matrixBody banner) ·
**Issues:** — (ADR-0100 silent-truncation audit follow-up)

## Out of scope

- A server-side pagination UI for the matrix. The load-all + client-side-filter interaction model
  is deliberate and shared with the admin catalog pages (ADR-0102 records why page-walking was
  chosen over a pagination UI).
- The backend `size` clamp itself (`PaginationUtil.MAX_PAGE_SIZE`, SEC-03 rationale in its
  Javadoc).
- The single-member eligibility re-render (`fragment=eligibilityCell`) and the rank-requirement /
  category admin pages — those fetch bounded per-entity or already-complete lists, not the matrix.

## Open questions

None.
