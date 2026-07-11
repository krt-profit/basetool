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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Regression for the price-calculation results table after the CSP hardening of ADR-0093
 * ("eliminate inline {@code style=""} attributes so the CSP can pin {@code style-src-attr
 * 'none'}"). {@code materials-profit-calculation.js} builds its placeholder / loading / no-data /
 * error rows via {@code innerHTML}, and those {@code <td>}s carried inline {@code
 * style="text-align:center; padding:..;color:.."} attributes. An inline style inside a JS {@code
 * innerHTML} string is blocked by {@code style-src-attr 'none'} exactly like a template one, so the
 * browser would report a CSP violation for every rendered status row. The fix moves the styling to
 * the {@code profit-msg} / {@code profit-msg-loading} / {@code profit-msg-error} classes.
 *
 * <p>This test loads {@code /materials/profit-calculation}, picks a ship to drive the client render
 * (which materializes a loading row and then a data or no-data row through the migrated code path),
 * asserts the results body renders a row, and asserts no {@code style-src-attr} CSP violation was
 * logged — the pre-fix frontend logs one per status row it injects.
 *
 * <p>Read-only and target-agnostic: it navigates and reads, mutating nothing, so it is safe against
 * a shared deployment. The actor is {@code test-admin}, who may open the trade pages. When the
 * stack has no seeded ships the driving step is skipped and the console guard still runs against
 * the base page.
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
   * style-src-attr} CSP violation — the failure mode the ADR-0093 JS fix addresses.
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

        // Index 0 is the placeholder option; anything after is a seeded ship. Picking one fires
        // the profit-update handler, which renders the migrated loading row and then a data or
        // no-data row into #profitBody — the former-inline-style status <td>s under test.
        if (page.locator("#shipSelect option").count() > 1) {
          page.locator("#shipSelect").selectOption(new SelectOption().setIndex(1));
          assertThat(page.locator("#profitBody tr").first())
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
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
}
