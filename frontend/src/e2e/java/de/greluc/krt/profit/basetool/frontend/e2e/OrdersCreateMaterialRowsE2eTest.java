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
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the material rows of the {@code /orders/create} form (REQ-UI-027): rows are removed only
 * while another remains, the remaining rows are renumbered without a gap, the comment counter
 * follows the text, and the scmdb import opens as a dialog.
 *
 * <p>Read-only: never submits.
 */
@Tag("e2e")
class OrdersCreateMaterialRowsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser shared across the page check. */
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
   * Adds two rows, removes the first, checks the amount fields are named {@code materials[0]} and
   * {@code materials[1]}, removes down to one row whose remove button is then disabled, types a
   * comment and reads the counter, and opens and closes the scmdb dialog.
   */
  @Test
  void materialRowsRemoveRenumberAndCount() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        Locator rows = page.locator("#materials-container .material-row");
        Locator removes =
            page.locator("#materials-container [data-trigger=\"orders-remove-material\"]");
        assertThat(rows).hasCount(1);
        assertThat(removes.first()).isDisabled();

        page.locator("[data-trigger=\"orders-add-material\"]").click();
        page.locator("[data-trigger=\"orders-add-material\"]").click();
        assertThat(rows).hasCount(3);
        assertThat(removes.first()).isEnabled();

        removes.first().click();
        assertThat(rows).hasCount(2);
        assertEquals(
            List.of("materials[0].amount", "materials[1].amount"),
            page.locator("#materials-container [data-role=\"material-amount\"]")
                .evaluateAll("els => els.map(e => e.getAttribute('name'))"),
            "the remaining rows are renumbered without a gap");

        removes.first().click();
        assertThat(rows).hasCount(1);
        assertThat(removes.first()).isDisabled();

        page.locator("#comment").fill("Hallo");
        assertThat(page.locator("#comment-count")).hasText("5 / 1000");
        assertThat(page.getByTestId("order-summary")).not().isEmpty();

        page.getByTestId("orders-scmdb-open").click();
        assertThat(page.locator("#orders-scmdb-modal")).isVisible();
        assertThat(page.locator("#scmdb-import-text")).isVisible();
        page.keyboard().press("Escape");
        assertThat(page.locator("#orders-scmdb-modal")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-create-material-rows");
        throw failure;
      }
    }
  }
}
