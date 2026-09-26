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
 * REQ-SEC-052 in a real browser: nobody without a login reads anything.
 *
 * <ol>
 *   <li>protected pages send an anonymous visitor into the login and never render;
 *   <li>the landing page carries the two login entries and no data;
 *   <li>a background call answers {@code 401 REAUTH_REQUIRED} rather than the payload;
 *   <li>a deep link is replayed after the login.
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

  /** The pages ADR-0159 closed to anonymous visitors. */
  private static final List<String> FORMER_PUBLIC_PAGES =
      List.of("/missions", "/operations", "/orders", "/orders/create");

  /** Pages that were never public, swept the same way. */
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
   * Every protected page sends a visitor with no session into the login and renders none of its own
   * markup on the way.
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
   * Joins two path lists into the list of every page to sweep.
   *
   * @param first the former public pages
   * @param second the pages that were never public
   * @return every path to sweep, in order
   */
  private static List<String> concat(List<String> first, List<String> second) {
    return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
  }

  /**
   * The landing page offers an anonymous visitor two login entries and the legal links, and no data
   * such as the mission grid.
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
        for (String path : List.of("/terms", "/privacy", "/impressum", "/licenses")) {
          E2eSupport.navigate(page, baseUrl + path);
          assertEquals(baseUrl + path, page.url(), path + " must render without a login");
        }
      } finally {
        page.close();
      }
    }
  }

  /**
   * A background {@code /catalog/**} call answers {@code 401 REAUTH_REQUIRED}, never the payload or
   * a redirect (REQ-SEC-012).
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
   * A deep link to {@code /hangar} survives the login: the member lands on the requested page, not
   * the dashboard. The consent gate is passed first in a throwaway context.
   */
  @Test
  void aDeepLinkIsReplayedAfterTheLogin() {
    assumeTrue(STACK.managesStack(), "needs the seeded test-member of the ephemeral stack");
    String baseUrl = STACK.baseUrl();

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
