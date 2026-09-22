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
 * Keeps {@link FrontendPageRoutes} honest: <b>every route the dispatcher answers is accounted for,
 * or this fails and names it.</b>
 *
 * <p><b>The drift this replaces was real and measured.</b> Until 2026-09-13 three E2E classes each
 * hand-maintained their own list of the frontend's page routes, and the largest of them was short
 * by seventeen while its Javadoc and {@code REQ-UI-009}'s "Enforced by" clause both said every
 * route was covered. Adding the seventeen found a genuine defect on the first run ({@code
 * /admin/notification-rules} scrolls the page sideways at every device class) and showed a phone
 * fix that had shipped unmeasured because the page carrying it was never loaded. Folding the three
 * lists into one removes two of the three ways they could disagree; this class removes the third,
 * which is the only one that was ever going to matter — a page route that exists and is in no list
 * at all.
 *
 * <p><b>Why a gate and not a convention.</b> "Add the route to the list" is a step somebody has to
 * remember, and the evidence is that it was missed seventeen times without anyone noticing. Asking
 * the dispatcher turns it into a step nobody can skip: the build goes red on the pull request that
 * adds the controller, naming the route and the two lists it could go in. That is the same
 * substitution {@code AnonymousSurfaceSweepMvcTest} makes for REQ-SEC-052, and it runs on the same
 * engine — {@link EndpointEnumeration}.
 *
 * <p><b>Why it does not decide which list a route belongs in.</b> A page is recognised by its app
 * shell at runtime, not by a return type: {@code /inventory/my/stack/entries} is an HTML fragment
 * and {@code /inventory/my} is a page, and both are a {@code @GetMapping} returning a view name
 * from a {@code @Controller}. Any predicate that sorted them would also, silently, reclassify a
 * page on the day its handler changed shape — and the touch sweep's whole method is to keep
 * non-pages listed and let the run classify them, because that is how a page that quietly
 * <i>stops</i> rendering its shell surfaces. So the gate asks only the question it can answer
 * without judgement: is this route written down somewhere? Which of the two lists it goes in stays
 * a decision a person makes, in a diff a reviewer reads.
 *
 * <p><b>Scope.</b> Variable-free {@code GET} patterns only. A route needing a {@code /{id}} is
 * reached from a seeded entity by the sweeps that care, never from a list of paths, and a wildcard
 * pattern has no single spelling to load.
 */
@SpringBootTest
class PageRouteCatalogueTest {

  /**
   * How many routes the enumeration must find before this class's verdict counts for anything.
   *
   * <p>A floor, not a target. {@link EndpointEnumeration}'s own Javadoc records the failure mode it
   * exists for: <i>a sweep that enumerates nothing passes every assertion it makes</i>. Every check
   * below is a set difference, and two empty sets differ by nothing — so a dispatcher bean that
   * came back empty, or a filter that over-reached, would report this catalogue perfectly in step
   * with an application it never looked at.
   *
   * <p>Set well under the ~95 routed today, because its job is to catch a collapse rather than to
   * count: a deliberate removal of a third of the frontend's pages should not be made to fail here,
   * in a test whose subject is drift.
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
   * Every variable-free {@code GET} route the frontend owns, as the dispatcher reports it.
   *
   * <p>Minus {@link FrontendPageRoutes#NOT_SWEPT_ROOTS} — the proxies, the machine descriptors and
   * the routes Spring contributes — matched segment-wise with {@link EndpointEnumeration#isUnder}
   * rather than by prefix, so an exclusion cannot swallow a neighbour it was never meant to cover.
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

  /**
   * The two smoke slices name pages that exist.
   *
   * <p>Neither slice is derived — which pages an ordinary staging member can load, and which admin
   * pages a dedicated flow already drives, are judgements about test value rather than facts about
   * a mapping. What is checkable is that each entry is a real page route, so a typo or a rename
   * cannot leave a smoke suite quietly loading nothing.
   */
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
   * Every detail-prefix override points from a real list page to a real detail route.
   *
   * <p>The touch sweep follows a list's first {@code <prefix>/<id>} link to measure its detail
   * view, and {@link FrontendPageRoutes#DETAIL_PREFIX_OVERRIDES} names the lists whose detail links
   * leave their own path. An override whose key is no page is never consulted, and one whose value
   * no longer owns a {@code /{id}} route matches no link — either way the detail view drops out of
   * the sweep while every assertion still passes. This pins both ends to the dispatcher.
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

  /**
   * No list repeats itself.
   *
   * <p>{@code List.of} rejects a null but not a duplicate, and a route listed twice is swept twice
   * — which costs a touch-sweep screenshot pass per device class and reads in the log as if the
   * coverage were wider than it is.
   */
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
