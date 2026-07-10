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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
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
 * Guards the Materialbörse partial-offer amount field (REQ-MARKET-002, ADR-0086): a member may
 * release only a part of a Lager row, so the release/edit modal carries an editable "Menge
 * anbieten" number input alongside the read-only material/quality facts.
 *
 * <p>Only a real browser catches the field's dynamic wiring: in "new" (Material-anbieten) mode the
 * amount input has no ceiling until an item is picked, so {@code materialboerse-release.js}
 * disables it on open and only enables it (with the item's stock as {@code max}) once a picker row
 * is chosen. MockMvc render tests do not run that JS, so they cannot see the disabled-until-picked
 * state. This drives the board in a real engine and asserts the amount input is present and
 * disabled on a fresh "new" open — no inventory seed is required because the empty picker leaves
 * the field disabled, which is exactly the state under test.
 *
 * <p>The actor is {@code test-admin}, whose seeded IRIDIUM membership carries KRT_MEMBER, the role
 * the board requires.
 */
@Tag("e2e")
class MaterialboardOfferedAmountFieldE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the actor's IRIDIUM membership. */
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
   * Opens the release dialog and asserts the offered-amount input is visible and disabled until an
   * item is picked — the "new" mode's initial state that lets a member choose how much to offer.
   */
  @Test
  void releaseModalShowsOfferedAmountField() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materialboerse");
        page.waitForLoadState();
        page.waitForFunction("() => typeof window.krtMaterialRelease === 'object'");

        page.locator("[data-mb-open-release]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // The offered-amount field is part of the modal and starts disabled until a picker row sets
        // its ceiling — the partial-offer entry point (REQ-MARKET-002).
        assertThat(page.locator("#mb-modal [data-mb-amount]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mb-modal [data-mb-amount]"))
            .isDisabled(new LocatorAssertions.IsDisabledOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-offered-amount-field");
        throw failure;
      }
    }
  }
}
