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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Browser coverage for the cross-order material-demand page ({@code /orders/material-demand},
 * REQ-ORDERS-034). The MockMvc render test pins the server-rendered markup; everything this page
 * does <em>after</em> the HTML arrives is client-side and only reachable here: the per-bucket
 * drill-down toggle and its per-browser {@code localStorage} persistence across a reload.
 *
 * <p>Seeds two orders of the same profit-eligible unit requesting the <em>same</em> material at the
 * same quality, so the page must fold them into a <b>single</b> row whose demand is the sum — the
 * defining behaviour of the feature — with both orders listed in that row's drill-down.
 *
 * <p>The seeded material name is unique to this class, so the assertions stay stable even when a
 * shared stack carries other suites' orders.
 */
@Tag("e2e")
class JobOrderMaterialDemandE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The canonical profit-eligible IRIDIUM Squadron seeded at stack bootstrap. */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Unique to this suite so a shared stack's other orders cannot collide with the assertions. */
  private static final String MATERIAL_NAME = "E2E Demand Ore";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and seeds two orders requesting the same material bucket, so the page has
   * something to aggregate.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, MATERIAL_NAME);
      seeder.createJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E demand A", materialId, 650, 40);
      seeder.createJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E demand B", materialId, 650, 60);
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
   * Opens a fresh authenticated context so each test starts with its own empty {@code
   * localStorage}. The caller closes it via try-with-resources.
   *
   * @return a new browser context carrying the authenticated session
   */
  private static BrowserContext newContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(
                E2eSupport.authenticatedStorageState(
                    browser, STACK.baseUrl(), USERNAME, PASSWORD)));
  }

  /**
   * Locates this suite's aggregated bucket row by its unique material name.
   *
   * @param page the page showing the demand overview.
   * @return the locator of the single matching row.
   */
  private static Locator demandRow(Page page) {
    return page.locator("tr[data-testid='demand-row']")
        .filter(new Locator.FilterOptions().setHasText(MATERIAL_NAME));
  }

  /**
   * The two seeded orders must collapse into one row whose demand is their sum, under the
   * responsible unit's own section — the aggregation the page exists for, verified end to end
   * through the real backend rather than a stubbed projection.
   */
  @Test
  void twoOrdersOfOneUnitFoldIntoASingleSummedRow() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");

        assertThat(page.locator("section[data-testid='demand-group']").first()).isVisible();
        Locator row = demandRow(page);
        assertThat(row).hasCount(1);
        // 40 + 60, summed across the two orders and formatted as an SCU amount.
        assertThat(row.locator("[data-testid='demand-required']")).containsText("100,000");
        // Nothing is booked against them, so the whole demand is still outstanding.
        assertThat(row.locator("[data-testid='demand-outstanding']")).containsText("100,000");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-aggregation");
        throw failure;
      }
    }
  }

  /**
   * The drill-down starts collapsed, opens on click listing both contributing orders, and its
   * expanded state survives a reload — the per-browser {@code localStorage} restore that no
   * server-side test can reach.
   */
  @Test
  void drillDownStartsCollapsedOpensAndSurvivesAReload() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");

        Locator row = demandRow(page);
        String bucketKey = row.getAttribute("data-bucket-key");
        Locator drillDown = page.locator("tr[data-bucket-orders='" + bucketKey + "']");
        Locator toggle = row.locator("[data-trigger='demand-toggle-orders']");

        assertThat(drillDown).isHidden();
        assertThat(toggle).hasAttribute("aria-expanded", "false");

        toggle.click();
        assertThat(drillDown).isVisible();
        assertThat(toggle).hasAttribute("aria-expanded", "true");
        // Both seeded orders are listed as contributors of this one bucket.
        assertThat(drillDown.locator("tr[data-testid='demand-order-share']")).hasCount(2);

        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        Locator reloaded = page.locator("tr[data-bucket-orders='" + bucketKey + "']");
        assertThat(reloaded).isVisible();
        assertThat(demandRow(page).locator("[data-trigger='demand-toggle-orders']"))
            .hasAttribute("aria-expanded", "true");

        // Collapsing again must persist too, otherwise the state would be write-once.
        demandRow(page).locator("[data-trigger='demand-toggle-orders']").click();
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        assertThat(page.locator("tr[data-bucket-orders='" + bucketKey + "']")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-drilldown");
        throw failure;
      }
    }
  }

  /**
   * The page is advertised in the navigation, not only reachable by typing the URL. The link is
   * asserted by presence and target rather than by clicking it: the nav entries live inside
   * collapsed {@code <details>} groups, so a click would be testing the sidebar's disclosure
   * behaviour instead of this feature.
   */
  @Test
  void navigationCarriesTheMaterialDemandEntry() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders");
        Locator navLink = page.locator("[data-testid='nav-orders-material-demand']");
        assertThat(navLink).hasCount(1);
        assertThat(navLink).hasAttribute("href", "/orders/material-demand");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-nav");
        throw failure;
      }
    }
  }
}
