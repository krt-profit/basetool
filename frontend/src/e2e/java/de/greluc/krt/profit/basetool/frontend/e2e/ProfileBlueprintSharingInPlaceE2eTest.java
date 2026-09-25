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
 * Verifies that the profile blueprint-sharing toggle (REQ-INV-018) saves in place and that a second
 * consecutive in-place save does not 409 on a stale {@code @Version}.
 *
 * <p>Each save flips the toggle so both bump the version; a window marker proves no reload
 * happened, and the persisted flag is read back from the backend.
 */
@Tag("e2e")
class ProfileBlueprintSharingInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the test user's backend {@code
   * app_user} row exists (so {@code /api/v1/users/me} carries a version for the toggle update).
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
   * Flips the blueprint-sharing toggle to the opposite of its current state and saves in place
   * (asserting a success toast and that the page was never reloaded), then — without reloading —
   * flips it back and saves again, asserting the second consecutive save succeeds (success toast,
   * no error/conflict toast, no reload-confirm dialog, marker survives) and that the backend
   * persisted the expected flag. The second save only succeeds if the twin wrote the fresh
   * {@code @Version} back into the hidden input — a stale version 409s.
   */
  @Test
  void savesBlueprintSharingInPlaceThenDoubleSaveDoesNotConflict() {
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

        Locator checkbox =
            page.locator("#profile-blueprint-sharing-form input[name='shareBlueprintsGlobally']");
        Locator submit = page.locator("#profile-blueprint-sharing-form button[type='submit']");

        boolean initial = checkbox.isChecked();

        checkbox.setChecked(!initial);
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Blueprint-sharing save must update in place — no page reload on success.");
        assertEquals(!initial, persistedBlueprintSharing(), "the first in-place save must persist");

        checkbox.setChecked(initial);
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the second consecutive save must also stay in place — no reload cleared the marker");
        assertEquals(
            initial,
            persistedBlueprintSharing(),
            "the second consecutive save must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-blueprint-sharing-in-place");
        throw failure;
      }
    }
  }

  /**
   * Submits the blueprint-sharing form in place and asserts a success toast, no error toast and no
   * {@code OPTIMISTIC_LOCK} reload-confirm dialog ({@code .krt-confirm-overlay}).
   *
   * @param page the authenticated profile page
   * @param submit the blueprint-sharing form's submit button
   */
  private static void saveInPlace(Page page, Locator submit) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().endsWith("/profile/blueprint-sharing")
                && "POST".equals(response.request().method()),
        submit::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
    assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
  }

  /**
   * Reads the test user's currently persisted global blueprint-sharing flag straight from the
   * backend ({@code GET /api/v1/users/me/blueprint-sharing}, which returns {@code
   * shareBlueprintsGlobally} plus the user-row version), so the persistence assertion does not race
   * the client's in-place writeback.
   *
   * @return the persisted {@code shareBlueprintsGlobally} boolean
   */
  private static boolean persistedBlueprintSharing() {
    String body =
        new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/users/me/blueprint-sharing");
    return JsonParser.parseString(body)
        .getAsJsonObject()
        .get("shareBlueprintsGlobally")
        .getAsBoolean();
  }
}
