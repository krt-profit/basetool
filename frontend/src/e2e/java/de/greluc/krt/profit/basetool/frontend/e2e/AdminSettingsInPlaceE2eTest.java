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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The admin system-settings form saves in place and a second consecutive in-place save does not
 * 409, proving the fresh per-setting {@code @Version} is written back after each save.
 */
@Tag("e2e")
class AdminSettingsInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Backend key of the yellow-aging threshold setting the test bumps twice. */
  private static final String YELLOW_DAYS_KEY = "job_order.age_yellow_days";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the admin test user's backend {@code
   * app_user} row exists (the settings page is {@code ADMIN}-gated and {@code test-admin} carries
   * that role from the seeded realm).
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
   * Saves {@code ageYellowDays} twice in place with different values and asserts both succeed
   * without reload or conflict, and that the second value is persisted.
   */
  @Test
  void savesSettingsInPlaceThenDoubleSaveDoesNotConflict() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/settings");
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        Locator yellowDays = page.locator("#ageYellowDays");
        Locator submit = page.locator("#admin-settings-form button[type='submit']");

        int firstValue = 31;
        int secondValue = 32;

        yellowDays.fill(String.valueOf(firstValue));
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the settings save must update in place — no page reload on success");
        assertEquals(firstValue, persistedYellowDays(), "the first in-place save must persist");

        yellowDays.fill(String.valueOf(secondValue));
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the second consecutive save must also stay in place — no reload cleared the marker");
        assertEquals(
            secondValue,
            persistedYellowDays(),
            "the second consecutive save must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-settings-in-place");
        throw failure;
      }
    }
  }

  /**
   * Submits the settings form and asserts a success toast, no error toast and no {@code
   * OPTIMISTIC_LOCK} reload-confirm dialog.
   *
   * @param page the authenticated admin-settings page
   * @param submit the settings form's submit button
   */
  private static void saveInPlace(Page page, Locator submit) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().endsWith("/admin/settings")
                && "POST".equals(response.request().method()),
        submit::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
    assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
  }

  /**
   * Reads the persisted yellow-aging threshold from {@code GET
   * /api/v1/settings/job_order.age_yellow_days}.
   *
   * @return the persisted {@code ageYellowDays} value as an int
   * @throws IllegalStateException if the backend returns a non-numeric {@code value}
   */
  private static int persistedYellowDays() {
    String body =
        new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/settings/" + YELLOW_DAYS_KEY);
    String raw = JsonParser.parseString(body).getAsJsonObject().get("value").getAsString();
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new IllegalStateException(
          "Setting '" + YELLOW_DAYS_KEY + "' returned a non-numeric value: '" + raw + "'", e);
    }
  }
}
