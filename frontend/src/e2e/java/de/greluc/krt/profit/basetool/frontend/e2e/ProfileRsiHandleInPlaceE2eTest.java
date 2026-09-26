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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the profile RSI-handle card (REQ-SEC-072) stores and clears the handle in place,
 * twice in a row without a stale-version conflict.
 */
@Tag("e2e")
class ProfileRsiHandleInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, ensures the test user's row exists. */
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
   * Stores a fresh handle in place, then clears it in place, asserting after each save the success
   * toast, the absence of a reload and the value the backend persisted.
   */
  @Test
  void storesAndClearsTheRsiHandleInPlace() {
    String baseUrl = STACK.baseUrl();
    String handle = "E2e_" + UUID.randomUUID().toString().substring(0, 8);
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

        Locator input = page.locator("[data-testid='profile-rsi-handle-input']");
        Locator submit = page.locator("[data-testid='profile-rsi-handle-save']");

        input.fill(handle);
        saveInPlace(page, submit);
        assertEquals(handle, persistedRsiHandle(), "the in-place save must persist the handle");

        input.fill("");
        saveInPlace(page, submit);
        assertEquals(null, persistedRsiHandle(), "saving an empty field must clear the handle");
        assertTrue(
            Boolean.TRUE.equals(page.evaluate("() => window.__krtNoReload === true")),
            "both saves must stay in place — no reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-rsi-handle-in-place");
        throw failure;
      }
    }
  }

  /**
   * Submits the RSI-handle form in place and asserts a success toast, no error toast and no
   * reload-confirm dialog.
   *
   * @param page the authenticated profile page
   * @param submit the RSI-handle form's submit button
   */
  private static void saveInPlace(Page page, Locator submit) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().endsWith("/profile/rsi-handle")
                && "POST".equals(response.request().method()),
        submit::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
    assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
  }

  /**
   * Reads the test user's persisted RSI handle straight from the backend.
   *
   * @return the stored handle, or {@code null} when none is stored
   */
  private static String persistedRsiHandle() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/users/me/rsi-handle");
    JsonElement handle = JsonParser.parseString(body).getAsJsonObject().get("rsiHandle");
    return handle == null || handle.isJsonNull() ? null : handle.getAsString();
  }
}
