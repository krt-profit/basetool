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
 * Regression for the Materialbörse Gesuche (requests) surface (REQ-MARKET-015…): switching to a
 * Gesuche tab relabels the CTAs, and the request create modal opens <em>genuinely visible</em> —
 * the same {@code .krt-modal-overlay} display:flex contract the offer release modal follows, which
 * only a real browser verifies (MockMvc render tests do not evaluate CSS).
 *
 * <p>Drives the board in a real engine and asserts: the shared four-tab bar carries the two Gesuche
 * tabs; clicking "Alle Gesuche" reveals the "Material suchen" / "Item suchen" CTAs (and hides the
 * offer CTAs); clicking "Material suchen" opens the request modal (hidden on load, visible after
 * the click); the catalogue picker dropdown stays closed on open and reveals on a click; the
 * Material/ Item kind radio toggles the material vs blueprint-product combobox; and the min-quality
 * + desired- quantity fields are present for both kinds. No inventory seed is needed — a request
 * has no backing Lager row, and the picker shows an empty-notice row without one.
 *
 * <p>The actor is {@code test-admin}, whose seeded IRIDIUM membership carries KRT_MEMBER, the role
 * the board requires.
 */
@Tag("e2e")
class MaterialboardRequestModalE2eTest {

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
   * Switches to the Gesuche board and asserts the request create modal opens visibly with a working
   * kind toggle and the min-quality + quantity fields.
   */
  @Test
  void switchingToGesucheShowsRequestModal() {
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
        page.waitForFunction("() => typeof window.krtMaterialRequest === 'object'");

        // The request modal is hidden on load (the .krt-modal-overlay global default is
        // display:none).
        assertThat(page.locator("#mg-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        // Switch to the Gesuche board via the shared tab bar.
        page.locator("[data-mb-mode='requests'][data-mb-tab='alle']").first().click();

        // The CTAs relabel: the request CTAs appear, the offer CTAs hide.
        assertThat(page.locator("[data-mg-open-request]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("[data-mb-open-release]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        // Open the request create modal — it must be genuinely visible, not just un-hidden.
        page.locator("[data-mg-open-request]").first().click();
        assertThat(page.locator("#mg-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // MATERIAL kind (default): the material combobox shows, the item combobox is hidden, and
        // the
        // picker dropdown stays closed on open.
        assertThat(page.locator("#mg-modal [data-mg-material-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-item-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-picker-list]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        page.locator("#mg-modal [data-mg-picker-input]").click();
        assertThat(page.locator("#mg-modal [data-mg-picker-list]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // The optional min-quality and the desired-quantity fields are present for a material
        // request.
        assertThat(page.locator("#mg-modal [data-mg-min-quality]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-qty]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // Switching the kind radio to Item toggles the combobox to the blueprint-product picker.
        page.locator("#mg-modal [data-mg-kind-radio][value='ITEM']").check();
        assertThat(page.locator("#mg-modal [data-mg-item-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-material-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        // Dismiss and confirm it hides again.
        page.locator("#mg-modal [data-mg-modal-close]").first().click();
        assertThat(page.locator("#mg-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-request-modal");
        throw failure;
      }
    }
  }
}
