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
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The layout of the „Bedarf" table on a desktop viewport (REQ-SUP-021): a row's actions share one
 * line, the column headers stay below the page header while the page scrolls, and the last row's
 * „⋯" menu opens upwards inside the table.
 */
@Tag("e2e")
class SupplyNeedsTableLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

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
    String materialId =
        seeder.ensureJobOrderMaterial(
            USERNAME,
            PASSWORD,
            "Bedarf Layout E2E " + UUID.randomUUID().toString().substring(0, 8));
    for (int i = 0; i < NEED_COUNT; i++) {
      int raised =
          seeder.postForStatus(
              USERNAME,
              PASSWORD,
              "/api/v1/supply/needs",
              "{\"orgUnitId\":\""
                  + IRIDIUM_ID
                  + "\",\"materialId\":\""
                  + materialId
                  + "\",\"amount\":"
                  + (i + 1)
                  + ",\"priority\":\"NORMAL\",\"purchasable\":false}");
      assertEquals(201, raised, "a need must be raisable");
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

  /** Checks the row actions, the sticky column headers and the upward menu on one page. */
  @Test
  void theNeedsTableKeepsActionsHeadersAndMenusInView() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(1440, 900))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/supply/needs?status=OPEN&size=50");
        page.waitForLoadState();
        Locator rows = page.locator("[data-testid='supply-need-row']");
        assertThat(rows.first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        assertEquals(
            Boolean.TRUE,
            rows.first()
                .locator(".supply-row-actions")
                .evaluate(
                    "(el) => { const r = [...el.children].map((c) => c.getBoundingClientRect());"
                        + " return r.length === 3 && Math.max(...r.map((x) => x.top)) <"
                        + " Math.min(...r.map((x) => x.bottom)); }"),
            "the pledge, edit and menu controls must share one line");

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
}
