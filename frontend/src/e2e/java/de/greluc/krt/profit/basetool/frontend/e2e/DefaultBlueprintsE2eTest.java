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
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end tests of default blueprints (REQ-INV-016, REQ-INV-017): a granted default shows no
 * delete control to its owner, and an admin can remove a default from the set in place.
 */
@Tag("e2e")
class DefaultBlueprintsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, forces the test user's first login so their
   * {@code app_user} row is created and the default blueprints are granted before the flows run.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().getUserId(USERNAME, PASSWORD);
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
   * Opens the owner's blueprint list, selects an auto-granted default, and asserts the detail pane
   * shows the edit control but hides the delete control — the hidden-delete UX the owner chose.
   */
  @Test
  void defaultBlueprintOffersNoDeleteControl() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/personal-inventory/blueprints");
        page.waitForLoadState();

        Locator defaultRow =
            page.locator("#krt-bp-master-rows .master-row[data-removable='false']").first();
        assertThat(defaultRow)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));

        defaultRow.click();

        assertThat(page.locator("#krt-bp-detail-edit")).isVisible();
        assertThat(page.locator("#krt-bp-detail-delete")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "default-blueprint-no-delete");
        throw failure;
      }
    }
  }

  /**
   * Opens the admin default-blueprints page, asserts the seeded set is listed, removes one entry
   * through the confirm modal, and asserts the set shrank by one in place, with the modal closed
   * and the document never reloaded (REQ-FE-001).
   */
  @Test
  void adminCanRemoveADefaultFromTheSet() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/default-blueprints");
        page.waitForLoadState();

        Locator removeButtons = page.locator("[data-trigger='dbp-open-delete']");
        assertThat(removeButtons.first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        int before = removeButtons.count();

        page.evaluate("() => { window.__krtNoReload = true; }");

        removeButtons.first().click();
        assertThat(page.locator("#krt-dbp-delete-modal")).isVisible();
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        Response removed =
            page.waitForResponse(
                response ->
                    response.url().endsWith("/delete")
                        && "POST".equals(response.request().method()),
                () -> page.locator("#krt-dbp-delete-confirm").click());
        assertEquals(200, removed.status(), "the in-place remove must succeed");

        assertThat(page.locator("[data-trigger='dbp-open-delete']")).hasCount(before - 1);
        assertThat(page.locator("#krt-dbp-delete-modal")).isHidden();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "removing a default must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "default-blueprint-admin-remove");
        throw failure;
      }
    }
  }
}
