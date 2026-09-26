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

package de.greluc.krt.profit.basetool.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.web.EndpointEnumeration;
import de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that every variable-free {@code GET} route the dispatcher answers is listed in {@link
 * FrontendPageRoutes}, naming any that is missing.
 *
 * <p>Uses {@link EndpointEnumeration}. It checks only that a route is listed, not which list it
 * belongs in.
 */
@SpringBootTest
class PageRouteCatalogueTest {

  /**
   * The minimum number of routes the enumeration must find for the test's verdict to count,
   * guarding against an enumeration that silently finds nothing.
   */
  private static final int MIN_ROUTED_CANDIDATES = 50;

  @Autowired private WebApplicationContext context;

  /**
   * Mocked so the context starts without a backend; nothing here issues a request.
   *
   * <p>This class only reads the handler mapping, so no call can reach this bean. It is stubbed for
   * the same reason the anonymous sweep stubs it: a real client would try to connect while the
   * context came up.
   */
  @MockitoBean private de.greluc.krt.profit.basetool.frontend.service.BackendApiClient client;

  /** The frontend is an OAuth2 client; the registry is what the security chain wires through. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /**
   * Every variable-free {@code GET} route the frontend owns, as the dispatcher reports it, minus
   * {@link FrontendPageRoutes#NOT_SWEPT_ROOTS} matched segment-wise via {@link
   * EndpointEnumeration#isUnder}.
   *
   * @return the routed paths, in lexicographic order
   */
  private Set<String> routedPageCandidates() {
    return EndpointEnumeration.patterns(context, HttpMethod.GET).stream()
        .filter(pattern -> !pattern.contains("{") && !pattern.contains("*"))
        .filter(
            pattern ->
                FrontendPageRoutes.NOT_SWEPT_ROOTS.stream()
                    .noneMatch(root -> EndpointEnumeration.isUnder(pattern, root)))
        .collect(TreeSet::new, Set::add, Set::addAll);
  }

  /** The union the catalogue claims to cover. */
  private static Set<String> catalogued() {
    Set<String> all = new TreeSet<>(FrontendPageRoutes.PAGES);
    all.addAll(FrontendPageRoutes.NOT_PAGES);
    return all;
  }

  /**
   * The enumeration found an application, so the checks below are comparing something.
   *
   * <p>First because everything after it is a set difference, and every set difference against an
   * empty enumeration passes.
   */
  @Test
  @DisplayName("the enumeration actually found the frontend's routes")
  void theEnumerationIsNotEmpty() {
    assertThat(routedPageCandidates())
        .withFailMessage(
            """
            The dispatcher reported %d variable-free GET routes, fewer than the floor of %d.

            Nothing below this can fail when the enumeration is empty - every check is a set \
            difference, and an empty set differs from the catalogue by nothing. Look at \
            EndpointEnumeration.patterns and at NOT_SWEPT_ROOTS before believing any verdict from \
            this class.\
            """,
            routedPageCandidates().size(), MIN_ROUTED_CANDIDATES)
        .hasSizeGreaterThanOrEqualTo(MIN_ROUTED_CANDIDATES);
  }

  /**
   * The gate: a route the frontend answers and nobody wrote down fails the build, here, naming
   * itself.
   */
  @Test
  @DisplayName("every routed GET page candidate is in PAGES or NOT_PAGES")
  void everyRoutedRouteIsAccountedFor() {
    Set<String> missing = new TreeSet<>(routedPageCandidates());
    missing.removeAll(catalogued());

    assertThat(missing)
        .withFailMessage(
            """
            %d route(s) are answered by the frontend and named in no list.

              %s

            Add each one to FrontendPageRoutes: to PAGES if it renders a page (the touch sweep and \
            the smoke suites will then walk it), or to NOT_PAGES if it is an HTML fragment, a JSON \
            read model or a stream. Do not decide by return type - a fragment and a page are both \
            a view name; open the route and look for the app shell.\
            """,
            missing.size(), String.join("\n  ", missing))
        .isEmpty();
  }

