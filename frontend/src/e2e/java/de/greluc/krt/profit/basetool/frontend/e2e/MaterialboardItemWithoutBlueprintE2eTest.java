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
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E flows for Materialbörse item offers of an item no blueprint produces (REQ-MARKET-012,
 * REQ-MARKET-014, ADR-0372): a Lager row of such an item is released from the "Material anbieten"
 * picker, and the item is offered free-stated from the "Item anbieten" picker.
 *
 * <p>Seeds two FPS weapons without a blueprint, one with a stock row; runs as {@code test-admin}.
 */
@Tag("e2e")
class MaterialboardItemWithoutBlueprintE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String STOCKED_ITEM = "E2E Boerse Railgun Stocked";
  private static final String LISTED_ITEM = "E2E Boerse Railgun Listed";
  private static final LocatorAssertions.IsVisibleOptions VISIBLE =
      new LocatorAssertions.IsVisibleOptions().setTimeout(10_000);

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the actor's membership, two weapons no
   * blueprint produces and a Lager row of the first at a fresh location.
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
    String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Boerse Railgun Hub");
    String stockedId = seeder.seedStockableItem(STOCKED_ITEM, "WeaponPersonal", "Large");
    seeder.seedStockableItem(LISTED_ITEM, "WeaponPersonal", "Large");
    seeder.createItemInventoryEntry(USERNAME, PASSWORD, stockedId, locationId, 3);
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
   * Releases the Lager row of the weapon without a blueprint from the "Material anbieten" picker
   * and asserts its item offer is on the board.
   */
  @Test
  void releasingARowWithoutBlueprintCreatesAnItemOffer() {
    onBoard(
        "materialboard-item-without-blueprint-stock",
        page -> {
          page.locator("[data-testid='mb-create-offer']").click();
          page.locator("[data-mb-open-release]").first().click();
          Locator pickerInput = page.locator("#mb-modal [data-mb-picker-input]");
          assertThat(pickerInput).isVisible(VISIBLE);
          page.locator("#mb-modal [data-mb-kind-radio][value=\"ITEM\"]").check();
          page.waitForResponse(
              response ->
                  response.url().contains("/materialboerse/releasable-items")
                      && response.url().contains("q="),
              () -> pickerInput.fill(STOCKED_ITEM));
          Locator option =
              page.locator("#mb-modal [data-mb-picker-list] .krt-combobox__option")
                  .filter(new Locator.FilterOptions().setHasText(STOCKED_ITEM))
                  .first();
          assertThat(option).isVisible(VISIBLE);
          option.click();
          Locator amount = page.locator("#mb-modal [data-mb-amount]");
          assertThat(amount).isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(10_000));
          amount.fill("2");
          submitAndExpectOnBoard(page, STOCKED_ITEM);
        });
  }

  /**
   * Picks the weapon without a blueprint in the "Item anbieten" picker, states a quantity, and
   * asserts the free-stated item offer is on the board.
   */
  @Test
  void offeringAnItemWithoutBlueprintFromTheItemPickerCreatesAnItemOffer() {
    onBoard(
        "materialboard-item-without-blueprint-listed",
        page -> {
          page.locator("[data-testid='mb-create-offer']").click();
          page.locator("[data-mb-open-item]").first().click();
          Locator pickerInput = page.locator("#mb-modal [data-mb-item-picker-input]");
          assertThat(pickerInput).isVisible(VISIBLE);
          page.waitForResponse(
              response ->
                  response.url().contains("/materialboerse/item-options")
                      && response.url().contains("q="),
              () -> pickerInput.fill(LISTED_ITEM));
          Locator option =
              page.locator("#mb-modal [data-mb-item-picker-list] .krt-combobox__option")
                  .filter(new Locator.FilterOptions().setHasText(LISTED_ITEM))
                  .first();
          assertThat(option).isVisible(VISIBLE);
          option.click();
          page.locator("#mb-modal [data-mb-item-qty]").fill("4");
          submitAndExpectOnBoard(page, LISTED_ITEM);
        });
  }

  /**
   * Opens the board as the actor, runs a flow on it and dumps the page when the flow fails.
   *
   * @param dumpName the name of the failure dump
   * @param flow the steps on the open board
   */
  private static void onBoard(String dumpName, Consumer<Page> flow) {
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
        flow.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, dumpName);
        throw failure;
      }
    }
  }

  /**
   * Submits the open offer dialog and waits for the item's offer on the board.
   *
   * @param page the board page with the dialog open
   * @param itemName the offered item's name
   */
  private static void submitAndExpectOnBoard(Page page, String itemName) {
    page.locator("#mb-modal [data-mb-modal-submit]").click();
    assertThat(page.locator("#mb-modal"))
        .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
    assertThat(
            page.locator(".master-row")
                .filter(new Locator.FilterOptions().setHasText(itemName))
                .first())
        .isVisible(VISIBLE);
  }
}
