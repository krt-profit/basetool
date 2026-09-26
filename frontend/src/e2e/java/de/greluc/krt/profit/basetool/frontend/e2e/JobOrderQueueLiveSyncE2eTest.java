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
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E live-sync coverage for the job-order queue (REQ-FE-015, ADR-0094): a passive {@code /orders}
 * viewer gains a newly created order in place, via the server-side {@code orders/[queue]} publish
 * of a frontend form create.
 */
@Tag("e2e")
class JobOrderQueueLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * The canonical IRIDIUM Squadron seeded at stack bootstrap (a profit-eligible unit, so it is both
   * a valid requester and a valid responsible for the create, and the queue viewer sees it).
   */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /**
   * Launches the browser and seeds IRIDIUM membership, a job-order material and one initial order
   * so the queue has a non-empty baseline the live create adds to.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E LiveSync Ore");
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
   * Creates an order from one browser context on {@code /orders/create} and asserts that a second
   * context viewing {@code /orders} gains the row without reloading.
   */
  @Test
  void orderCreatePropagatesToTheQueueViewerLive() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext viewer =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(
                        E2eSupport.authenticatedStorageState(
                            browser, baseUrl, USERNAME, PASSWORD)));
        BrowserContext creator =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(
                        E2eSupport.authenticatedStorageState(
                            browser, baseUrl, USERNAME, PASSWORD)))) {
      Page pageB = viewer.newPage();
      Page pageA = creator.newPage();
      try {
        E2eSupport.navigate(pageB, baseUrl + "/orders");
        pageB.waitForLoadState();

        int before = pageB.locator("#orders-results tr[data-id]").count();

        pageB.evaluate("window.__krtNoReload = true;");

        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        E2eSupport.navigate(pageA, baseUrl + "/orders/create");
        pageA.locator("#requestingOrgUnitId").selectOption(IRIDIUM_ID);
        pageA.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        pageA.locator("#handle").fill("E2E live " + UUID.randomUUID());
        E2eSupport.selectComboboxFirstOption(pageA.getByTestId("order-material-select"));
        pageA.getByTestId("order-material-amount").fill("5");
        E2eSupport.clickSubmitClearingFooter(pageA.getByTestId("order-submit"));
        pageA.waitForLoadState();

        pageB.waitForCondition(
            () -> pageB.locator("#orders-results tr[data-id]").count() == before + 1,
            new Page.WaitForConditionOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageB, "orders-queue-livesync-b");
        E2eSupport.dump(pageA, "orders-queue-livesync-a");
        throw failure;
      }
    }
  }
}
