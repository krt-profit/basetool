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
 * <p>The fixture seeds two fresh profit-eligible squadrons A and B, each responsible for exactly
 * one OPEN order. The flow starts with every squadron checked (the default), deselects squadron B,
 * and asserts B's order drops out of the queue while A's stays — then reloads and asserts the
 * deselection survives from localStorage without any manual re-filtering. The absence assertions
 * key on B's own {@code data-id} and hold regardless of pagination (a filtered-out order is on no
 * page); the initial visibility check uses {@code size=200} so the two freshly seeded orders are on
 * the first page. The actor is {@code test-admin}, whose cross-squadron visibility lets one session
 * see both orders.
 */
@Tag("e2e")
class OrdersSquadronFilterE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Large page so both freshly seeded orders land on the first page for the initial check. */
  private static final String QUEUE_URL_SUFFIX = "/orders?status=OPEN&size=200";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * The id of the fresh profit squadron B (responsible for {@link #orderBId}), the one deselected.
   */
  private static String squadronBId;

  /** An OPEN order whose responsible (and requesting) unit is squadron A — stays visible. */
  private static String orderAId;

  /** An OPEN order whose responsible (and requesting) unit is squadron B — filtered out. */
  private static String orderBId;

  /**
   * Seeds two fresh profit-eligible squadrons and one OPEN order responsible to each, so the
   * multi-select filter has two independently addressable squadrons to narrow between.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);

      String squadronAId =
          seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Filter A", "EFLA");
      seeder.setSquadronProfitEligible(ADMIN_USER, ADMIN_PASSWORD, squadronAId, true);
      squadronBId = seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Filter B", "EFLB");
      seeder.setSquadronProfitEligible(ADMIN_USER, ADMIN_PASSWORD, squadronBId, true);

      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Filter Material");
      orderAId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, squadronAId, "E2E Filter A Order", materialId, 650, 25);
      orderBId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, squadronBId, "E2E Filter B Order", materialId, 650, 25);
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
   * Deselects squadron B from the multi-select filter (its order disappears, A's stays), then
   * reloads and asserts the deselection is restored from localStorage and re-applied without any
   * manual interaction.
   */
  @Test
  void deselectingASquadronHidesItsOrdersAndPersistsAcrossReload() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded squadrons + orders");
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
        // with every squadron checked (the default) both seeded orders are visible.
        assertThat(page.locator("#squadronFilterContainer")).isVisible();
        assertVisible(page, orderAId);
        assertVisible(page, orderBId);

        // Deselect squadron B: a single checkbox change fires one server-side re-fetch (no rapid
        // multi-swap race), dropping B's order from the queue while A's stays.
        page.locator("#squadronHeader").click();
        page.locator("input.sqCheck[value='" + squadronBId + "']").uncheck();
        assertAbsent(page, orderBId);
        assertVisible(page, orderAId);

        // The deselection persists: a full reload restores it from localStorage and re-applies it,
        // so B's order stays hidden without any manual re-filtering.
        E2eSupport.navigate(page, baseUrl + QUEUE_URL_SUFFIX);
        page.waitForLoadState();
        assertAbsent(page, orderBId);
        assertVisible(page, orderAId);
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
