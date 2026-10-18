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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * E2E flow for the Lager item-attribute filters (REQ-INV-063): picking component type
 * "Quantenantrieb" and size S2, or class "Militär" and size S2, narrows the items view to the S2
 * quantum drive in place, on both Mein Lager and Globales Lager, shows two chips and counts them on
 * the toggle, survives a revisit through the stored selection, feeds "Alle markieren", and "Alle
 * zurücksetzen" brings the other items back.
 */
@Tag("e2e")
class LagerItemAttributeFilterE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** How long a grouped Lager render may take before an assertion fails. */
  private static final double RENDER_TIMEOUT_MS = 20_000;

  private static Playwright playwright;
  private static Browser browser;
  private static String driveS2Id;
  private static String driveS1Id;
  private static String shieldS2Id;

  /**
   * Launches the browser and seeds three components in stock: a military S2 and S1 quantum drive
   * and a civilian S2 shield, so neither the type, the class nor the size alone isolates the S2
   * drive.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String ingredient =
          seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Attribute Filter Ingredient");
      String hub = seeder.createLocation(USERNAME, PASSWORD, "E2E Attribute Filter Hub");
      driveS2Id = seeder.seedOrderableItem("E2E Attribute Drive S2", ingredient);
      driveS1Id = seeder.seedOrderableItem("E2E Attribute Drive S1", ingredient);
      shieldS2Id = seeder.seedOrderableItem("E2E Attribute Shield S2", ingredient);
      seeder.makeShipComponent(driveS2Id, "QuantumDrive", 2, "A");
      seeder.makeShipComponent(driveS1Id, "QuantumDrive", 1, "C");
      seeder.makeShipComponent(shieldS2Id, "Shield", 2, "B");
      seeder.setItemClass(driveS2Id, "MILITARY");
      seeder.setItemClass(driveS1Id, "MILITARY");
      seeder.setItemClass(shieldS2Id, "CIVILIAN");
      seeder.createItemInventoryEntry(USERNAME, PASSWORD, driveS2Id, hub, 1);
      seeder.createItemInventoryEntry(USERNAME, PASSWORD, driveS1Id, hub, 1);
      seeder.createItemInventoryEntry(USERNAME, PASSWORD, shieldS2Id, hub, 1);
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
   * Ticks "Quantenantrieb" and S2 and asserts the items view keeps only the S2 drive, counts both
   * filters on the toggle, never reloads, and lists every item again after "Alle zurücksetzen".
   *
   * @param path the Lager page
   * @param panelId the page's filter popover
   */
  @ParameterizedTest
  @CsvSource({
    "/inventory/my?view=items, myFilterPanel",
    "/inventory/all?view=items, globalFilterPanel"
  })
  void componentTypeAndSizeNarrowTheItemsViewInPlace(String path, String panelId) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();

        assertThat(group(page, driveS2Id))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS1Id)).isVisible();
        assertThat(group(page, shieldS2Id)).isVisible();

        if (page.locator("#" + panelId).isHidden()) {
          page.locator("[data-testid='lager-filter-toggle']").click();
        }
        assertThat(page.locator("#" + panelId)).isVisible();
        page.evaluate("() => { window.__e2eNoReloadMarker = 'kept'; }");

        page.locator("#componentTypeHeader").click();
        page.locator("#componentTypeOptions input.componentTypeCheck[value='QUANTUM_DRIVE']")
            .check();
        assertThat(group(page, shieldS2Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));

        page.locator("#sizeHeader").click();
        page.locator("#sizeOptions input.sizeCheck[value='2']").check();
        assertThat(group(page, driveS1Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS2Id)).hasCount(1);
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("2");
        assertThat(page.locator("#sizeOptions input.sizeCheck[value='1']")).isAttached();

        Assertions.assertEquals(
            "kept",
            page.evaluate("() => window.__e2eNoReloadMarker"),
            "a filter change must swap the table in place, never reload the page");

        assertThat(page.locator("[data-testid='filter-chip-componentTypeFilter']")).isVisible();
        assertThat(page.locator("[data-testid='filter-chip-sizeFilter']")).isVisible();

        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();
        assertThat(group(page, driveS1Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS2Id))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("2");

        if (page.locator("#bulkSelectAllBtn").count() > 0) {
          page.waitForRequest(
              request ->
                  request.url().contains("/inventory/my/entry-ids")
                      && request.url().contains("componentTypes=QUANTUM_DRIVE")
                      && request.url().contains("sizes=2"),
              () -> page.locator("#bulkSelectAllBtn").click());
          assertThat(page.locator("#bulkSelectAllBtn")).hasAttribute("data-state", "on");
          page.locator("#bulkSelectAllBtn").click();
        }

        page.locator("[data-filter-chips-reset]").click();
        assertThat(group(page, shieldS2Id))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS1Id)).hasCount(1);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "lager-item-attribute-filter");
        throw failure;
      }
    }
  }

  /**
   * Ticks "Militär" and S2 and asserts the items view keeps only the military S2 drive, counts both
   * filters on the toggle, restores them on a revisit, feeds "Alle markieren", never reloads, and
   * lists every item again after "Alle zurücksetzen".
   *
   * @param path the Lager page
   * @param panelId the page's filter popover
   */
  @ParameterizedTest
  @CsvSource({
    "/inventory/my?view=items, myFilterPanel",
    "/inventory/all?view=items, globalFilterPanel"
  })
  void itemClassAndSizeNarrowTheItemsViewInPlace(String path, String panelId) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();

        assertThat(group(page, shieldS2Id))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));

        if (page.locator("#" + panelId).isHidden()) {
          page.locator("[data-testid='lager-filter-toggle']").click();
        }
        assertThat(page.locator("#" + panelId)).isVisible();
        page.evaluate("() => { window.__e2eNoReloadMarker = 'kept'; }");

        page.locator("#itemClassHeader").click();
        page.locator("#itemClassOptions input.itemClassCheck[value='MILITARY']").check();
        assertThat(group(page, shieldS2Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS1Id)).hasCount(1);
        assertThat(group(page, driveS2Id)).hasCount(1);

        page.locator("#sizeHeader").click();
        page.locator("#sizeOptions input.sizeCheck[value='2']").check();
        assertThat(group(page, driveS1Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS2Id)).hasCount(1);
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("2");
        assertThat(page.locator("[data-testid='filter-chip-itemClassFilter']")).isVisible();

        Assertions.assertEquals(
            "kept",
            page.evaluate("() => window.__e2eNoReloadMarker"),
            "a filter change must swap the table in place, never reload the page");

        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();
        assertThat(group(page, driveS1Id))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS2Id))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("2");

        if (page.locator("#bulkSelectAllBtn").count() > 0) {
          page.waitForRequest(
              request ->
                  request.url().contains("/inventory/my/entry-ids")
                      && request.url().contains("itemClasses=MILITARY")
                      && request.url().contains("sizes=2"),
              () -> page.locator("#bulkSelectAllBtn").click());
          assertThat(page.locator("#bulkSelectAllBtn")).hasAttribute("data-state", "on");
          page.locator("#bulkSelectAllBtn").click();
        }

        page.locator("[data-filter-chips-reset]").click();
        assertThat(group(page, shieldS2Id))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(group(page, driveS1Id)).hasCount(1);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "lager-item-class-filter");
        throw failure;
      }
    }
  }

  private static Locator group(Page page, String gameItemId) {
    return page.locator("div.tree-row--group[data-game-item-id='" + gameItemId + "']");
  }
}
