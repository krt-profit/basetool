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
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The frontend's page routes in one catalogue, shared by the E2E page sweeps and the {@code
 * PageRouteCatalogueTest} gate.
 *
 * <p>The gate fails when a dispatcher mapping is in neither {@link #PAGES} nor {@link #NOT_PAGES},
 * using {@link EndpointEnumeration}. Whether a listed route actually renders an app shell is
 * decided at sweep time, not here.
 */
public final class FrontendPageRoutes {

  /** Not instantiable: this is a catalogue, not a collaborator. */
  private FrontendPageRoutes() {
    throw new AssertionError("no instances");
  }

  /**
   * Every route without a path variable that the frontend answers with a page, ordered as a reader
   * walks the app: member surface, administration, legal pages.
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
          "/sc-links",
          "/profile",
          "/promotion/overview",
          "/promotion/my-evaluations",
          "/promotion/manage",
          "/promotion/admin/topics",
          "/promotion/admin/rank-requirements",
          "/admin/audit-log",
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
          "/pending-approval",
          "/admin/p4k-import",
          "/impressum",
          "/licenses",
          "/app/link-help",
          "/privacy",
          "/terms/accept",
          "/terms");

  /**
   * Routes that are accounted for but are not pages: HTML fragments, JSON read models, the
   * notification SSE stream and one redirect.
   *
   * <p>Enumerated explicitly, so that together with {@link #PAGES} it must cover every routed
   * endpoint.
   */
  public static final @Unmodifiable List<String> NOT_PAGES =
      List.of(
          "/app/callback",
          "/profile/deletion-request",
          "/inventory/all/stack/entries",
          "/inventory/all/game-item-stack/entries",
          "/inventory/my/stack/entries",
          "/inventory/my/game-item-stack/entries",
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
          "/notifications/stream");

  /**
   * Subtree roots the catalogue does not enumerate: the {@code /api/**} proxies, {@code
   * assetlinks.json}, {@code manifest.webmanifest}, {@code /csrf}, the error page, the actuator
   * tree and the OAuth2 entry and exit.
   *
   * <p>Matched segment by segment with {@link EndpointEnumeration#isUnder}, never by {@code
   * startsWith}.
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
          "/sc-links",
          "/notifications",
          "/personal-inventory",
          "/personal-inventory/blueprints");

  /**
   * The ADMIN-gated routes {@code AdminPagesSmokeE2eTest} loads; a curated subset of {@link #PAGES}
   * that omits admin pages already driven by a dedicated flow. Every entry must exist in {@link
   * #PAGES}.
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
   * The routes {@code AccessibilitySmokeE2eTest} scans with axe for WCAG A+AA; a small subset of
   * {@link #PAGES}, because a scan is expensive per route.
   */
  public static final @Unmodifiable List<String> A11Y_SMOKE =
      List.of("/", "/missions", "/orders", "/refinery-orders", "/hangar");

  /**
   * The list routes that own a {@code /{id}} detail view; the touch sweep reports, without failing,
   * when one of them renders no row.
   */
  public static final @Unmodifiable Set<String> DETAIL_LIST_PAGES =
      Set.of("/missions", "/operations", "/orders", "/refinery-orders");

  /**
   * List routes whose detail links leave the list's own path, mapped to the prefix their detail
   * view lives under; the touch sweep otherwise assumes the list's own path.
   *
   * <p>Every key must be in {@link #PAGES} and every value must own a routed {@code /{id}} pattern.
   */
  public static final @Unmodifiable Map<String, String> DETAIL_PREFIX_OVERRIDES =
      Map.of("/admin/special-commands", "/organisation/special-commands");

  /**
   * The path prefix under which a list page's detail links are expected.
   *
   * @param listPath the app-relative path of the list page
   * @return the override from {@link #DETAIL_PREFIX_OVERRIDES} when the list links out of its own
   *     path, otherwise {@code listPath} itself
   */
  public static @NotNull String detailPrefixOf(@NotNull String listPath) {
    return DETAIL_PREFIX_OVERRIDES.getOrDefault(listPath, listPath);
  }
}
