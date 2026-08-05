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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
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

  /**
   * A second material whose demand is fully covered by linked stock, so its row has an outstanding
   * amount of 0 — the row the "hide covered" filter must remove and the uncovered one must survive.
   */
  private static final String COVERED_MATERIAL_NAME = "E2E Demand Covered";

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

      // A second, fully covered bucket: 25 required with 25 linked in stock, so its outstanding
      // amount is 0 and the hide-covered filter has something to remove.
      String coveredMaterialId =
          seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, COVERED_MATERIAL_NAME);
      String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Demand Location");
      String coveredOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E demand covered", coveredMaterialId, 650, 25);
      seeder.createInventoryItemForJobOrder(
          USERNAME, PASSWORD, coveredMaterialId, locationId, coveredOrderId, 750, 25);
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

  /**
   * The filter panel collapses, remembers the choice across a reload, and the toggle carries the
   * active-filter count — the Lager idiom (REQ-INV-037) this page mirrors.
   */
  @Test
  void filterPanelCollapsesRemembersTheChoiceAndCountsActiveFilters() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        Locator toggle = page.locator("[data-testid='demand-filter-toggle']");
        Locator panel = page.locator("#demandFilterPanel");
        Locator count = page.locator("[data-testid='demand-filter-count']");

        // Nothing is filtered on a first visit, so the panel starts collapsed and the chip is off.
        assertThat(panel).isHidden();
        assertThat(toggle).hasAttribute("aria-expanded", "false");
        assertThat(count).isHidden();

        toggle.click();
        assertThat(panel).isVisible();
        page.locator("[data-testid='demand-hide-covered']").check();
        assertThat(count).isVisible();
        assertThat(count).containsText("1");

        // The explicit open choice AND the active filter both survive a reload.
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        assertThat(page.locator("#demandFilterPanel")).isVisible();
        assertThat(page.locator("[data-testid='demand-hide-covered']")).isChecked();
        assertThat(page.locator("[data-testid='demand-filter-count']")).containsText("1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-filter-panel");
        throw failure;
      }
    }
  }

  /**
   * "Gedeckte ausblenden" removes exactly the bucket whose stock already covers its demand and
   * leaves the uncovered one, then reverts cleanly.
   */
  @Test
  void hideCoveredRemovesOnlyTheFullyStockedRow() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        Locator covered = demandRowFor(page, COVERED_MATERIAL_NAME);
        Locator uncovered = demandRow(page);
        assertThat(covered).isVisible();
        assertThat(uncovered).isVisible();

        page.locator("[data-testid='demand-filter-toggle']").click();
        page.locator("[data-testid='demand-hide-covered']").check();

        assertThat(covered).isHidden();
        assertThat(uncovered).isVisible();

        page.locator("[data-testid='demand-hide-covered']").uncheck();
        assertThat(covered).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-hide-covered");
        throw failure;
      }
    }
  }

  /**
   * Unchecking a material in the multi-select hides its rows; the in-dropdown search narrows the
   * option list without changing what the table shows.
   */
  @Test
  void materialFilterHidesDeselectedMaterialsAndTheSearchNarrowsTheOptions() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        page.locator("[data-testid='demand-filter-toggle']").click();
        page.locator("#demandMaterialHeader").click();

        Locator coveredOption =
            page.locator(".demand-material-option")
                .filter(new Locator.FilterOptions().setHasText(COVERED_MATERIAL_NAME));
        Locator oreOption =
            page.locator(".demand-material-option")
                .filter(new Locator.FilterOptions().setHasText(MATERIAL_NAME))
                .first();

        // The search is presentation only: it hides options, never rows.
        page.locator("[data-testid='demand-material-search']").fill(COVERED_MATERIAL_NAME);
        assertThat(coveredOption).isVisible();
        assertThat(oreOption).isHidden();
        assertThat(demandRow(page)).isVisible();

        coveredOption.locator("input[type='checkbox']").uncheck();
        assertThat(demandRowFor(page, COVERED_MATERIAL_NAME)).isHidden();
        assertThat(demandRow(page)).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-material-filter");
        throw failure;
      }
    }
  }

  /**
   * Clicking the "Offen" header sorts every group table by the outstanding amount and reverses on a
   * second click. Asserted on the RELATIVE order of this suite's own two rows, so a shared stack's
   * other seeded materials cannot break it.
   */
  @Test
  void clickingAColumnHeaderSortsAndReversesTheGroupTables() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/material-demand");
        Locator sortOutstanding = page.locator("[data-testid='demand-sort-outstanding']").first();

        sortOutstanding.click();
        assertThat(page.locator("th[data-sort-col='outstanding']").first())
            .hasAttribute("aria-sort", "ascending");
        // Ascending: the covered bucket (0) comes before the outstanding one (100).
        assertTrue(
            rowIndexOf(page, COVERED_MATERIAL_NAME) < rowIndexOf(page, MATERIAL_NAME),
            "covered row sorts before the outstanding row ascending");

        sortOutstanding.click();
        assertThat(page.locator("th[data-sort-col='outstanding']").first())
            .hasAttribute("aria-sort", "descending");
        assertTrue(
            rowIndexOf(page, MATERIAL_NAME) < rowIndexOf(page, COVERED_MATERIAL_NAME),
            "outstanding row sorts first descending");

        // A third click returns to the server's own order.
        sortOutstanding.click();
        assertThat(page.locator("th[data-sort-col='outstanding']").first())
            .hasAttribute("aria-sort", "none");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-demand-sort");
        throw failure;
      }
    }
  }

  /**
   * Locates a bucket row by material name.
   *
   * @param page the page showing the demand overview.
   * @param materialName the material to look for.
   * @return the locator of the matching row.
   */
  private static Locator demandRowFor(Page page, String materialName) {
    return page.locator("tr[data-material-name='" + materialName + "']").first();
  }

  /**
   * The position of a material's row among the visible bucket rows of its group, used to assert a
   * relative sort order without depending on what else the stack contains.
   *
   * @param page the page showing the demand overview.
   * @param materialName the material to locate.
   * @return the zero-based index, or -1 when the row is absent.
   */
  private static int rowIndexOf(Page page, String materialName) {
    List<Locator> rows =
        page.locator("section[data-testid='demand-group'] tr[data-testid='demand-row']").all();
    for (int index = 0; index < rows.size(); index++) {
      if (materialName.equals(rows.get(index).getAttribute("data-material-name"))) {
        return index;
      }
    }
    return -1;
  }
}
