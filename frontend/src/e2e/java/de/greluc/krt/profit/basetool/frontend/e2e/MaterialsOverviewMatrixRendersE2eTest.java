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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the {@code /materials/overview} price matrix becomes visible and that no {@code
 * style-src-attr} CSP violation is logged (ADR-0093).
 *
 * <p>Read-only, so safe against a shared deployment; runs as {@code test-admin}.
 */
@Tag("e2e")
class MaterialsOverviewMatrixRendersE2eTest {

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
   * Loads {@code /materials/overview} and asserts the client-rendered matrix container is revealed
   * (loading and error boxes hidden) and no {@code style-src-attr} CSP violation is logged — the
   * two failure modes the ADR-0093 fix addresses.
   */
  @Test
  void priceOverviewMatrixBecomesVisibleWithoutCspViolation() {
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
        E2eSupport.navigate(page, baseUrl + "/materials/overview");

        assertThat(page.locator("#tableContainer"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        assertThat(page.locator("#matrixLoading")).isHidden();
        assertThat(page.locator("#matrixError")).isHidden();

        List<String> cspStyleViolations =
            consoleLog.stream().filter(line -> line.contains("style-src-attr")).toList();
        assertTrue(
            cspStyleViolations.isEmpty(),
            "no style-src-attr CSP violation must be logged on the price overview, but saw: "
                + cspStyleViolations);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-overview-matrix");
        throw failure;
      }
    }
  }
}
