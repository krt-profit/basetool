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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The admin material-create modal on {@code /admin/materials} sends exactly one write on a
 * double-click and renders the new row in {@code #materialsTable} without a page reload.
 */
@Tag("e2e")
class AdminMaterialCreateInPlaceE2eTest {

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
   * Double-clicks the create button and asserts one create POST, the new row in the table, and no
   * page reload.
   */
  @Test
  void doubleClickedCreateSendsOneWriteAndRendersTheRowInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String materialName = "E2E Material " + UUID.randomUUID();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      AtomicInteger createPosts = new AtomicInteger();
      page.onRequest(
          request -> {
            if ("POST".equals(request.method())
                && request.url().endsWith("/admin/materials/ajax")) {
              createPosts.incrementAndGet();
            }
          });
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/materials");
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");

        page.locator("[data-trigger='materials-open-create-modal']").click();
        page.locator("#cm-name").fill(materialName);

        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/materials/ajax")
                    && "POST".equals(response.request().method()),
            () ->
                page.locator("#modal-create-material [data-trigger='materials-submit-create']")
                    .dblclick());

        Locator newRow =
            page.locator("#materialsTable tbody tr")
                .filter(
                    new Locator.FilterOptions()
                        .setHas(
                            page.locator("td:first-child")
                                .filter(new Locator.FilterOptions().setHasText(materialName))));
        assertThat(newRow).hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(15_000));

        assertEquals(
            1, createPosts.get(), "a double-clicked create must send exactly one create POST");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "creating a material must re-render the table in place — no full reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-material-create-in-place");
        throw failure;
      }
    }
  }
}
