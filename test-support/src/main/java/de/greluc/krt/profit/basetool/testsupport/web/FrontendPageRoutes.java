/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.testsupport.web;

import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The frontend's page routes, in one place, for every guard that walks them.
 *
 * <p><b>Why this exists.</b> Three E2E classes each carried their own copy of this information:
 * {@code TouchClassLayoutE2eTest.PAGES} (the superset), {@code CorePagesSmokeE2eTest} and {@code
 * AdminPagesSmokeE2eTest} (two slices of it). Three hand-maintained lists of the same facts agree
 * only for as long as somebody remembers all three, and on 2026-09-13 that failed measurably:
 * {@code PAGES} was short by <b>seventeen</b> page routes while its own Javadoc and {@code
 * REQ-UI-009}'s "Enforced by" clause both claimed every route the controllers expose was covered.
 * Adding the seventeen found a real defect on the first run — {@code /admin/notification-rules}
 * renders a 1303px-wide table with no {@code .table-responsive} container, which scrolls the page
 * sideways at every device class, desktop included — and {@code /organisation/leitung} was missing
 * while the very change under review was adding {@code .leitung-group-actions} to a phone flex-wrap
 * fix, so that fix shipped unmeasured.
 *
 * <p><b>What makes this a gate rather than a tidier list.</b> One list is still a list somebody has
 * to remember. {@code PageRouteCatalogueTest} asks the dispatcher for every mapping it knows and
 * fails when a route is in neither {@link #PAGES} nor {@link #NOT_PAGES}. A page added next month
 * is covered on the day it is added, or the build goes red naming it — the same substitution {@code
 * AnonymousSurfaceSweepMvcTest} makes for REQ-SEC-052, on the same engine ({@link
 * EndpointEnumeration}).
 *
 * <p><b>Why not on {@code E2eSupport}, where the touch sweep's Javadoc pointed.</b> Three of the
 * four consumers live in the frontend's {@code e2e} source set, which would have been the natural
 * home for them. The gate is the fourth, and it needs a Spring context and the frontend's own
 * classes, so it lives in {@code src/test} — which by design neither compiles nor depends on the
 * {@code e2e} source set, deliberately kept out of {@code check} because that suite needs a running
 * stack and a downloaded Chromium. A catalogue on {@code E2eSupport} could therefore only be gated
 * by a test that also needed that stack, and would then run only on pull requests carrying the
 * {@code e2e} label — so a controller change without that label would slip past exactly the check
 * built to catch it. {@code test-support} is already on both classpaths ({@code e2eImplementation}
 * extends {@code testImplementation}), which is what lets one catalogue serve a {@code check}-time
 * gate and a stack-time sweep at once.
 *
 * <p><b>Classification stays at runtime, and that is load-bearing.</b> Entries in {@link #PAGES}
 * that turn out not to render an app shell are skipped by the sweep where they are measured, not
 * filtered out here. A Basetool page is recognised by its shell; a fragment or a JSON endpoint
 * simply does not have one. Keeping such entries listed and letting the run classify them is how a
 * route that quietly <i>stops</i> rendering a shell surfaces, instead of being silently absent from
 * a curated list. It is also why the gate compares against the union of two hand-written lists
 * rather than deriving "is a page" from a return type: {@code /inventory/my/stack/entries} returns
 * a view name exactly as {@code /inventory/my} does, and only one of the two is a page.
 */
public final class FrontendPageRoutes {

  /** Not instantiable: this is a catalogue, not a collaborator. */
  private FrontendPageRoutes() {
    throw new AssertionError("no instances");
  }

  /**
   * Every route the frontend answers with a page, taken from its {@code @GetMapping}s rather than
   * chosen.
   *
   * <p>The full list of page routes that need no path variable — detail views are reached from
   * seeded entities instead. Ordered as a reader walks the app (the member surface, then the
   * administration surface, then the legal pages) rather than alphabetically, so a missing
   * neighbour is visible to someone reading the list.
   */
  public static final @Unmodifiable List<String> PAGES =
      List.of(
          "/",
          "/missions",
          "/missions/new",
          "/operations",
          "/orders",
          "/orders/create",
          "/orders/material-demand",
          "/refinery-orders",
          "/refinery-orders/create",
          "/inventory",
          "/inventory/all",
          "/inventory/my",
          "/inventory/input",
          "/bank",
          "/bank/requests",
          "/bank/grants",
          "/bank/manage",
          "/org-unit-bank",
          "/materialboerse",
          "/materials",
          "/materials/overview",
          "/materials/profit-calculation",
          "/hangar",
          "/hangar/squadron",
          "/ship-data",
          "/blueprint-overview",
          "/personal-inventory",
          "/personal-inventory/blueprints",
          "/notifications",
          "/org-chart",
          "/profile",
          "/promotion/overview",
          "/promotion/my-evaluations",
          "/promotion/manage",
          "/promotion/admin/topics",
          "/promotion/admin/rank-requirements",
          "/admin/audit-log",
          // The two data-protection admin pages (REQ-SEC-060, REQ-SEC-061). Both render the
          // app shell and both carry the 44px touch floor, so both belong in the sweeps.
          "/admin/deletion-requests",
          "/admin/person-search",
          "/admin/bank",
          "/admin/bank-audit",
          "/admin/sync-reports",
          "/admin/sync-reports/uex",
          "/admin/sync-reports/scwiki",
          "/admin/terms",
          "/members",
          "/admin/settings",
          "/admin/locations",
          "/admin/blueprints",
          "/admin/default-blueprints",
          "/admin/discord-registrations",
          "/admin/material-aliases",
          "/admin/materials",
          "/admin/mission-data",
          "/admin/notification-rules",
          "/admin/org-structure",
          "/admin/announcement",
          "/admin/personal-inventory",
          "/admin/personal-blueprints",
          "/admin/special-commands",
          "/admin/uex-data",
          "/organisation/leitung",
          // Measured with an APPROVED session, which this route redirects away from — so the
          // REDIRECT CHECK in the touch sweep's `measure()` skips it and says so. Corrected
          // 2026-09-13: this entry used to claim the route "cancels out of the coverage
          // comparison", and it did not. `PendingApprovalPageController` answers `redirect:/`, the
          // browser landed on the dashboard, the shell check passed, and the route was counted as
          // MEASURED while every device class measured the dashboard twice.
          //
          // It is listed anyway, and the escape hatch is real rather than decorative now: the day
          // it stops redirecting, it is a page like any other and gets measured. It is also one of
          // the two non-error templates with no `fragments/sidebar`, so it has no `.krt-footer` and
          // could not satisfy the phone-class footer contract even if it did render.
          "/pending-approval",
          // The PAGE. Its JSON polling endpoint `/admin/p4k-import/jobs` is in NOT_PAGES, where the
          // first sweep put it by reporting a route with no footer at all.
          "/admin/p4k-import",
          "/impressum",
          // The third-party licence notice (REQ-UI-021): a long public page with the app shell.
          "/licenses",
          // The Android App Link's fallback page (REQ-SEC-038), reached when the link did not
          // resolve to the app. A real page with the app shell, and worth sweeping: the member who
          // sees it is on a phone mid-login, which is exactly the class the touch sweep measures.
          "/app/link-help",
          "/privacy",
          // The terms gate itself. Like `/pending-approval` it is measured with a session that has
          // already accepted, so it redirects and skips — and, like it, it is a page again on the
          // day it stops. It was in NONE of the three lists before they were folded into this one;
          // the gate below found it on its first run.
          "/terms/accept",
          "/terms");

  /**
   * Routes that are accounted for and are <b>not</b> pages: HTML fragments, JSON read models, the
   * notification SSE stream, and one redirect.
   *
   * <p>They are enumerated rather than matched by a predicate so that {@link #PAGES} and this list
   * together have to cover everything the dispatcher routes. That totality is the gate: a new
   * endpoint lands in one list or the other by a decision somebody made, and a new <i>page</i>
   * cannot be forgotten, because forgetting it fails the build instead of going quietly unmeasured.
   *
   * <p>Nothing here is skipped for being uninteresting. A predicate over {@code @ResponseBody} or
   * the return type would classify most of these correctly today and would also, silently,
   * reclassify a page on the day its handler changed shape.
   */
  public static final @Unmodifiable List<String> NOT_PAGES =
      List.of(
          // A redirect, and deliberately nothing else. `/app/callback` is the Android App Link
          // (REQ-SEC-038); the browser only reaches it when the link did not resolve to the app,
          // and it answers 303 to `/app/link-help` so the OAuth authorization code in the query
          // leaves the address bar instead of being rendered into a page. Sweeping it as a page
          // would measure the page it redirects to, twice, and say nothing about this route.
          "/app/callback",
          // HTML fragments: a view name, rendered without the app shell, swapped into a page by
          // `krtFetch`. Indistinguishable from a page by return type, which is why neither list is
          // derived from one.
          // The member's erasure-request card, swapped into /profile after a raise or a withdrawal
          // (REQ-SEC-061). A fragment, not a page: the same route also answers POST and DELETE,
          // which the sweeps would not exercise anyway.
          "/profile/deletion-request",
          "/inventory/all/stack/entries",
          "/inventory/all/game-item-stack/entries",
          "/inventory/my/stack/entries",
          "/inventory/my/game-item-stack/entries",
          // JSON read models behind a page: combobox searches, poll endpoints, grid data.
          "/admin/default-blueprints/search",
          "/admin/p4k-import/jobs",
          "/blueprint-overview/owners",
          "/catalog/location-search",
          "/catalog/material-search",
          "/inventory/item-search",
          "/inventory/my/entry-ids",
          "/inventory/order-needs",
          "/materialboerse/offerable-products",
          "/materialboerse/releasable-items",
          "/materialboerse/request-materials",
          "/materials/overview/data",
          "/members/api/search",
          "/notifications/page-items",
          "/notifications/recent",
          "/notifications/unread-count",
          "/orders/item-search",
          "/pending-approval/status",
          "/personal-inventory/blueprints/craftability",
          "/personal-inventory/blueprints/search",
          "/personal-inventory/uex-search",
          "/users/search",
          "/users/search-bank",
          // The live-sync channel. A navigation to it would never finish loading.
          "/notifications/stream");

  /**
   * Whole kinds of route this catalogue does not enumerate, as subtree roots.
   *
   * <p>Two groups, and neither is a judgement about an individual route. What the frontend owns but
   * no page sweep is about: the {@code /api/**} proxies, the two machine descriptors ({@code
   * assetlinks.json} and {@code manifest.webmanifest}), and {@code /csrf}. And what it does not own
   * at all: Spring's error page, the actuator tree, and the OAuth2 entry and exit, which the
   * dispatcher reports alongside the application's own mappings.
   *
   * <p>Matched with {@link EndpointEnumeration#isUnder}, segment by segment — never {@code
   * startsWith}, which would let {@code /error} swallow a future {@code /errors} and quietly remove
   * it from a gate whose whole value is that it covers everything.
   */
  public static final @Unmodifiable List<String> NOT_SWEPT_ROOTS =
      List.of(
          "/api",
          "/csrf",
          "/manifest.webmanifest",
          "/.well-known",
          "/error",
          "/actuator",
          "/oauth2",
          "/login",
          "/logout");

  /**
   * The member-facing slice {@code CorePagesSmokeE2eTest} loads.
   *
   * <p>A subset of {@link #PAGES}, and deliberately a small one: that class is tagged {@code smoke}
   * and is target-agnostic, so it may run against a shared staging deployment as a user who is not
   * an administrator. Every entry must therefore render for an ordinary member.
   */
  public static final @Unmodifiable List<String> CORE_SMOKE =
      List.of(
          "/",
          "/missions",
          "/orders",
          "/refinery-orders",
          "/hangar",
          "/operations",
          "/materials",
          "/materials/overview",
          "/materials/profit-calculation",
          "/ship-data",
          "/blueprint-overview",
          "/org-chart",
          "/notifications",
          "/personal-inventory",
          "/personal-inventory/blueprints");

  /**
   * The ADMIN-gated slice {@code AdminPagesSmokeE2eTest} loads.
   *
   * <p>A subset of {@link #PAGES}. It is not "every admin page": the ones already covered by a
   * dedicated flow ({@code /admin/settings}, {@code /admin/materials}, {@code
   * /admin/special-commands}, {@code /admin/default-blueprints}, {@code /admin/bank}, {@code
   * /admin/audit-log}, {@code /admin/mission-data}) are left out on purpose, because a page-load
   * smoke adds nothing to a flow that already drives the page.
   *
   * <p>That omission is a judgement about test value, not a fact about the routes, which is why
   * this list stays curated while {@link #PAGES} is gated. What the gate does give it is that every
   * entry must exist in {@link #PAGES} — so a typo, or a route renamed out from under it, fails the
   * build instead of quietly testing nothing.
   */
  public static final @Unmodifiable List<String> ADMIN_SMOKE =
      List.of(
          "/members",
          "/organisation/leitung",
          "/admin/locations",
          "/admin/material-aliases",
          "/admin/uex-data",
          "/admin/discord-registrations",
          "/admin/sync-reports",
          "/admin/p4k-import",
          "/admin/announcement",
          "/admin/notification-rules",
          "/admin/org-structure",
          "/admin/blueprints",
          "/admin/personal-inventory",
          "/admin/personal-blueprints");

  /**
   * The slice {@code AccessibilitySmokeE2eTest} runs the axe WCAG A+AA scan over.
   *
   * <p>A subset of {@link #PAGES}, and the smallest of the three slices on purpose: an axe scan
   * injects and runs the engine inside the page, so it costs far more per route than a page load.
   * Five representative surfaces — the dashboard, a list, two different queue/create shapes and the
   * hangar — rather than every page.
   *
   * <p>This was the <b>fourth</b> hand-kept copy of the frontend's routes, and it was not one of
   * the three the 2026-09-13 review named. It is folded in here for the same reason as the other
   * two slices: which pages are worth an axe scan stays a judgement, but every entry being a page
   * route that actually exists is checkable, and {@code PageRouteCatalogueTest} now checks it.
   */
  public static final @Unmodifiable List<String> A11Y_SMOKE =
      List.of("/", "/missions", "/orders", "/refinery-orders", "/hangar");

  /**
   * The list routes that own a {@code /{id}} detail view.
   *
   * <p>Used by the touch sweep only to say so when one of them renders no row: the detail view then
   * goes unmeasured, and that is worth printing rather than passing over in silence. It is NOT a
   * failure — an empty list is a legitimate state of a fresh or shared stack, and the {@code smoke}
   * tag exists so the sweep can run against one.
   */
  public static final @Unmodifiable Set<String> DETAIL_LIST_PAGES =
      Set.of("/missions", "/operations", "/orders", "/refinery-orders");
}
