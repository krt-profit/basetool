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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The paginated catalogue lists on {@code /admin/materials}, {@code /admin/locations} and {@code
 * /ship-data} search server-side: typing swaps the results fragment in place, keeps the address bar
 * in step and updates the count chip, without a page reload (REQ-ADMIN-001, REQ-FE-005).
 */
@Tag("e2e")
class CatalogueListSearchInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the admin user's IRIDIUM membership.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
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
   * Searches a list for a name nothing carries and asserts the in-place swap: the empty state, the
   * search in the address bar, a zero count chip and no reload.
   *
   * @param path the list page
   * @param inputId the search input's id
   * @param resultsId the results container's id
   * @param countId the count chip's id
   */
  @ParameterizedTest
  @CsvSource({
    "/admin/materials, filterMaterials, materials-results, materials-count",
    "/admin/locations, filterLocations, locations-results, locations-count",
    "/ship-data, filterShipTypes, ship-types-results, sd-shiptypes-count"
  })
  void aSearchSwapsTheResultsInPlace(
      String path, String inputId, String resultsId, String countId) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String query = "e2e-none-" + UUID.randomUUID().toString().substring(0, 8);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");

        page.waitForResponse(
            response ->
                response.url().contains(path + "?") && response.url().contains("fragment=results"),
            () -> page.locator("#" + inputId).fill(query));

        assertThat(page.locator("#" + resultsId + " [data-testid='empty-state']")).isVisible();
        assertThat(page.locator("#" + countId)).hasText("0");
        assertTrue(page.url().contains("query=" + query), "the search must reach the address bar");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "a search must swap the list in place — no full reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "catalogue-list-search-in-place");
        throw failure;
      }
    }
  }
}
