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
 * End-to-end coverage for a <b>stock-backed</b> Materialbörse item offer (REQ-MARKET-014,
 * ADR-0108): releasing one of the caller's own game-item Lager rows from the "Material anbieten"
 * picker creates an ITEM offer that appears on the board.
 *
 * <p>The board release picker now returns both material and game-item rows ({@code
 * findReleasableForUser} dropped its material-only guard); picking a game-item row and submitting a
 * whole-unit quantity posts the ordinary {@code /materialboerse/offers/ajax} payload, and the
 * backend detects the game-item row and creates a stock-backed item offer bound to it (its product
 * key/name derived from the row's game item). This drives the whole flow in a real browser and
 * asserts the item offer surfaces on the board with its "Item" kind tag.
 *
 * <p><b>Seeding.</b> A bookable game item is the output of an active blueprint (REQ-INV-029),
 * seeded via {@link BackendSeeder#seedOrderableItem}; the game-item stock row is seeded through the
 * real {@code POST /api/v1/inventory} with a {@code gameItemId} payload ({@link
 * BackendSeeder#createItemInventoryEntry}). The actor is {@code test-admin} (seeded IRIDIUM
 * membership → KRT_MEMBER, the role the board requires).
 */
@Tag("e2e")
class MaterialboardItemStockOfferE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String ITEM_NAME = "E2E Boerse Item Stock Widget";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the actor's membership, a blueprint-
   * bearing game item and a game-item stock row of it at a fresh location.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Boerse Item Hub");
    String ingredientMatId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Boerse Item Ingredient");
    String gameItemId = seeder.seedOrderableItem(ITEM_NAME, ingredientMatId);
    seeder.createItemInventoryEntry(USERNAME, PASSWORD, gameItemId, locationId, 20);
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
   * Opens the board, releases the seeded game-item Lager row via "Material anbieten" (picking the
   * item in the picker, offering 5 units), and asserts a stock-backed item offer for it — an offer
   * showing the item name and the "Item" kind tag — is on the board.
   */
  @Test
  void releasingGameItemRowCreatesItemOfferOnBoard() {
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
        Locator pickerInput = page.locator("#mb-modal [data-mb-picker-input]");
        assertThat(pickerInput)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // The picker defaults to Material (REQ-MARKET-002); pick the Item kind so the stock-backed
        // game-item rows are the ones the combobox lists.
        page.locator("#mb-modal [data-mb-kind-radio][value=\"ITEM\"]").check();

        // Type the item name and gate on the debounced /releasable-items?q=… query it fires,
        // so the picker list has settled before we touch it. The picker REPLACES the
        // [data-mb-picker-list] innerHTML when that response renders; clicking an option
        // mid-replacement detaches it, and the click retried to its 10s timeout on the
        // timing-sensitive Firefox shard (chromium/webkit won the race). Waiting for the
        // filtered response means the render has run and — via the JS pickerSeq guard that
        // drops any late initial-query response — no further re-render follows, so the
        // resolved option is stable when clicked. The modal-open query carries no q=, so the
        // predicate matches only the typed search.
        page.waitForResponse(
            response ->
                response.url().contains("/materialboerse/releasable-items")
                    && response.url().contains("q="),
            () -> pickerInput.fill(ITEM_NAME));
        Locator option =
            page.locator("#mb-modal [data-mb-picker-list] .krt-combobox__option")
                .filter(new Locator.FilterOptions().setHasText(ITEM_NAME))
                .first();
        assertThat(option).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        option.click();

        // Picking a game-item row enables the amount field (whole units); offer 5 of the 20 in
        // stock.
        Locator amount = page.locator("#mb-modal [data-mb-amount]");
        assertThat(amount).isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(10_000));
        amount.fill("5");

        page.locator("#mb-modal [data-mb-modal-submit]").click();
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        // The board now carries the stock-backed item offer: the item name and its "Item" kind tag.
        assertThat(page.getByText(ITEM_NAME).first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator(".mb-kind-tag").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-item-stock-offer");
        throw failure;
      }
    }
  }
}
