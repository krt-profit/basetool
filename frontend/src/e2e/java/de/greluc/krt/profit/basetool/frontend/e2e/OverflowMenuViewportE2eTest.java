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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * An overflow menu whose toggle sits mid-row on a phone stays inside the viewport (REQ-UI-027): the
 * order detail's „⋯" menu opens below its toggle without overrunning either screen edge.
 */
@Tag("e2e")
class OverflowMenuViewportE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The phone class's narrowest viewport width, in CSS pixels. */
  private static final int PHONE_WIDTH = 375;

  /** The phone class's viewport height, in CSS pixels. */
  private static final int PHONE_HEIGHT = 812;

  private static Playwright playwright;
  private static Browser browser;
  private static String orderId;

  /** Launches the browser and creates an order whose menu holds the transfer and delete items. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String materialId =
        seeder.ensureJobOrderMaterial(
            USERNAME,
            PASSWORD,
            "Menu Viewport E2E " + UUID.randomUUID().toString().substring(0, 8));
    orderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, IRIDIUM_ID, "menu-viewport", materialId, 650, 5.0);
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

  /** Opens the order menu on a phone and checks that the panel lies within the viewport. */
  @Test
  void theOrderMenuStaysInsideAPhoneViewport() {
    assumeTrue(STACK.managesStack(), "needs the seeded order of the ephemeral stack");
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + orderId);
        page.waitForLoadState();
        page.locator("#order-menu-toggle").click();
        Locator panel = page.locator("#order-menu");
        assertThat(panel).isVisible();
        assertEquals(
            Boolean.TRUE,
            panel.evaluate(
                "(el) => { const p = el.getBoundingClientRect();"
                    + " return p.left >= 0"
                    + " && p.right <= document.documentElement.clientWidth; }"),
            "the order menu must not overrun either edge of a phone viewport");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "overflow-menu-viewport");
        throw failure;
      }
    }
  }
}
