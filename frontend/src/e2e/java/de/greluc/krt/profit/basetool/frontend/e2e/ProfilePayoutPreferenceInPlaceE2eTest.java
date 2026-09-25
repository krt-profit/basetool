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
 * Regression for the stale optimistic-lock {@code @Version} bug class on in-place writes (epic
 * #571): the profile default-payout-preference save ({@code POST /profile/payout-preference} →
 * {@code PUT /api/v1/users/me/payout-preference}) saves through {@code window.krtFetch.write}
 * without a page reload and must survive a SECOND consecutive in-place save.
 *
 * <p>The proven bug: the pre-fix backend returned a stale user {@code version} after a real payout
 * change while the DB advanced one further, so replaying the returned version on the next write
 * 409ed with {@code OPTIMISTIC_LOCK}. The fix flushes (save → saveAndFlush) so the response carries
 * the fresh version that the page writes back via {@code syncAllVersions}.
 *
 * <p>Each save flips the preference to a <em>genuinely different</em> enum value (the only two
 * values are {@code PAYOUT} and {@code DONATE}, so the two saves alternate between them): re-saving
 * the same value is a no-op that never bumps the {@code @Version} and would not exercise the bug. A
 * window marker set on the live document proves no reload happened between the two saves, and the
 * persisted preference is read back from the backend to confirm the second write actually landed.
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
   * Changes the payout preference to the value the form is NOT currently on and saves in place
   * (asserting a success toast and that the page was never reloaded), then — without reloading —
   * flips it to the third (other) value and saves again, asserting the second consecutive save
   * succeeds (success toast, no error/conflict toast, no reload-confirm dialog, marker survives)
   * and that the second value is the one the backend persisted. The second save only succeeds if
   * the twin wrote the fresh {@code @Version} back into the hidden input — a stale version 409s.
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
   * Clears any prior toast, submits the payout form, waits for the in-place {@code POST
   * /profile/payout-preference} to settle, and asserts the success UX: a non-error success toast
   * appeared, NO error toast surfaced, and the {@code OPTIMISTIC_LOCK} reload-confirm dialog
   * ({@code .krt-confirm-overlay}) never opened. The latter two are the discriminators that turn a
   * stale second save red on the pre-fix backend.
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
