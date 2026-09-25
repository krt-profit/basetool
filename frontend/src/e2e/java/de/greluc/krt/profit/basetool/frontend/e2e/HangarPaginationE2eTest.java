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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies server-side pagination and search of the personal hangar (REQ-HANGAR-002): a page click
 * and a search both re-render the table in place. Assertions are structural, so other ships in the
 * stack do not affect them.
 */
@Tag("e2e")
class HangarPaginationE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Number of ships to seed — comfortably more than one page at the smallest size (10). */
  private static final int SEEDED_SHIPS = 12;

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the membership + a multi-page fleet.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String shipTypeId = seeder.findShipTypeIdByName(USERNAME, PASSWORD, "E2E Ship Type");
      for (int i = 1; i <= SEEDED_SHIPS; i++) {
        seeder.seedShip(USERNAME, PASSWORD, String.format("Pagi-Ship-%02d", i), shipTypeId, "LTI");
      }
    }
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

  /**
   * Loads the hangar at size 10, asserts the page caps at 10 rows and renders the pagination nav +
   * size picker, then pages forward in place and finally filters with a no-match search — all
   * without a full reload (REQ-FE-001 / REQ-HANGAR-002).
   */
  @Test
  void paginatesAndFiltersThePersonalHangarInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar?size=10");

        assertEquals(10, page.getByTestId("hangar-ship-row").count(), "page must cap at size 10");
        assertThat(page.locator(".pagination").first()).isVisible();
        assertThat(page.locator(".page-size-picker")).isVisible();
        assertThat(page.locator("#hangar-ship-filter")).isVisible();

        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            r -> r.url().contains("/hangar") && r.url().contains("fragment=results"),
            () -> page.locator("a.page-btn[href*='page=1']").first().click());
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "paging must not reload the page");
        page.waitForURL(url -> url.contains("page=1"));

        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            r -> r.url().contains("/hangar") && r.url().contains("fragment=results"),
            () -> page.locator("#hangar-ship-filter").fill("zzz-no-such-ship-xyz"));
        assertThat(page.getByTestId("hangar-ship-row")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "filtering must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "hangar-pagination");
        throw failure;
      }
    }
  }
}
