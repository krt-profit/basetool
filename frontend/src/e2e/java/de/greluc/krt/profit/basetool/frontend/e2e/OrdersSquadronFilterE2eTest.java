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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the orders-overview multi-squadron filter (REQ-ORDERS-027): the former mine/all scope
 * toggle is now a localStorage-backed multi-select dropdown of every active squadron, applied
 * SERVER-side (matching an order's responsible OR requesting side) and re-applied on reload.
 *
 * <p>The dropdown is populated from the frontend's <b>squadron catalogue cache</b> (2-hour TTL), so
 * a squadron created mid-suite is not offered as a checkbox — the fixture therefore keys on the
 * bootstrap-cached IRIDIUM squadron, which is always present. It seeds one OPEN order responsible
 * to IRIDIUM, deselects IRIDIUM in the filter, and asserts that order drops out of the queue — then
 * reloads and asserts the deselection survives from localStorage without any manual re-filtering.
 * The absence assertion keys on the order's own {@code data-id} and holds regardless of pagination
 * (a filtered-out order is on no page); the initial visibility check uses {@code size=200} so the
 * freshly seeded order is on the first page. The actor is {@code test-admin} (an IRIDIUM member).
 */
@Tag("e2e")
class OrdersSquadronFilterE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Canonical IRIDIUM squadron id — bootstrap-seeded, so always present in the cached dropdown. */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Large page so the freshly seeded order lands on the first page for the initial check. */
  private static final String QUEUE_URL_SUFFIX = "/orders?status=OPEN&size=200";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * An OPEN order whose responsible (and requesting) unit is IRIDIUM — hidden once IRIDIUM is off.
   */
  private static String iridiumOrderId;

  /** Launches the browser and seeds one IRIDIUM-responsible order for the filter to act on. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Filter Material");
      iridiumOrderId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, IRIDIUM_ID, "E2E Filter IRI Order", materialId, 650, 25);
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
   * Deselects IRIDIUM from the multi-select filter (its order disappears), then reloads and asserts
   * the deselection is restored from localStorage and re-applied without any manual interaction.
   */
  @Test
  void deselectingASquadronHidesItsOrdersAndPersistsAcrossReload() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded IRIDIUM order");
    String baseUrl = STACK.baseUrl();
    java.nio.file.Path storageState =
        E2eSupport.authenticatedStorageState(browser, baseUrl, ADMIN_USER, ADMIN_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + QUEUE_URL_SUFFIX);
        page.waitForLoadState();
        assertThat(page.getByTestId("nav-logout")).isVisible();
        // The multi-select renders for the full queue (admin is not the requester-only view), and
        // with every squadron checked (the default) the IRIDIUM order is visible.
        assertThat(page.locator("#squadronFilterContainer")).isVisible();
        assertVisible(page, iridiumOrderId);

        // Deselect IRIDIUM: a single checkbox change fires one server-side re-fetch (no rapid
        // multi-swap race), dropping the IRIDIUM order from the queue.
        page.locator("#squadronHeader").click();
        page.locator("input.sqCheck[value='" + IRIDIUM_ID + "']").uncheck();
        assertAbsent(page, iridiumOrderId);

        // The deselection persists: a full reload restores it from localStorage and re-applies it,
        // so the IRIDIUM order stays hidden without any manual re-filtering.
        E2eSupport.navigate(page, baseUrl + QUEUE_URL_SUFFIX);
        page.waitForLoadState();
        assertAbsent(page, iridiumOrderId);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-squadron-filter");
        throw failure;
      }
    }
  }

  /**
   * Asserts the order row with the given id is visible, allowing for the slow WebKit list render
   * and the results-fragment swap that a filter change triggers.
   *
   * @param page the page showing the orders overview
   * @param orderId the id of the order whose row must be present
   */
  private static void assertVisible(Page page, String orderId) {
    assertThat(page.locator("[data-testid='order-row'][data-id='" + orderId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Asserts no order row with the given id is present — the row the active squadron filter
   * excludes. Absence holds regardless of pagination, since a filtered-out order appears on no
   * page.
   *
   * @param page the page showing the orders overview
   * @param orderId the id of the order whose row must be absent
   */
  private static void assertAbsent(Page page, String orderId) {
    assertThat(page.locator("[data-testid='order-row'][data-id='" + orderId + "']"))
        .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
  }
}
