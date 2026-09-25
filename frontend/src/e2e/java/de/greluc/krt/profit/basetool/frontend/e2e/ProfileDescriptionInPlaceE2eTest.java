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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Phase 0 exemplar for epic #571 (REQ-FE-001, REQ-FE-004): the profile description save runs
 * through {@code window.krtFetch.write} and must update in place — no page navigation on success —
 * and must self-heal a stale CSRF token via one transparent {@code GET /csrf} refresh + retry.
 *
 * <p>The "no reload" assertion uses a window marker set on the live document: a full navigation
 * wipes it, so its survival after the save proves the document was never reloaded. The forced
 * stale-token case corrupts the {@code _csrf} meta tag in the page, then saves again and asserts
 * the save still succeeds — exercising {@code krtCsrf.refresh()} + the single retry.
 */
@Tag("e2e")
class ProfileDescriptionInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the test user's backend {@code
   * app_user} row exists (so {@code /api/v1/users/me} carries a version for the description
   * update).
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
   * Saves the description in place (asserting a success toast and that the page was never
   * reloaded), then corrupts the CSRF token and saves again, asserting the stale-token write still
   * succeeds via the transparent refresh + retry and likewise without a reload.
   */
  @Test
  void savesDescriptionInPlaceThenSurvivesStaleTokenViaRetry() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/profile");
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        Locator description = page.locator("#description");
        Locator submit = page.locator("#profile-description-form button[type='submit']");

        description.fill("E2E in-place description alpha");
        submit.click();

        assertThat(page.locator(".notification-toast:not(.error-toast)")).isVisible();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Description save must update in place — no page reload on success.");

        page.evaluate(
            "() => { const m = document.querySelector('meta[name=\"_csrf\"]'); if (m) {"
                + " m.setAttribute('content', 'stale-invalid-token'); } }");
        page.evaluate(
            "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove());"
                + " }");

        description.fill("E2E in-place description beta");
        submit.click();

        assertThat(page.locator(".notification-toast:not(.error-toast)")).isVisible();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Stale-token retry must succeed transparently — still no page reload.");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-description-in-place");
        throw failure;
      }
    }
  }
}
