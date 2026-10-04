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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Asserts that a remote picker whose session is gone sends the member to the login instead of
 * silently showing no matches (REQ-FE-031, REQ-SEC-012).
 *
 * <p>Runs on the {@code /admin/personal-inventory} member picker as {@code test-admin}; the session
 * is lost by clearing every cookie of the browser context, the Keycloak ones included.
 */
@Tag("e2e")
class PickerSessionLossE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The picker's remote source, {@code krt-user-search.js}'s {@code remote-users}. */
  private static final String SEARCH_PATH = "/users/search";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds the actor's membership. */
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
   * Loses the session, types into the picker, and asserts that the search is answered with the
   * re-authentication challenge and that the page then shows the Keycloak login form.
   */
  @Test
  void pickerAfterSessionLossSendsTheMemberToTheLogin() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/personal-inventory");
        page.waitForLoadState();
        Locator textbox =
            page.locator(".krt-combobox:has(#krt-pi-user-select) .krt-combobox__input");
        textbox.waitFor();

        context.clearCookies();

        Response search =
            page.waitForResponse(
                response -> response.url().contains(SEARCH_PATH), () -> textbox.fill("test"));
        assertEquals(401, search.status(), "a search without a session must be challenged");
        assertNotNull(
            search.headerValue("x-reauthenticate"),
            "the challenge must name the re-authentication path");

        page.locator("#kc-form-login").waitFor();
        assertTrue(
            page.locator("#username").isVisible(),
            "the picker must hand the member to the Keycloak login, not show an empty list");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "picker-session-loss");
        throw failure;
      }
    }
  }
}
