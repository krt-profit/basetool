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
 * E2E flow for a stock-backed Materialbörse item offer (REQ-MARKET-014, ADR-0108): releasing an own
 * game-item Lager row from the "Material anbieten" picker creates an ITEM offer shown on the board.
 *
 * <p>Seeds an orderable game item and a stock row for it; runs as {@code test-admin}.
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

        page.locator("#mb-modal [data-mb-kind-radio][value=\"ITEM\"]").check();

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

        Locator amount = page.locator("#mb-modal [data-mb-amount]");
        assertThat(amount).isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(10_000));
        amount.fill("5");

        page.locator("#mb-modal [data-mb-modal-submit]").click();
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

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
