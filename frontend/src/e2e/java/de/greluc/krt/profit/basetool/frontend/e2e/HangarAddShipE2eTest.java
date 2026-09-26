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
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Adds a ship to the hangar through the UI and verifies it appears in the ship list. The ship type
 * comes from the catalog seed in {@link E2eStackExtension}; {@link BackendSeeder} seeds the IRIDIUM
 * membership in {@link #setUp()}.
 */
@Tag("e2e")
class HangarAddShipE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership. */
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
   * Adds a ship through the modal and asserts it appears in the hangar list without a page reload
   * (REQ-FE-001).
   */
  @Test
  void addsAShipThroughTheUiInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar");
        page.getByTestId("hangar-add-ship").click();

        page.locator("#ship-type").selectOption(new SelectOption().setLabel("E2E Ship Type"));
        page.locator("#ship-insurance").selectOption("LTI");

        page.evaluate("window.__krtNoReload = true;");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        page.waitForResponse(
            r -> r.url().contains("/hangar/add") && "POST".equals(r.request().method()),
            () -> page.getByTestId("hangar-ship-submit").click());

        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "adding a ship must not reload the page");
        assertThat(
                page.getByTestId("hangar-ship-row")
                    .filter(new Locator.FilterOptions().setHasText("E2E Ship Type")))
            .isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "hangar-add-ship");
        throw failure;
      }
    }
  }
}
