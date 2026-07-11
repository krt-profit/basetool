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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Reveal-over-class regression for the material-order editor's SCU hint after ADR-0093. The "?" SCU
 * hint that {@code orders-create.js} builds for each material row starts hidden and is revealed
 * only when an SCU-typed material is chosen. Before the fix it started hidden via an inline {@code
 * style="display:none"} and was revealed with {@code hint.style.display = ''}; the CSP migration
 * moved the hidden state to a class, at which point clearing the (empty) inline style could no
 * longer override the class rule, so the hint stayed hidden for SCU materials. The fix toggles the
 * runtime {@code krtm-hidden} class instead.
 *
 * <p>This test opens {@code /orders/create}, adds a material row, selects an SCU-typed material,
 * and asserts the row's SCU hint becomes visible — the discriminator that turns red on the pre-fix
 * frontend (which leaves the hint hidden) — while asserting no {@code style-src-attr} CSP violation
 * is logged. Materials with a quantity type are core seed data, so the SCU option is present on any
 * provisioned stack; the reveal step is guarded so a stack seeded without an SCU material still
 * runs the console guard rather than failing spuriously.
 *
 * <p>Read-only: it builds a row in the browser but never submits, so it mutates no server state.
 * The actor is {@code test-admin}, who may create orders.
 */
@Tag("e2e")
class OrdersCreateScuHintRevealE2eTest {

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
   * Adds a material row on the create-order form and asserts that choosing an SCU material reveals
   * the row's SCU hint (via the toggled visibility class) without any {@code style-src-attr} CSP
   * violation — the reveal-over-class failure mode the ADR-0093 JS fix addresses.
   */
  @Test
  void scuMaterialRevealsHintWithoutCspViolation() {
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

        // Material mode is the default; add a fresh row (the SCU hint of which is built by
        // scuHintMarkup and starts hidden via krtm-hidden).
        page.locator("[data-trigger=\"orders-add-material\"]").click();
        Locator row = page.locator("#materials-container .material-row").last();
        Locator select = row.locator("[data-role=\"material-select\"]");

        Locator scuOption = select.locator("option[data-quantity-type=\"SCU\"]").first();
        if (scuOption.count() > 0) {
          select.selectOption(scuOption.getAttribute("value"));
          assertThat(row.locator(".scu-hint"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        }

        List<String> cspStyleViolations =
            consoleLog.stream().filter(line -> line.contains("style-src-attr")).toList();
        assertTrue(
            cspStyleViolations.isEmpty(),
            "no style-src-attr CSP violation must be logged on the order create form, but saw: "
                + cspStyleViolations);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-create-scu-hint");
        throw failure;
      }
    }
  }
}
