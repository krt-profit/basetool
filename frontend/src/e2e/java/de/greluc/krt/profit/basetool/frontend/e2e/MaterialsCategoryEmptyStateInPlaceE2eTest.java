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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E regression test: deleting the last material category in place on {@code /admin/materials}
 * restores the "no entries" placeholder without a reload.
 *
 * <p>Relies on the ephemeral stack starting with no categories; runs as {@code test-admin}.
 */
@Tag("e2e")
class MaterialsCategoryEmptyStateInPlaceE2eTest {

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
   * Creates one material category in place, deletes it in place, and asserts the empty-state
   * placeholder reappears without a reload.
   */
  @Test
  void deletingLastCategoryRestoresEmptyStateInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String categoryName = "E2E Cat " + UUID.randomUUID();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/materials");
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        page.locator("form[data-category-create] input[name='name']").fill(categoryName);
        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/materials/categories")
                    && "POST".equals(response.request().method()),
            () -> page.locator("form[data-category-create] button[type='submit']").click());
        Locator newRow =
            page.locator("tr[data-category-row]")
                .filter(new Locator.FilterOptions().setHasText(categoryName));
        assertThat(newRow).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        newRow.locator("button[type='submit']").click();
        page.waitForResponse(
            response ->
                response.url().contains("/admin/materials/categories/")
                    && response.url().endsWith("/delete")
                    && "POST".equals(response.request().method()),
            () -> page.locator(".krt-confirm-ok").click());

        assertThat(page.locator("[data-category-empty]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(
                page.locator("tr[data-category-row]")
                    .filter(new Locator.FilterOptions().setHasText(categoryName)))
            .hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "deleting the last category must restore the empty-state in place — no full reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-category-empty-state");
        throw failure;
      }
    }
  }
}
