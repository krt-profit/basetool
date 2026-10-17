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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the {@code /materials/profit-calculation} results table settles into its rows or
 * its state line without a {@code style-src-attr} CSP violation (ADR-0093), and that a ready row
 * names the buy and the sell terminal of its route.
 *
 * <p>Read-only; runs as {@code test-admin}. Without seeded ships only the console guard runs.
 */
@Tag("e2e")
class MaterialsProfitCalculationRendersE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser shared across the (single) page check. */
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

  /**
   * Loads {@code /materials/profit-calculation}, drives the ship selector to render the
   * client-built results rows, and asserts the results body populates without any {@code
   * style-src-attr} CSP violation — the failure mode the ADR-0093 JS fix addresses. A ready table's
   * first row must name a buy and a sell terminal.
   */
  @Test
  void profitCalculationRowsRenderWithoutCspViolation() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      List<String> consoleLog = new CopyOnWriteArrayList<>();
      page.onConsoleMessage((ConsoleMessage message) -> consoleLog.add(message.text()));
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/profit-calculation");
        assertThat(page.locator("#resultsTable")).isVisible();

        if (page.locator("#shipSelect option").count() > 1) {
          page.locator("#shipSelect").selectOption(new SelectOption().setIndex(1));
          assertThat(
                  page.locator(
                      "#profitResults[data-state='ready'], #profitResults[data-state='empty'],"
                          + " #profitResults[data-state='error']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
          if (page.locator("#profitResults[data-state='ready']").count() > 0) {
            assertThat(
                    page.locator("[data-testid='profit-row']")
                        .first()
                        .locator("[data-testid='profit-route-terminal']"))
                .hasCount(2);
          }
        }

        List<String> cspStyleViolations =
            consoleLog.stream().filter(line -> line.contains("style-src-attr")).toList();
        assertTrue(
            cspStyleViolations.isEmpty(),
            "no style-src-attr CSP violation must be logged on the profit calculation, but saw: "
                + cspStyleViolations);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-profit-calculation");
        throw failure;
      }
    }
  }

  /**
   * Sorts a ready results table through its column headers (REQ-UI-029): the default order is Max.
   * Profit descending, a click on Marge orders the rows by margin with the highest first and marks
   * that header, a second click reverses it, and every row keeps its profit rank.
   */
  @Test
  void resultsSortByClickingTheColumnHeaders() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/profit-calculation");
        page.evaluate("() => localStorage.removeItem('profit_calculation_filters')");
        E2eSupport.navigate(page, baseUrl + "/materials/profit-calculation");
        Locator profitHeader = page.locator("#resultsTable th[data-sort-col='profit']");
        Locator marginHeader = page.locator("#resultsTable th[data-sort-col='margin']");
        assertThat(profitHeader).hasAttribute("aria-sort", "descending");
        assertThat(marginHeader).hasAttribute("aria-sort", "none");
        assertThat(page.locator("#resultsTable th[data-sort-col='route']")).hasCount(0);

        if (page.locator("#shipSelect option").count() < 2) {
          return;
        }
        page.locator("#shipSelect").selectOption(new SelectOption().setIndex(1));
        assertThat(
                page.locator(
                    "#profitResults[data-state='ready'], #profitResults[data-state='empty'],"
                        + " #profitResults[data-state='error']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        Locator rows = page.locator("[data-testid='profit-row']");
        if (page.locator("#profitResults[data-state='ready']").count() == 0 || rows.count() < 2) {
          return;
        }
        List<Double> defaultProfits = numbers(rows, "data-profit");
        assertTrue(isOrdered(defaultProfits, false), "default order is Max. Profit descending");
        assertThat(rows.first()).hasAttribute("data-rank", "1");

        page.locator("[data-testid='profit-sort-margin']").click();
        assertThat(marginHeader).hasAttribute("aria-sort", "descending");
        assertThat(profitHeader).hasAttribute("aria-sort", "none");
        assertTrue(isOrdered(numbers(rows, "data-margin"), false), "margin descending");

        page.locator("[data-testid='profit-sort-margin']").click();
        assertThat(marginHeader).hasAttribute("aria-sort", "ascending");
        assertTrue(isOrdered(numbers(rows, "data-margin"), true), "margin ascending");
        assertTrue(
            ranks(rows).stream().sorted().toList().equals(rankRange(rows.count())),
            "every row keeps its profit rank");

        page.locator("[data-testid='profit-sort-profit']").click();
        assertThat(profitHeader).hasAttribute("aria-sort", "descending");
        assertThat(rows.first()).hasAttribute("data-rank", "1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-profit-calculation-sort");
        throw failure;
      }
    }
  }

  /**
   * Reads one numeric data attribute from every row, in document order, skipping rows without it.
   *
   * @param rows the result rows
   * @param attribute the data attribute
   * @return the values in row order
   */
  private static List<Double> numbers(Locator rows, String attribute) {
    return rows.evaluateAll(
                "(els, name) => els.map((el) => el.getAttribute(name)).filter((v) => v !== '')",
                attribute)
            instanceof List<?> raw
        ? raw.stream().map(value -> Double.parseDouble(String.valueOf(value))).toList()
        : List.of();
  }

  /**
   * Reads the profit rank of every row.
   *
   * @param rows the result rows
   * @return the ranks in row order
   */
  private static List<Integer> ranks(Locator rows) {
    return rows.evaluateAll("els => els.map((el) => el.getAttribute('data-rank'))")
            instanceof List<?> raw
        ? raw.stream().map(value -> Integer.parseInt(String.valueOf(value))).toList()
        : List.of();
  }

  /**
   * The ranks one to {@code count}.
   *
   * @param count the number of rows
   * @return the expected ranks in ascending order
   */
  private static List<Integer> rankRange(int count) {
    return IntStream.rangeClosed(1, count).boxed().toList();
  }

  /**
   * Whether the values never fall (ascending) or never rise (descending).
   *
   * @param values the values in row order
   * @param ascending the direction to check
   * @return true when the values follow the direction
   */
  private static boolean isOrdered(List<Double> values, boolean ascending) {
    for (int i = 1; i < values.size(); i++) {
      double previous = values.get(i - 1);
      double current = values.get(i);
      if (ascending ? current < previous : current > previous) {
        return false;
      }
    }
    return true;
  }
}
