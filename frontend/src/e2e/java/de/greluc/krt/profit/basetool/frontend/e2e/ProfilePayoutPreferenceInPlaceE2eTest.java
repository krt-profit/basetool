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
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the profile payout-preference save updates in place and that a second consecutive
 * in-place save does not 409 on a stale {@code @Version}.
 *
 * <p>The saves alternate between {@code PAYOUT} and {@code DONATE} so both bump the version; a
 * window marker proves no reload happened, and the persisted preference is read back from the
 * backend.
 */
@Tag("e2e")
class ProfilePayoutPreferenceInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the test user's backend {@code
   * app_user} row exists (so {@code /api/v1/users/me} carries a version for the payout update).
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
   * Switches the payout preference to the other value and saves in place, then switches back and
   * saves again without reloading, asserting the second save succeeds without a conflict and the
   * backend persisted its value.
   */
  @Test
  void savesPayoutPreferenceInPlaceThenDoubleSaveDoesNotConflict() {
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

        Locator select =
            page.locator("#profile-payout-form select[name='defaultPayoutPreference']");
        Locator submit = page.locator("#profile-payout-form button[type='submit']");

        String initial = select.inputValue();
        String firstTarget = "PAYOUT".equals(initial) ? "DONATE" : "PAYOUT";
        String secondTarget = initial.isBlank() ? "PAYOUT" : initial;

        select.selectOption(new SelectOption().setValue(firstTarget));
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Payout-preference save must update in place — no page reload on success.");
        assertEquals(
            firstTarget, persistedPayoutPreference(), "the first in-place save must persist");

        select.selectOption(new SelectOption().setValue(secondTarget));
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the second consecutive save must also stay in place — no reload cleared the marker");
        assertEquals(
            secondTarget,
            persistedPayoutPreference(),
            "the second consecutive save must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-payout-preference-in-place");
        throw failure;
      }
    }
  }

  /**
   * Submits the payout form in place and asserts a success toast, no error toast and no {@code
   * OPTIMISTIC_LOCK} reload-confirm dialog ({@code .krt-confirm-overlay}).
   *
   * @param page the authenticated profile page
   * @param submit the payout form's submit button
   */
  private static void saveInPlace(Page page, Locator submit) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().endsWith("/profile/payout-preference")
                && "POST".equals(response.request().method()),
        submit::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
    assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
  }

  /**
   * Reads the test user's currently persisted default payout preference straight from the backend
   * ({@code GET /api/v1/users/me/payout-preference}, which returns {@code defaultPayoutPreference}
   * plus the user-row version), so the persistence assertion does not race the client's in-place
   * writeback.
   *
   * @return the persisted {@code defaultPayoutPreference} enum name (e.g. {@code PAYOUT})
   */
  private static String persistedPayoutPreference() {
    String body =
        new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/users/me/payout-preference");
    return JsonParser.parseString(body)
        .getAsJsonObject()
        .get("defaultPayoutPreference")
        .getAsString();
  }
}
