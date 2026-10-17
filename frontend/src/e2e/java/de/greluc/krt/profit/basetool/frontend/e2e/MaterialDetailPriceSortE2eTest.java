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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The material detail price table sorts in place by clicking its column headers (REQ-UI-015). */
@Tag("e2e")
class MaterialDetailPriceSortE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String ALPHA = "E2E Sort Alpha";
  private static final String BRAVO = "E2E Sort Bravo";
  private static final String CHARLIE = "E2E Sort Charlie";

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;

  /** Launches the browser and seeds one material priced at three terminals. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    materialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Price Sort " + UUID.randomUUID());
    seeder.seedTerminalPrice(materialId, ALPHA, new BigDecimal("300"), new BigDecimal("100"));
    seeder.seedTerminalPrice(materialId, BRAVO, new BigDecimal("500"), null);
    seeder.seedTerminalPrice(materialId, CHARLIE, new BigDecimal("100"), new BigDecimal("50"));
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
   * The table opens sorted by sale price, highest first; a header click sorts by that column in its
   * first direction, a second click reverses it, rows without a price stay last, and the sort keeps
   * working on a filtered table.
   */
  @Test
  void columnHeadersSortThePriceTableInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/" + materialId);
        page.evaluate("() => { window.__noReload = true; }");

        Locator sell = page.locator("th[data-sort-col='sell']");
        Locator buy = page.locator("th[data-sort-col='buy']");
        Locator terminal = page.locator("th[data-sort-col='terminal']");
        assertThat(page.locator("input[name='terminalSort']")).hasCount(0);
        assertThat(sell).hasAttribute("aria-sort", "descending");
        assertEquals(List.of(BRAVO, ALPHA, CHARLIE), order(page));

        page.locator("[data-testid='price-sort-buy']").click();
        assertThat(buy).hasAttribute("aria-sort", "ascending");
        assertThat(sell).hasAttribute("aria-sort", "none");
        assertEquals(List.of(CHARLIE, ALPHA, BRAVO), order(page));

        page.locator("[data-testid='price-sort-buy']").click();
        assertThat(buy).hasAttribute("aria-sort", "descending");
        assertEquals(List.of(ALPHA, CHARLIE, BRAVO), order(page));

        page.locator("[data-testid='price-sort-terminal']").press("Enter");
        assertThat(terminal).hasAttribute("aria-sort", "ascending");
        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), order(page));

        page.locator("#terminalFilter").fill("Sort");
        page.locator("[data-testid='price-sort-sell']").click();
        assertThat(sell).hasAttribute("aria-sort", "descending");
        assertEquals(List.of(BRAVO, ALPHA, CHARLIE), order(page));

        assertEquals(Boolean.TRUE, page.evaluate("() => window.__noReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-detail-price-sort");
        throw failure;
      }
    }
  }

  /**
   * At phone width the stacked table keeps its sort buttons visible as a strip, and a tap sorts the
   * rows.
   */
  @Test
  void sortButtonsStayUsableAtPhoneWidth() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(375, 812)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/" + materialId);

        Locator buyButton = page.locator("[data-testid='price-sort-buy']");
        for (String key : List.of("terminal", "location", "sell", "buy")) {
          assertThat(page.locator("[data-testid='price-sort-" + key + "']")).isVisible();
        }
        assertTrue(
            buyButton.boundingBox().height >= 44,
            "a sort button must keep the 44px touch target at phone width");

        buyButton.click();
        assertThat(page.locator("th[data-sort-col='buy']")).hasAttribute("aria-sort", "ascending");
        assertEquals(List.of(CHARLIE, ALPHA, BRAVO), order(page));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-detail-price-sort-phone");
        throw failure;
      }
    }
  }

  /**
   * Reads the terminal names of the price table's rows in their current order.
   *
   * @param page the material detail page
   * @return the terminal names, top to bottom
   */
  private static List<String> order(Page page) {
    Object names =
        page.locator("#priceTable tbody tr")
            .evaluateAll("rows => rows.map(r => r.getAttribute('data-terminal'))");
    return ((List<?>) names).stream().map(String::valueOf).toList();
  }
}