  /**
   * The reverse drift: an entry that outlived the route it named.
   *
   * <p>A renamed or deleted route leaves a catalogue entry pointing at nothing, and the sweeps that
   * walk it would load a 404 and — because a 404 page has no app shell — skip it as "not a page".
   * The coverage would quietly shrink by one and every assertion would still pass.
   */
  @Test
  @DisplayName("every catalogued route is still answered by the dispatcher")
  void everyCataloguedRouteStillExists() {
    Set<String> stale = new TreeSet<>(catalogued());
    stale.removeAll(routedPageCandidates());

    assertThat(stale)
        .withFailMessage(
            """
            %d catalogued route(s) are no longer answered by the frontend.

              %s

            They were renamed or removed. Update FrontendPageRoutes - an entry that names nothing \
            loads a 404, which has no app shell, so the sweeps skip it and report clean.\
            """,
            stale.size(), String.join("\n  ", stale))
        .isEmpty();
  }

  /** A route cannot be both a page and not a page; the two lists partition, they do not overlap. */
  @Test
  @DisplayName("PAGES and NOT_PAGES do not overlap")
  void thePagesAndNotPagesListsAreDisjoint() {
    Set<String> both = new TreeSet<>(FrontendPageRoutes.PAGES);
    both.retainAll(Set.copyOf(FrontendPageRoutes.NOT_PAGES));

    assertThat(both)
        .withFailMessage("route(s) listed as both a page and not a page: %s", both)
        .isEmpty();
  }

  /** Verifies that every entry of the two smoke slices is a real page route. */
  @Test
  @DisplayName("the smoke slices and the detail-list set are subsets of PAGES")
  void theSlicesAreSubsetsOfThePages() {
    Set<String> pages = Set.copyOf(FrontendPageRoutes.PAGES);

    assertThat(FrontendPageRoutes.CORE_SMOKE)
        .as("CorePagesSmokeE2eTest slice")
        .allSatisfy(path -> assertThat(pages).contains(path));
    assertThat(FrontendPageRoutes.ADMIN_SMOKE)
        .as("AdminPagesSmokeE2eTest slice")
        .allSatisfy(path -> assertThat(pages).contains(path));
    assertThat(FrontendPageRoutes.A11Y_SMOKE)
        .as("AccessibilitySmokeE2eTest slice")
        .allSatisfy(path -> assertThat(pages).contains(path));
    assertThat(FrontendPageRoutes.DETAIL_LIST_PAGES)
        .as("list routes owning a detail view")
        .allSatisfy(path -> assertThat(pages).contains(path));
  }

  /**
   * Verifies that every {@link FrontendPageRoutes#DETAIL_PREFIX_OVERRIDES} entry maps a real list
   * page to a routed {@code /{id}} detail view.
   */
  @Test
  @DisplayName("every detail-prefix override maps a list page to a routed /{id} detail view")
  void everyDetailPrefixOverrideIsRouted() {
    Set<String> pages = Set.copyOf(FrontendPageRoutes.PAGES);
    Set<String> routed = Set.copyOf(EndpointEnumeration.patterns(context, HttpMethod.GET));

    assertThat(FrontendPageRoutes.DETAIL_PREFIX_OVERRIDES.keySet())
        .as("list pages with a detail-prefix override")
        .allSatisfy(path -> assertThat(pages).contains(path));
    assertThat(FrontendPageRoutes.DETAIL_PREFIX_OVERRIDES.values())
        .as("detail prefixes named by an override")
        .allSatisfy(prefix -> assertThat(routed).contains(prefix + "/{id}"));
  }

  /** Verifies that no catalogue list contains a duplicate route. */
  @Test
  @DisplayName("no catalogue list contains a duplicate")
  void noListRepeatsARoute() {
    assertNoDuplicates("PAGES", FrontendPageRoutes.PAGES);
    assertNoDuplicates("NOT_PAGES", FrontendPageRoutes.NOT_PAGES);
    assertNoDuplicates("NOT_SWEPT_ROOTS", FrontendPageRoutes.NOT_SWEPT_ROOTS);
    assertNoDuplicates("CORE_SMOKE", FrontendPageRoutes.CORE_SMOKE);
    assertNoDuplicates("ADMIN_SMOKE", FrontendPageRoutes.ADMIN_SMOKE);
    assertNoDuplicates("A11Y_SMOKE", FrontendPageRoutes.A11Y_SMOKE);
  }

  /**
   * Fails naming the repeated entries of one list.
   *
   * @param name the list's field name, for the message
   * @param routes the list to check
   */
  private static void assertNoDuplicates(String name, List<String> routes) {
    Set<String> seen = new HashSet<>();
    Set<String> repeated = new TreeSet<>();
    routes.stream().filter(route -> !seen.add(route)).forEach(repeated::add);

    assertThat(repeated).withFailMessage("%s lists %s more than once", name, repeated).isEmpty();
  }
}
