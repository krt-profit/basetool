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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verifies that a refused blueprint batch keeps the staged selection available for retry. */
@Tag("e2e")
class PersonalBlueprintBatchFailureE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser against the isolated test stack. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().getUserId(USERNAME, PASSWORD);
    }
  }

  /** Releases the browser and driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** Keeps the add dialog, selected chip and retry control after an HTTP 503 refusal. */
  @Test
  void failedBatchKeepsStagedSelectionAndDialogOpen() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      page.route(
          "**/personal-inventory/blueprints/search?**",
          route ->
              route.fulfill(
                  new Route.FulfillOptions()
                      .setStatus(200)
                      .setContentType("application/json")
                      .setBody(
                          """
                          [{"productKey":"batch-fixture","name":"Batch fixture",
                            "variantCount":1,"ownedByCurrentUser":false}]
                          """)));
      page.route(
          "**/personal-inventory/blueprints/add-selected",
          route ->
              route.fulfill(
                  new Route.FulfillOptions()
                      .setStatus(503)
                      .setContentType("application/problem+json")
                      .setBody("{\"status\":503,\"code\":\"SERVICE_UNAVAILABLE\"}")));
      try {
        E2eSupport.navigate(page, baseUrl + "/personal-inventory/blueprints");
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.locator("#krt-bp-add-open").click();
        page.locator("#krt-bp-search-input").fill("Batch fixture");
        page.locator("#krt-bp-search-results .krt-bp-result").click();

        Locator selected = page.locator("#krt-bp-staging-list .chip[data-key='batch-fixture']");
        Locator submit = page.locator("#krt-bp-add-selected");
        assertThat(selected).isVisible();
        Response refused =
            page.waitForResponse(
                response -> response.url().endsWith("/blueprints/add-selected"), submit::click);

        assertEquals(503, refused.status());
        assertEquals("[\"batch-fixture\"]", refused.request().postData());
        assertThat(page.locator("#krt-bp-add-modal")).isVisible();
        assertThat(selected).isVisible();
        assertThat(submit).isEnabled();
        assertThat(page.locator(".notification-toast.error-toast")).isVisible();
        assertThat(page.locator(".notification-toast:not(.error-toast)")).hasCount(0);
        assertEquals(Boolean.TRUE, page.evaluate("() => window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "blueprint-batch-failure");
        throw failure;
      }
    }
  }
}
