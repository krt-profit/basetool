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
 * Regression for the shared Materialbörse modal showing <em>both</em> quantity fields at once
 * (REQ-MARKET-002/012).
 *
 * <p>The release/edit modal ({@code fragments/materialboerse-modal.html}) carries two mutually
 * exclusive quantity inputs: the material offered-amount block ({@code [data-mb-amount-block]},
 * "Menge anbieten", SCU/Stück) shown for a material release/edit, and the item-quantity block
 * ({@code [data-mb-qty-block]}, "Menge (Stück)") shown only for a craftable-item offer. {@code
 * materialboerse-release.js} toggles them via the {@code hidden} attribute (mode {@code 'item'} →
 * item block, otherwise → amount block).
 *
 * <p>Because {@code .mb-modal-qty} carries an author {@code display:flex} rule, the UA {@code
 * [hidden] { display:none }} rule lost to it (equal specificity, author wins), so the item-quantity
 * block stayed on screen even though the JS had set {@code hidden} — every material release (the
 * Mein-Lager "Für Börse freigeben" checkbox and the board "Material anbieten" CTA) showed a stray
 * second "Menge (Stück)" field. The fix is the {@code .mb-modal-qty[hidden]} guard in {@code
 * materialboerse.css}. Only a real browser catches this: MockMvc render tests do not evaluate CSS,
 * so the {@code hidden} attribute alone looked correct.
 *
 * <p>This drives the board in a real engine and asserts that a material release shows the amount
 * field and hides the item-quantity field, while an item offer does the inverse — pinning the
 * exclusivity in both directions so neither the shipped leak nor an over-broad fix (that hides the
 * item field unconditionally) can regress unnoticed.
 *
 * <p>The actor is {@code test-admin}, whose seeded IRIDIUM membership carries KRT_MEMBER, the role
 * the board requires. No inventory or blueprint seed is needed: both dialogs open with an empty
 * picker, so the quantity-block visibility is exercised without any releasable posten or product.
 */
@Tag("e2e")
class MaterialboardQuantityFieldExclusivityE2eTest {

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
   * Opens the board and asserts that "Material anbieten" reveals only the offered-amount field (the
   * item-quantity field stays hidden), then that "Item anbieten" reveals only the item-quantity
   * field (the offered-amount field stays hidden). Pre-fix the item-quantity block stayed {@code
   * display:flex} despite its {@code hidden} attribute, so it was visible during the material
   * release — the first {@code isHidden} assertion fails.
   */
  @Test
  void quantityFieldsAreMutuallyExclusivePerOfferKind() {
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

        // Material release: amount field shown, item-quantity field hidden (the shipped bug).
        page.locator("[data-mb-open-release]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mb-modal [data-mb-amount-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        // The regression discriminator: pre-fix this block was visible because the author
        // .mb-modal-qty display:flex outranked the UA [hidden] { display:none } rule.
        assertThat(page.locator("#mb-modal [data-mb-qty-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        page.locator("#mb-modal [data-mb-modal-close]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        // Item offer: item-quantity field shown, amount field hidden (the inverse).
        page.locator("[data-mb-open-item]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mb-modal [data-mb-qty-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mb-modal [data-mb-amount-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-quantity-field-exclusivity");
        throw failure;
      }
    }
  }
}
