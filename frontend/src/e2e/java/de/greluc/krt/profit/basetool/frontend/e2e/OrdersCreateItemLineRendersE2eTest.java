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
 * Regression for the item-order editor's dynamically-added lines after the CSP hardening of
 * ADR-0093 ("eliminate inline {@code style=""} attributes so the CSP can pin {@code style-src-attr
 * 'none'}"). {@code orders-create.js} builds each item line via {@code innerHTML}, and the row's
 * flex layout, the SCU hint, and the derived-materials rows carried inline {@code style=""}
 * attributes. Under {@code style-src-attr 'none'} those inline styles are blocked, so the row lost
 * its flex layout and the SCU hint could no longer be hidden/revealed. The fix moves the layout to
 * the {@code oc-*} classes plus the global {@code flex-1}/{@code flex-2}/{@code nowrap} utilities
 * and toggles the runtime {@code krtm-hidden} class instead of clearing an inline style.
 *
 * <p>This test opens {@code /orders/create}, switches to item-order mode, adds an item line,
 * asserts the client-built {@code oc-line-fields} row is visible, and asserts no {@code
 * style-src-attr} CSP violation was logged — the pre-fix frontend logs one for every inline style
 * it injects into the row.
 *
 * <p>Read-only: it builds a line in the browser but never submits the form, so it mutates no server
 * state and is safe against a shared deployment. The actor is {@code test-admin}, who may create
 * orders.
 */
@Tag("e2e")
class OrdersCreateItemLineRendersE2eTest {

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
   * Opens the create-order form in item mode, adds an item line, and asserts the client-rendered
   * row appears with its flex layout intact and no {@code style-src-attr} CSP violation — the
   * failure mode the ADR-0093 JS fix addresses.
   */
  @Test
  void addedItemLineRendersWithoutCspViolation() {
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
        E2eSupport.navigate(page, baseUrl + "/orders/create");

        // Switching to item mode reveals the (initially hidden) #mode-item section holding the
        // add-item control; adding a line runs the innerHTML row builder under test.
        page.getByTestId("order-mode-item").check();
        page.locator("[data-trigger=\"orders-add-item\"]").click();

        assertThat(page.locator("#item-lines .oc-line-fields").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));

        List<String> cspStyleViolations =
            consoleLog.stream().filter(line -> line.contains("style-src-attr")).toList();
        assertTrue(
            cspStyleViolations.isEmpty(),
            "no style-src-attr CSP violation must be logged on the order create form, but saw: "
                + cspStyleViolations);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-create-item-line");
        throw failure;
      }
    }
  }
}
