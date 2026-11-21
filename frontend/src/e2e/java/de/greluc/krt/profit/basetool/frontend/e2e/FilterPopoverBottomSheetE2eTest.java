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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The toolbar's filter popover opens as a bottom sheet above the tab bar on a phone (REQ-UI-027):
 * on the order list it spans the viewport's width, ends where the tab bar begins and shows its
 * first filter in full.
 */
@Tag("e2e")
class FilterPopoverBottomSheetE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The phone class's narrowest viewport width, in CSS pixels. */
  private static final int PHONE_WIDTH = 375;

  /** The phone class's viewport height, in CSS pixels. */
  private static final int PHONE_HEIGHT = 812;

  /** Sub-pixel slack for edges compared across layout boxes, in CSS pixels. */
  private static final double EDGE_SLACK = 1.0;

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, on the ephemeral stack, gives the account its IRIDIUM membership. */
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
   * Opens the order list's filter on a phone and checks that the panel is a full-width sheet
   * resting on the tab bar, with the type filter inside its visible area.
   */
  @Test
  void theOrderFilterOpensAsABottomSheetOnAPhone() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                .setIsMobile(true)
                .setHasTouch(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.getByTestId("orders-filter-toggle").click();
        Locator panel = page.locator("#orders-filter-panel");
        Locator tabbar = page.locator(".mobile-tabbar");
        Locator typeFilter = page.locator("#orders-type-filter");
        assertThat(panel).isVisible();
        assertThat(tabbar).isVisible();
        assertThat(typeFilter).isVisible();

        BoundingBox sheet = panel.boundingBox();
        double viewportWidth =
            ((Number) page.evaluate("() => document.documentElement.clientWidth")).doubleValue();
        assertEquals(0, sheet.x, EDGE_SLACK, "the filter sheet must start at the viewport's edge");
        assertEquals(
            viewportWidth,
            sheet.x + sheet.width,
            EDGE_SLACK,
            "the filter sheet must reach the viewport's other edge");
        assertEquals(
            tabbar.boundingBox().y,
            sheet.y + sheet.height,
            EDGE_SLACK,
            "the filter sheet must end where the tab bar begins");

        BoundingBox type = typeFilter.boundingBox();
        assertTrue(
            type.y >= sheet.y - EDGE_SLACK
                && type.y + type.height <= sheet.y + sheet.height + EDGE_SLACK,
            "the type filter must show in full inside the sheet, sheet was "
                + sheet.height
                + "px high");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "filter-popover-bottom-sheet");
        throw failure;
      }
    }
  }
}
