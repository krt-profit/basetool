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
 * End-to-end coverage for the default-blueprint feature (REQ-INV-016/017) against the live stack.
 *
 * <p>The default blueprints are seeded into {@code default_blueprint} at backend startup (the
 * provisioning bootstrap; enabled in the {@code dev} profile the e2e stack runs) and granted to a
 * user the first time their {@code app_user} row is created — which {@link BackendSeeder#getUserId}
 * forces in {@link #setUp()}. Two flows are asserted:
 *
 * <ul>
 *   <li><b>user-facing non-removability:</b> a granted default appears in the owner's blueprint
 *       list and its detail pane offers the edit control but <em>no</em> delete control (the {@code
 *       removable=false} flag hides it), which is the exact UX the owner chose over a 409.
 *   <li><b>admin curation:</b> the admin default-blueprints page lists the seeded set, and removing
 *       one entry through the confirm modal drops it from the set in place, without a reload.
 * </ul>
 *
 * <p>Removing an entry in the admin flow does not revoke rows users already hold, so the two tests
 * are order-independent on the shared ephemeral stack.
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
      // Materialises the user row on first login, which grants the default blueprints synchronously
      // in UserService.syncUser before the call returns.
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

        // Target an auto-granted default explicitly (data-removable="false") rather than assuming
        // row order, so the test stays valid even if the user later owns non-default blueprints.
        Locator defaultRow =
            page.locator("#krt-bp-master-rows .master-row[data-removable='false']").first();
        assertThat(defaultRow)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));

        defaultRow.click();

        // The detail pane renders with the edit control but the delete control stays hidden.
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
        // The set is fully server-rendered on load, so this count is stable here.
        int before = removeButtons.count();

        // A marker on the window survives only if the document is never reloaded (REQ-FE-001).
        page.evaluate("() => { window.__krtNoReload = true; }");

        // Open the confirm modal for the first default, then confirm it (the confirm button sends
        // the row's server-rendered action through krtFetch and re-swaps the list).
        removeButtons.first().click();
        assertThat(page.locator("#krt-dbp-delete-modal")).isVisible();
        // Not clickSubmitClearingFooter: that helper waits for a post-submit NAVIGATION, which an
        // in-place write never starts. Wait for the XHR remove itself instead, with the fixed
        // footer moved out of the click's way as the helper would.
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

        // Web-first count assertion: auto-retries until the swapped list shows one fewer entry, so
        // it never races the in-place re-render (a one-shot count() flakes on WebKit / Firefox).
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
