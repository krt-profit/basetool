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
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verifies the needs table's responsive layout and row actions (REQ-SUP-021). */
@Tag("e2e")
class SupplyNeedsTableLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String LONG_MATERIAL_NAME =
      "Bedarf Layout E2E "
          + "Rohstoffaufbereitungsspeziallegierungsqualitaetstestmaterialkatalogeintragsonderbestellung";

  private static final int NEED_COUNT = 15;

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser, switches the supply surface on and raises enough open needs to scroll.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    }
    int settings =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/settings",
            "{\"enabled\":true,\"governingAreaIds\":[]}");
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    int unit =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/units/" + IRIDIUM_ID,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String materialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "Bedarf Layout " + suffix);
    for (int i = 0; i < NEED_COUNT; i++) {
      raiseNeed(seeder, materialId, i + 1, false);
    }
    String longMaterialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, LONG_MATERIAL_NAME + " " + suffix);
    raiseNeed(seeder, longMaterialId, 1, true);
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

  /** Checks the row actions, the sticky column headers and the upward menu on one page. */
  @Test
  void theNeedsTableKeepsActionsHeadersAndMenusInView() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = signedInContext(1440, 900, false)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/supply/needs?status=OPEN&size=50");
        page.waitForLoadState();
        Locator rows = page.locator("[data-testid='supply-need-row']");
        assertThat(rows.first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        assertRowActionsShareOneLine(rows.first());

        page.evaluate("() => window.scrollTo(0, 700)");
        assertEquals(
            Boolean.TRUE,
            page.evaluate(
                "() => { const header = document.querySelector('body >"
                    + " header').getBoundingClientRect(); const th ="
                    + " document.querySelector('.supply-table thead th').getBoundingClientRect();"
                    + " return window.scrollY > 0 && Math.abs(th.top - header.bottom) <= 1; }"),
            "the column headers must stay directly below the page header while scrolling");

        Locator last = rows.last();
        last.scrollIntoViewIfNeeded();
        last.locator("[data-testid='overflow-menu-toggle']").click();
        Locator panel = last.locator(".overflow-menu__panel");
        assertThat(panel).isVisible();
        assertEquals(
            Boolean.TRUE,
            panel.evaluate(
                "(el) => { const p = el.getBoundingClientRect();"
                    + " const t = el.closest('table').getBoundingClientRect();"
                    + " return el.classList.contains('overflow-menu__panel--up')"
                    + " && p.bottom <= t.bottom + 1 && p.top >= 0; }"),
            "the last row's menu must open upwards inside the table");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-needs-table-layout");
        throw failure;
      }
    }
  }

  /** Checks that a long material name fits at the reference tablet width. */
  @Test
  void theTabletKeepsLongMaterialNamesAndRowActionsInsideTheContent() {
    try (BrowserContext context = signedInContext(810, 1080, true)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/supply/needs?status=OPEN&size=50");
        Locator row =
            page.getByTestId("supply-need-row")
                .filter(new Locator.FilterOptions().setHasText(LONG_MATERIAL_NAME))
                .first();
        assertThat(row.getByTestId("supply-need-purchasable")).isVisible();
        assertEquals(
            Boolean.TRUE,
            row.evaluate(
                """
                (row) => {
                  const viewportWidth = document.documentElement.clientWidth;
                  const pageWidth = Math.max(document.documentElement.scrollWidth,
                                             document.body.scrollWidth);
                  const table = row.closest('table');
                  return pageWidth === viewportWidth
                    && table.getBoundingClientRect().right
                      <= table.parentElement.getBoundingClientRect().right + 1;
                }
                """),
            "the long name must fit without widening the table or creating page overflow");

        assertRowActionsShareOneLine(row);
        row.getByTestId("overflow-menu-toggle").tap();
        row.getByRole(AriaRole.MENUITEM).first().hover();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-needs-table-tablet");
        throw failure;
      }
    }
  }

  /**
   * Raises an open need in the test unit, with a price and reason if purchasing is allowed.
   *
   * @param seeder the backend fixture client
   * @param materialId the needed material
   * @param amount the required amount
   * @param purchasable whether the need can be bought
   */
  private static void raiseNeed(
      BackendSeeder seeder, String materialId, int amount, boolean purchasable) {
    String purchaseFields =
        purchasable
            ? "\"purchasable\":true,\"purchaseReason\":\"RARE_SOURCE\",\"maxUnitPrice\":200"
            : "\"purchasable\":false";
    int raised =
        seeder.postForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/needs",
            """
            {"orgUnitId":"%s","materialId":"%s","amount":%d,"priority":"NORMAL",%s}
            """
                .formatted(IRIDIUM_ID, materialId, amount, purchaseFields));
    assertEquals(201, raised, "a need must be raisable");
  }

  /**
   * Opens an authenticated session at the given viewport.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   * @param touch whether the device supports touch
   * @return the authenticated context
   */
  private static BrowserContext signedInContext(int width, int height, boolean touch) {
    Path storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState)
            .setHasTouch(touch)
            .setViewportSize(width, height));
  }

  /**
   * Checks that all row actions share one line.
   *
   * @param row the needs table row
   */
  private static void assertRowActionsShareOneLine(Locator row) {
    assertEquals(
        Boolean.TRUE,
        row.locator(".supply-row-actions")
            .evaluate(
                "(el) => { const r = [...el.children].map((c) => c.getBoundingClientRect());"
                    + " return r.length === 3 && Math.max(...r.map((x) => x.top)) <"
                    + " Math.min(...r.map((x) => x.bottom)); }"),
        "the pledge, edit and menu controls must share one line");
  }
}
