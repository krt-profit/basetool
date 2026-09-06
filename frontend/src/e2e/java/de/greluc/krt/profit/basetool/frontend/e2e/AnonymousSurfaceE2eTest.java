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

package de.greluc.krt.profit.basetool.frontend.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * REQ-SEC-052 in a real browser: <b>nobody without a login reads anything.</b>
 *
 * <p>The in-process sweeps ({@code AnonymousSurfaceSweepTest}, {@code
 * AnonymousSurfaceSweepMvcTest}) enumerate every mapping and are the exhaustive half of this
 * guarantee. They run against MockMvc, which is exactly what they cannot cover: a real browser
 * follows the OAuth2 redirect, runs the silent-SSO probe, keeps a cookie jar, and replays a deep
 * link after signing in. Each of those is a place the members-only cut-over could be undone without
 * a single unit test noticing.
 *
 * <p>Four things are pinned here, in the order a visitor meets them:
 *
 * <ol>
 *   <li>the former public pages send an anonymous visitor into the login and never render;
 *   <li>the landing page carries the two login entries and no data;
 *   <li>a background call answers {@code 401 REAUTH_REQUIRED} rather than the payload;
 *   <li>a deep link survives the login — the WP-F 11 request cache, without which a member
 *       following a Discord link lands on the dashboard and has to navigate again.
 * </ol>
 */
