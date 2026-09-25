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
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that choosing an SCU-typed material in the {@code /orders/create} material editor
 * reveals the row's SCU hint by toggling {@code krtm-hidden}, without a {@code style-src-attr} CSP
 * violation (ADR-0093).
 *
 * <p>Read-only: never submits. Without an SCU material only the console guard runs.
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
   * Adds a material row, chooses an SCU material and asserts the SCU hint is revealed without a CSP
   * violation; also asserts that the combobox's hidden input mirrors the option's {@code
   * data-quantity-type} and drops it on {@code setValue('')} (REQ-FE-016).
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

        page.locator("[data-trigger=\"orders-add-material\"]").click();
        Locator row = page.locator("#materials-container .material-row").last();

        Object catalog =
            page.evaluate(
                "() => fetch('/catalog/material-search?jobOrder=true&q=')"
                    + ".then(r => (r.ok ? r.json() : []))");
        String scuMaterialId = null;
        if (catalog instanceof List<?> rows) {
          for (Object entry : rows) {
            if (entry instanceof Map<?, ?> material && "SCU".equals(material.get("quantityType"))) {
              Object id = material.get("id");
              scuMaterialId = id == null ? null : id.toString();
              break;
            }
          }
        }
        if (scuMaterialId != null) {
          E2eSupport.selectComboboxByValue(
              row.locator(
                  ".krt-combobox:has([data-role=\"material-select\"])" + " .krt-combobox__input"),
              scuMaterialId);
          assertThat(row.locator(".scu-hint"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));

          Locator hiddenMaterial = row.locator("input[data-role=\"material-select\"]");
          assertEquals(
              "SCU",
              hiddenMaterial.getAttribute("data-quantity-type"),
              "the picked SCU option's quantity type must be mirrored onto the hidden input");
          Object staleKeyRemoved =
              hiddenMaterial.evaluate(
                  "el => { el.krtCombobox.setValue(''); return el.dataset.quantityType"
                      + " === undefined; }");
          assertEquals(
              Boolean.TRUE,
              staleKeyRemoved,
              "setValue('') must remove the previously mirrored data-quantity-type");
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
