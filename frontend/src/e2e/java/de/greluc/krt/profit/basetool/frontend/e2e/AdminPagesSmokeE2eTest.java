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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Non-destructive page-load smoke for the ADMIN-only management pages that had no end-to-end
 * coverage at all — several admin data-management screens plus the members list and the
 * organisation-leadership view. It guards against a page rendering a 500 (a stale template, a
 * broken controller binding, a missing model attribute) the way {@code CorePagesSmokeE2eTest}
 * guards the member-facing core pages.
 *
 * <p>Unlike {@code CorePagesSmokeE2eTest} (tagged {@code smoke}, target-agnostic, possibly a
 * non-admin staging user), these pages are {@code ADMIN}-gated, so this class logs in explicitly as
 * {@code test-admin} and is tagged {@code @Tag("e2e")} to run against the ephemeral stack where
 * that seeded admin exists. It mutates nothing — every check is a GET — asserting both an HTTP 200
 * and the authenticated shell ({@code nav-logout}), so a page that bounced to the IdP or errored
 * fails loudly. Pages already covered by a dedicated flow ({@code /admin/settings}, {@code
 * /admin/materials}, {@code /admin/special-commands}, {@code /admin/default-blueprints}, {@code
 * /admin/bank}, {@code /admin/audit-log}, {@code /admin/mission-data}) are intentionally omitted.
 */
@Tag("e2e")
class AdminPagesSmokeE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the admin's own {@code app_user} row
   * exists, then captures one authenticated admin session reused across all page checks.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Navigates to an ADMIN-only page with the authenticated admin session and asserts it renders
   * (HTTP 200 and the authenticated sidebar), proving the page loads without touching any data.
   *
   * @param path the app-relative path of the admin page to load
   */
  @ParameterizedTest(name = "admin page {0} loads")
  @ValueSource(
      strings = {
        "/members",
        "/organisation/leitung",
        "/admin/locations",
        "/admin/material-aliases",
        "/admin/uex-data",
        "/admin/discord-registrations",
        "/admin/sync-reports",
        "/admin/p4k-import",
        "/admin/announcement",
        "/admin/notification-rules",
        "/admin/org-structure",
        "/admin/blueprints",
        "/admin/personal-inventory",
        "/admin/personal-blueprints"
      })
  void adminPageLoads(String path) {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        Response response = E2eSupport.navigate(page, baseUrl + path);
        assertEquals(200, response.status(), "admin page " + path + " must render HTTP 200");
        assertThat(page.getByTestId("nav-logout")).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        String slug = path.substring(1).replace('/', '-');
        E2eSupport.dump(page, "admin-smoke-" + slug);
        throw failure;
      }
    }
  }
}
