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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Live-sync coverage for the global job-order queue (#1102, REQ-FE-015 / ADR-0092): a viewer of the
 * {@code /orders} queue must gain a newly-created order IN PLACE, no manual reload — the {@code
 * queue} section key crossing the global {@code orders} room.
 *
 * <p>Unlike the per-resource tests this drives the <b>server-side</b> publish path: an order create
 * fans {@code orders/[queue]} to every subscribed viewer straight from {@code
 * JobOrderWriteController} (the path that also covers <em>anonymous guest</em> creates, which have
 * no socket to publish from), so seeding a create through the backend exercises exactly the
 * production fan-out a guest would trigger. A second browser context reordering rows drives the
 * identical queue receiver; the server-publish create is chosen here because it is deterministic
 * (no drag-and-drop flake) and covers the socket-less create path a client publish cannot. The
 * global {@code orders} room coalesces at ~1.5&nbsp;s, so the assertion carries a generous timeout.
 */
@Tag("e2e")
class JobOrderQueueLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * The canonical IRIDIUM Squadron seeded at stack bootstrap (a profit-eligible processing unit).
   */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String materialId;

  /**
   * Launches the browser and seeds IRIDIUM membership, a job-order material and one initial order.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E LiveSync Ore");
      seeder.createJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E seed", materialId, 650, 10);
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
   * A passive viewer on {@code /orders} that never reloads must gain a row when a new order is
   * created server-side, driven purely by the {@code orders/[queue]} change signal.
   */
  @Test
  void orderCreatePropagatesToTheQueueViewerLive() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext contextB =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(
                    E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD)))) {
      Page pageB = contextB.newPage();
      try {
        E2eSupport.navigate(pageB, baseUrl + "/orders");
        pageB.waitForLoadState();

        int before = pageB.locator("#orders-results tr[data-id]").count();

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: B is registered with the relay once its `orders` subscribe is acked.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        // Create a new order server-side — JobOrderWriteController fans orders/[queue] to the room.
        seeder.createJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E live", materialId, 650, 5);

        // The assertion under test: B's queue gains the new row in place (global room coalesces at
        // ~1.5 s, so allow a generous window), without a full-page reload.
        pageB.waitForCondition(
            () -> pageB.locator("#orders-results tr[data-id]").count() == before + 1,
            new Page.WaitForConditionOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageB, "orders-queue-livesync-b");
        throw failure;
      }
    }
  }
}
