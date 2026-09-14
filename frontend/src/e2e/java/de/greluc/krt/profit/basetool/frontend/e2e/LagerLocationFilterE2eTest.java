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
 * Browser flow for the Lager location filter (REQ-INV-040) on "Mein Lager".
 *
 * <p>The server half — the relayed {@code locationIds}, the narrowed stacks and the in-scope-only
 * option list — is pinned by {@code InventoryItemStackQueryDataTest}, {@code
 * InventoryItemControllerTest} and {@code InventoryPageControllerMvcTest}. What only a browser can
 * show is the part in between: that ticking one location actually re-swaps the grouped table **in
 * place** (no navigation), that the stack at the other location disappears while its option stays
 * selectable, and that the collapsed panel's active-filter chip counts the new dimension.
 *
 * <p>The no-reload assertion is a window marker set before the click and read afterwards: a
 * full-page navigation would wipe it, and REQ-FE-001…010 forbid one on a filter change.
 */
@Tag("e2e")
class LagerLocationFilterE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MATERIAL_NAME = "E2E Location Filter Material";
  private static final String LOCATION_A = "E2E Location Filter Hub A";
  private static final String LOCATION_B = "E2E Location Filter Hub B";

  /**
   * The grouped Lager render is slow on WebKit under CI load, so allow more than the 5 s default.
   */
  private static final double RENDER_TIMEOUT_MS = 20_000;

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;
  private static String locationAId;
  private static String locationBId;

  /**
   * Launches the browser and seeds one material held at two locations, so the grouped tree carries
   * two stacks under a single material group — the shape the filter has to cut in half.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      materialId = seeder.createRefineryMaterial(USERNAME, PASSWORD, MATERIAL_NAME);
      locationAId = seeder.createLocation(USERNAME, PASSWORD, LOCATION_A);
      locationBId = seeder.createLocation(USERNAME, PASSWORD, LOCATION_B);
      seeder.createInventoryItem(USERNAME, PASSWORD, materialId, locationAId, 800, 100.0);
      seeder.createInventoryItem(USERNAME, PASSWORD, materialId, locationBId, 800, 50.0);
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
   * Ticks one of the two seeded locations and asserts the grouped table keeps that location's
   * stack, drops the other one, counts the dimension on the filter toggle, and never navigates
   * away.
   */
  @Test
  void pickingOneLocationNarrowsTheGroupedTableInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/inventory/my");
        page.waitForLoadState();

        // The tree starts collapsed, so open the seeded material's group: its two location stacks
        // are the rows the filter has to cut down to one.
        assertThat(page.locator("div.tree-row--group[data-material-id='" + materialId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        page.locator("div.tree-row--group[data-material-id='" + materialId + "']").click();
        assertThat(page.locator("div.tree-row--mid[data-location-id='" + locationAId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("div.tree-row--mid[data-location-id='" + locationBId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));

        // Every filter panel starts collapsed (REQ-FE-021), so open it before picking.
        if (page.locator("#myFilterPanel").isHidden()) {
          page.locator("[data-testid='lager-filter-toggle']").click();
        }
        assertThat(page.locator("#myFilterPanel")).isVisible();

        // Survives an in-place fragment swap, but not a navigation — this is the no-reload probe.
        page.evaluate("() => { window.__e2eNoReloadMarker = 'kept'; }");

        page.locator("#locationHeader").click();
        page.locator("#locationOptions input.locCheck[value='" + locationAId + "']").check();

        // The swapped-in table keeps only the picked location's stack. Counted, not "hidden":
        // the excluded stack must be gone from the server-rendered fragment altogether, which a
        // visibility assertion would also accept from a merely collapsed row.
        assertThat(page.locator("div.tree-row--mid[data-location-id='" + locationBId + "']"))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("div.tree-row--mid[data-location-id='" + locationAId + "']"))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(RENDER_TIMEOUT_MS));

        // One dimension narrows the view, and the chip that keeps a collapsed panel honest says so.
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("1");

        // The excluded location must remain offerable, or the filter would be a one-way door.
        assertThat(page.locator("#locationOptions input.locCheck[value='" + locationBId + "']"))
            .isAttached();

        org.junit.jupiter.api.Assertions.assertEquals(
            "kept",
            page.evaluate("() => window.__e2eNoReloadMarker"),
            "a filter change must swap the table in place, never reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "lager-location-filter");
        throw failure;
      }
    }
  }
}
