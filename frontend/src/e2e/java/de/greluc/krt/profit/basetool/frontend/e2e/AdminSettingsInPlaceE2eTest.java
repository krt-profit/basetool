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
 * Regression for the stale optimistic-lock {@code @Version} bug class on in-place writes (epic
 * #571, #582): the admin system-settings form ({@code POST /admin/settings} with {@code
 * X-Requested-With} → per-setting {@code PUT /api/v1/settings/{key}}) saves through {@code
 * window.krtFetch.write} without a page reload and must survive a SECOND consecutive in-place save.
 *
 * <p>The proven bug: a pre-fix backend {@code SystemSettingService.updateSetting} returned a stale
 * version after a real change, so the next write replaying that version 409ed with {@code
 * OPTIMISTIC_LOCK}. The fix flushes (save → saveAndFlush) so the AJAX twin returns the bumped
 * versions that the page writes back into the hidden {@code *Version} inputs.
 *
 * <p>Each save bumps {@code ageYellowDays} to a <em>genuinely different</em> value (a no-op re-save
 * never bumps the {@code @Version} and would not exercise the bug). A window marker proves no
 * reload happened between the two saves; the persisted value is read back from the backend to
 * confirm the second write landed; and the absence of an error toast and of the {@code
 * OPTIMISTIC_LOCK} reload-confirm dialog ({@code .krt-confirm-overlay}) is the discriminator that
 * turns the second save red on the pre-fix backend.
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
   * Opens the admin-settings page, changes {@code ageYellowDays} to a fresh value and saves in
   * place (asserting a success toast and no reload), then — without reloading — changes it again
   * and saves a second time, asserting the second consecutive save is a 200 (success toast, no
   * error/conflict toast, no reload-confirm dialog, marker survives) and that the second value is
   * the one the backend persisted. The second save only succeeds if the AJAX twin wrote the fresh
   * per-setting {@code @Version} back into the hidden version input — a stale version 409s
   * OPTIMISTIC_LOCK.
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
   * Clears any prior toast, submits the settings form, waits for the in-place {@code POST
   * /admin/settings} to settle, and asserts the success UX: a non-error success toast appeared, NO
   * error toast surfaced, and the {@code OPTIMISTIC_LOCK} reload-confirm dialog ({@code
   * .krt-confirm-overlay}) never opened — the latter two being what a stale second save would
   * trigger on the pre-fix backend. The submit button is re-enabled in the twin's {@code finally},
   * so waiting on the POST response and the resulting toast suffices to gate the next click.
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
   * Reads the persisted yellow-aging threshold straight from the backend ({@code GET
   * /api/v1/settings/job_order.age_yellow_days}), so the persistence assertion does not race the
   * client's in-place writeback.
   *
   * @return the persisted {@code ageYellowDays} value as an int
   * @throws IllegalStateException if the backend returns a non-numeric {@code value} for the
   *     setting — surfaced explicitly (with the offending raw value and key) instead of leaking a
   *     bare {@link NumberFormatException}, while still failing the test
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