@Tag("e2e")
class AnonymousSurfaceE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  /** What a background call must get instead of the payload (REQ-SEC-012). */
  private static final int UNAUTHORIZED = 401;

  /**
   * The pages that answered anonymously until ADR-0159.
   *
   * <p>{@code /orders} and {@code /operations} were whole families; {@code /missions} carried the
   * seven-day grid and the roster. They are listed one by one rather than swept — the sweep is the
   * MockMvc test's job — because what is asserted here is the browser's experience of each.
   */
  private static final List<String> FORMER_PUBLIC_PAGES =
      List.of("/missions", "/operations", "/orders", "/orders/create");

  /**
   * Pages that were <b>never</b> public, swept in the same shape.
   *
   * <p>The rule is not "the pages the members-only change closed" but "every page", and a gate that
   * only holds where somebody remembered to look is the failure mode this whole PR is about. These
   * two also carry the coverage of the cases {@code AnonymousSurfaceE2eTest} replaces: {@code
   * InventoryTenancyE2eTest.guestIsRedirectedToLoginFromLager} asserted exactly this shape for the
   * Lager.
   */
  private static final List<String> NEVER_PUBLIC_PAGES = List.of("/inventory/all", "/hangar");

  private static Playwright playwright;
  private static Browser browser;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
  }

  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Every former public page sends a visitor with no session into the login, and renders none of
   * its own markup on the way.
   *
   * <p>Asserted as "not this page, and none of its content" rather than as a status: the silent-SSO
   * probe ({@code prompt=none} → {@code login_required}) means the browser can end up back on the
   * landing page at {@code /?error} rather than on a Keycloak form, and which of the two it is
   * depends on the SSO session, not on this rule. What must hold either way is that the protected
   * markup never appears — which is also why the "no form" half only applies while the browser is
   * still on this host: being handed a login form on Keycloak's is the rule working.
   */
  @Test
  void everyFormerPublicPageSendsAnAnonymousVisitorAway() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        for (String path : concat(FORMER_PUBLIC_PAGES, NEVER_PUBLIC_PAGES)) {
          E2eSupport.navigate(page, baseUrl + path);

          assertNotEquals(
              baseUrl + path,
              page.url(),
              path + " must not render for an anonymous visitor (REQ-SEC-052)");
          assertEquals(
              0,
              page.locator("table tbody tr").count(),
              "no row of " + path + " may reach an anonymous visitor");
          // Scoped to our own origin on purpose: the page a refused visitor is sent to IS a
          // login form, and it is Keycloak's, served from Keycloak's host. The landing page —
          // the other place the visitor can end up — carries no form at all, so the check
          // still bites wherever it can mean anything.
          if (page.url().startsWith(baseUrl)) {
            assertEquals(
                0,
                page.locator("form").count(),
                "and no form of it either — /orders/create was the public request form"
                    + " (ADR-0149)");
          }
          assertTrue(
              page.url().contains("/oauth2/authorization/keycloak")
                  || page.url().startsWith(baseUrl + "/?")
                  || page.url().equals(baseUrl + "/")
                  || !page.url().startsWith(baseUrl),
              "expected the login flow or the landing page, got " + page.url());
        }
      } finally {
        page.close();
      }
    }
  }

  /**
   * Joins two path lists, so the sweep above reads as one loop over "every page".
   *
   * @param first the former public pages
   * @param second the pages that were never public
   * @return every path to sweep, in order
   */
  private static List<String> concat(List<String> first, List<String> second) {
    return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
  }

  /**
   * The landing page is the whole of what an anonymous visitor gets: two login entries, the legal
   * links, and no data.
   *
   * <p>The seven-day mission grid used to render here for anyone who asked — unit, status, meeting
   * point and times. That it is gone is the visible half of decision D7, and a template edit could
   * put it back without failing anything else.
   */
  @Test
  void theLandingPageOffersTheLoginAndNoData() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/");

        assertEquals(baseUrl + "/", page.url(), "the landing page itself needs no login");
        assertEquals(
            1,
            page.locator("[data-testid='landing-login']").count(),
            "the Keycloak login entry must be offered");
        assertEquals(
            1,
            page.locator("[data-testid='landing-login-discord']").count(),
            "and the Discord one beside it");
        assertEquals(
            0,
            page.locator("table").count(),
            "no table may render on the landing page — the mission grid was one");
      } finally {
        page.close();
      }
    }
  }

  /** The legal pages stay reachable without a login; consent cannot be gated behind consent. */
  @Test
  void theLegalPagesStayPublic() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        for (String path : List.of("/terms", "/privacy", "/impressum")) {
          E2eSupport.navigate(page, baseUrl + path);
          assertEquals(baseUrl + path, page.url(), path + " must render without a login");
        }
      } finally {
        page.close();
      }
    }
  }

  /**
   * A background call answers {@code 401 REAUTH_REQUIRED} — never the payload, and never a redirect
   * a fetch cannot follow usefully.
   *
   * <p>{@code /catalog/**} was one of the widest anonymous reads the frontend served, and it is the
   * shape {@code krtFetch} uses, so this is the exact request a logged-out tab keeps making. The
   * distinction from the navigation case above is REQ-SEC-012's whole point: a page must be able to
   * re-authenticate in place instead of having a half-filled form replaced by a login screen.
   */
  @Test
  void aBackgroundCatalogueCallIsRefusedWithReauthRequired() {
    APIRequestContext api =
        playwright
            .request()
            .newContext(
                new com.microsoft.playwright.APIRequest.NewContextOptions()
                    .setBaseURL(STACK.baseUrl())
                    .setIgnoreHTTPSErrors(true));
    try {
      APIResponse response =
          api.get(
              "/catalog/material-search?q=x",
              RequestOptions.create()
                  .setHeader("Accept", "application/json")
                  .setHeader("Sec-Fetch-Mode", "cors"));

      assertEquals(
          UNAUTHORIZED,
          response.status(),
          "a background catalogue read must be refused, not answered: " + response.text());
      assertTrue(
          response.headers().containsKey("x-reauthenticate"),
          "the refusal must carry X-Reauthenticate so krtFetch can re-authenticate in place");
    } finally {
      api.dispose();
    }
  }

  /**
   * A deep link survives the login: after signing in the member lands on the page they asked for,
   * not on the dashboard.
   *
   * <p>This is WP-F 11's request cache seen from the outside, and it is the half of that change
   * that would fail silently — the matcher (save only real navigations) and the "one shared cache"
   * wiring are both invisible until somebody follows a Discord link and ends up somewhere else.
   * {@code /hangar} is deliberately a page that was <em>never</em> public: the replay must work for
   * every authenticated page, not only for the ones the members-only cut-over closed.
   *
   * <p>The consent gate is passed once up front, in a throwaway context, because it consumes the
   * saved request on a member's very first login — which would make this assertion depend on
   * whichever test happened to log this user in first.
   */
  @Test
  void aDeepLinkIsReplayedAfterTheLogin() {
    assumeTrue(STACK.managesStack(), "needs the seeded test-member of the ephemeral stack");
    String baseUrl = STACK.baseUrl();

    // Consent, once, so the gate below is a no-op and cannot eat the saved request.
    E2eSupport.authenticatedStorageState(browser, baseUrl, MEMBER_USER, MEMBER_PASSWORD);

    for (Map.Entry<String, String> deepLink :
        Map.of("/hangar", "a page that was never public", "/missions", "a former public page")
            .entrySet()) {
      try (BrowserContext context =
          browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
        Page page = context.newPage();
        try {
          E2eSupport.navigate(page, baseUrl + deepLink.getKey());
          E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);

          // Spring Security replays the saved request with its own `continue` marker appended:
          // the request cache only hands the saved request back to a URL carrying it, which is
          // how it tells a replay apart from a fresh navigation to the same path. That marker is
          // its bookkeeping, not part of the deep link the member asked for.
          String url = page.url();
          int query = url.indexOf('?');
          String replayed = query < 0 ? url : url.substring(0, query);
          assertEquals(
              baseUrl + deepLink.getKey(),
              replayed,
              "the deep link must be replayed after the login (" + deepLink.getValue() + ")");
        } finally {
          page.close();
        }
      }
    }
  }
}
