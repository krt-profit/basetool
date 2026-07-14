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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
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
 * Two-context live-sync coverage for the shared squadron Lager ({@code /inventory/all}, #1307,
 * REQ-FE-010 / REQ-FE-015): an allocation-chip change one logistician makes must update another
 * viewer who has the same stack expanded, without a manual reload — the opaque {@code stock}
 * section key crossing the global {@code inventory} room.
 *
 * <p>Two browser contexts as the same admin are two distinct {@code /ws/sync} sockets, so the
 * deterministic pre-mutation wait is context B's {@code window.krtLiveSync.subscribedTopics()}
 * containing {@code inventory} (the global room is authorized by the socket authentication alone).
 * A shared, job-order-eligible 100-SCU row is seeded; context A adds a job-order allocation chip
 * through the real "+ Zuordnen" combobox ({@code POST /inventory/{id}/allocation}, which broadcasts
 * {@code inventory/[stock]}), and context B — a passive viewer that never reloads — must show the
 * new chip in place, driven purely by the change signal (its own filtered fragment re-fetch
 * preserves the expanded tree). No stock data crosses the socket; B re-pulls its own authorized
 * view.
 */
@Tag("e2e")
class InventorySharedLagerLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Quality stamped on the seeded row; clears the job order's {@code minQuality} of 650. */
  private static final int SEED_QUALITY = 750;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String materialId;
  private static String itemId;
  private static String orderId;

  /**
   * Launches the browser and seeds the IRIDIUM membership plus a shared, job-order-eligible 100-SCU
   * row and an order requesting its material, so context A can earmark a slice of it.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);

    String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Sync Lager Loc");
    materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Sync Lager Mat");
    itemId =
        seeder.createInventoryItem(USERNAME, PASSWORD, materialId, locationId, SEED_QUALITY, 100);
    orderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Sync Lager Order", materialId, 650, 100);
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
   * Context A (a logistician) adds a job-order allocation chip on {@code /inventory/all}; context B
   * — a passive viewer with the same stack expanded that never reloads — must show that chip IN
   * PLACE, driven purely by the {@code inventory/[stock]} change signal over {@code /ws/sync}.
   */
  @Test
  void allocationChipAddPropagatesToAnotherViewerLive() {
    String baseUrl = STACK.baseUrl();
    String chip =
        "div.assoc-split[data-entry-id='"
            + itemId
            + "'][data-assoc-field='JOB_ORDER'] [data-assoc-chip='jobOrder'][data-target-id='"
            + orderId
            + "']";

    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext contextA =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState));
        BrowserContext contextB =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState))) {
      Page pageA = contextA.newPage();
      Page pageB = contextB.newPage();
      try {
        expandToLeaf(pageA);
        expandToLeaf(pageB);

        // B sees the row but not yet the order chip (the order is unallocated).
        assertThat(pageB.locator(chip)).hasCount(0);

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: B is registered with the relay once its `inventory` subscribe is
        // acked.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics &&"
                            + " window.krtLiveSync.subscribedTopics().indexOf('inventory') >="
                            + " 0)")));

        // Context A earmarks 60 SCU of the row to the order (broadcasts inventory/[stock]).
        addOrderChip(pageA);

        // The assertion under test: context B — which did nothing — shows the new chip in place,
        // its
        // filtered fragment re-fetched with the expanded tree restored (the global room coalesces
        // at
        // ~1.5 s), and never reloaded.
        assertThat(pageB.locator(chip))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "inventory-livesync-a");
        E2eSupport.dump(pageB, "inventory-livesync-b");
        throw failure;
      }
    }
  }

  /**
   * Navigates to {@code /inventory/all} and expands the seeded material's group then its single
   * stack, waiting for the lazily-fetched entry leaf row to load. The material is unique to this
   * test, so the group / stack / leaf selectors resolve unambiguously by material or item id.
   *
   * @param page the authenticated page
   */
  private static void expandToLeaf(Page page) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/all");
    page.waitForLoadState();
    page.locator("div.tree-row--group[data-material-id='" + materialId + "']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    page.locator("div.stack-header[data-material-id='" + materialId + "']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    // 20 s, not the 5 s default: the lazy stack-entries fetch + render is slow on WebKit under
    // load.
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + itemId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Adds the seeded order as a job-order allocation chip on the expanded entry through the inline
   * "+ Zuordnen" combobox: opens the JOB_ORDER split's popover, picks the order by its value, fills
   * 60 and saves, waiting for the in-place {@code POST /inventory/{id}/allocation} to settle. Drops
   * the fixed footer, which can otherwise intercept the trusted clicks.
   *
   * @param page the authenticated page expanded to the entry (see {@link #expandToLeaf})
   */
  private static void addOrderChip(Page page) {
    Locator split =
        page.locator(
            "div.assoc-split[data-entry-id='" + itemId + "'][data-assoc-field='JOB_ORDER']");
    dropFooter(page);
    split
        .locator("button[data-trigger='inv-admin-assoc-add-open']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    Locator pop = split.locator("[data-assoc-pop]");
    pop.locator(".krt-combobox__input").click();
    pop.locator("li.krt-combobox__option[data-value='" + orderId + "']").click();
    pop.locator("[data-assoc-amount-input]").fill("60");
    page.waitForResponse(
        r -> r.url().contains("/allocation") && "POST".equals(r.request().method()),
        new Page.WaitForResponseOptions().setTimeout(60_000),
        () -> pop.locator("button[data-trigger='inv-admin-assoc-save']").click());
  }

  /**
   * Hides the {@code position:fixed} footer, which can intercept the trusted click on some engines
   * (the WebKit/Firefox footer-overlap flake the other inventory e2es also guard against).
   *
   * @param page the active page
   */
  private static void dropFooter(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer');"
            + " if (f) { f.style.display = 'none'; } }");
  }
}
