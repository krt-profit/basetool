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
 * Regression for the Materialbörse release modal opening <em>invisibly</em> (REQ-MARKET-002/007).
 *
 * <p>The shared release/edit modal is a {@code .krt-modal-overlay}, whose global default in {@code
 * styles.css} is {@code display:none}; the app-wide contract is that a modal is opened by setting
 * an inline {@code display:flex}, NOT by clearing a {@code hidden} attribute. The first cut of
 * {@code materialboerse-release.js} opened with {@code modal.hidden = false}, which removed the
 * attribute but left the CSS {@code display:none} in place — so clicking "Material anbieten" (and
 * the Mein-Lager "Für Börse freigeben" checkbox) ran the open logic and even fetched the picker,
 * yet the user saw nothing and could never submit, so no offer was ever created. Only a real
 * browser catches this: MockMvc render tests do not evaluate CSS, so the modal markup looked
 * correct.
 *
 * <p>This drives the board in a real engine and asserts the modal is hidden on load (also guarding
 * the inverse "modal renders open on load" regression the {@code styles.css} default protects
 * against), becomes genuinely visible after the CTA click, and hides again when dismissed —
 * Playwright's visibility checks resolve a {@code display:none} element as hidden, which is exactly
 * the discriminator that turns this red on the pre-fix frontend.
 *
 * <p>The actor is {@code test-admin}, whose seeded IRIDIUM membership carries KRT_MEMBER, the role
 * the board requires. No inventory seed is needed: the "new" release dialog opens with an empty
 * picker, so the modal's visibility is exercised without any releasable Lager posten.
 */
@Tag("e2e")
class MaterialboardReleaseModalOpensE2eTest {

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
   * Opens the board, clicks "Material anbieten", and asserts the release modal actually appears on
   * screen (not merely present in the DOM), then closes and asserts it disappears. Pre-fix the
   * modal stayed {@code display:none} despite the {@code hidden} attribute being cleared, so the
   * visibility assertion after the click fails.
   */
  @Test
  void clickingOfferCtaShowsReleaseModal() {
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
        // Both deferred scripts must have wired up: the board's delegated click handler and the
        // shared release modal's window.krtMaterialRelease. waitForLoadState() already guarantees
        // deferred scripts ran, but assert the seam explicitly so a click can never land early.
        page.waitForFunction("() => typeof window.krtMaterialRelease === 'object'");

        // Closed on load: the .krt-modal-overlay global default is display:none.
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        page.locator("[data-mb-open-release]").first().click();

        // The regression discriminator: the modal must be genuinely visible, not just un-hidden.
        assertThat(page.locator("#mb-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        // "new" mode reveals the material picker so the user can choose a releasable posten.
        assertThat(page.locator("#mb-modal [data-mb-picker]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // Dismiss via the header close button and confirm it hides again (display toggles back).
        page.locator("#mb-modal [data-mb-modal-close]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-release-modal");
        throw failure;
      }
    }
  }
}
