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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * An open page offers a reload once an answer comes from another version than the page was rendered
 * with (REQ-FE-042): every answer names the running version, the page names its own, and an answer
 * from another version shows the notice, whose button reloads the page.
 */
@Tag("e2e")
class AppVersionNoticeE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The header naming the version that answered. */
  private static final String VERSION_HEADER = "x-basetool-version";

  /** The notice of a new version on the page's topmost layer. */
  private static final String NOTICE = "#krt-maintenance-banner[data-state='updated']";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** Shows the notice for an answer from another version, and its button reloads the page. */
  @Test
  void anAnswerFromAnotherVersionOffersAReload() {
    String baseUrl = STACK.baseUrl();
    Path auth = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions?showPast=true");
        page.waitForLoadState();
        String pageVersion =
            page.locator("meta[name='krt-app-version']").first().getAttribute("content");
        APIResponse probe = page.request().head(baseUrl + "/favicon.ico");
        assertEquals(pageVersion, probe.headers().get(VERSION_HEADER));
        assertThat(page.locator(NOTICE)).hasCount(0);

        page.route(
            url -> url.contains("search=E2E"),
            route -> fulfillFromAnotherVersion(route, pageVersion + "-next"));
        E2eSupport.openFilterPanel(page);
        page.locator("#mission-search").fill("E2E");

        Locator notice = page.locator(NOTICE);
        assertThat(notice).isVisible();
        page.unrouteAll();
        page.evaluate("() => { window.__krtBeforeReload = true; }");
        page.waitForRequest(
            request -> request.isNavigationRequest() && request.url().contains("/missions"),
            () -> notice.locator(".krt-maintenance-banner__reload").click());
        page.waitForLoadState();
        assertNull(page.evaluate("() => window.__krtBeforeReload ?? null"));
        assertFalse(page.locator(NOTICE).isVisible());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "app-version-notice");
        throw failure;
      }
    }
  }

  /**
   * Answers a request with the real response under another version header.
   *
   * @param route the intercepted request
   * @param version the version the answer names
   */
  private static void fulfillFromAnotherVersion(Route route, String version) {
    APIResponse real = route.fetch();
    Map<String, String> headers = new HashMap<>(real.headers());
    headers.put(VERSION_HEADER, version);
    route.fulfill(new Route.FulfillOptions().setResponse(real).setHeaders(headers));
  }
}
