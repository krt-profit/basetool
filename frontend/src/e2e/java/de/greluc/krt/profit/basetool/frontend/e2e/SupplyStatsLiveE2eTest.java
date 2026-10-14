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
 * The live statistics of „Bedarf &amp; Spenden" (REQ-SUP-013, REQ-FE-010): a donation recorded on
 * „Eingehende Spenden" in one session refreshes the statistics page of a second session in place,
 * without a reload.
 */
@Tag("e2e")
class SupplyStatsLiveE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String HIDE_FOOTER =
      "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
          + " 'none'; } }";

  private static final String STATS_TEXT =
      "() => document.querySelector('#supply-stats-results').innerText";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser, switches the supply surface on, lets IRIDIUM take donations. */
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
            "{\"enabled\":true,\"governingAreaId\":null}");
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    int unit =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/units/" + IRIDIUM_ID,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
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
   * Opens the statistics in one session, records a received donation in another and expects the
   * first session's statistics to change without a reload.
   */
  @Test
  void aRecordedDonationRefreshesThePeersStatistics() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    Browser.NewContextOptions options =
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState);
    try (BrowserContext watcher = browser.newContext(options);
        BrowserContext writer = browser.newContext(options)) {
      Page watching = watcher.newPage();
      Page writing = writer.newPage();
      try {
        E2eSupport.navigate(watching, baseUrl + "/supply/stats");
        watching.waitForLoadState();
        watching.evaluate("() => { window.__krtNoReload = true; }");
        String before = (String) watching.evaluate(STATS_TEXT);

        E2eSupport.navigate(
            writing, baseUrl + "/supply/donations/incoming?orgUnitId=" + IRIDIUM_ID);
        writing.waitForLoadState();
        writing.evaluate(HIDE_FOOTER);
        writing.locator("[data-testid='supply-record']").click();
        selectFirstOption(writing, "#supply-record-material");
        writing.locator("#supply-record-amount").fill("2");
        selectFirstOption(writing, "#supply-record-location");
        writing.evaluate(
            "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove());"
                + " }");
        writing.locator("[data-testid='supply-record-submit']").click();
        assertThat(writing.locator(".notification-toast:not(.error-toast)"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        watching.waitForFunction(
            "previous => document.querySelector('#supply-stats-results').innerText !== previous",
            before,
            new Page.WaitForFunctionOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            watching.evaluate("() => window.__krtNoReload === true"),
            "the statistics must refresh in place — no reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(watching, "supply-stats-live");
        throw failure;
      }
    }
  }

  /**
   * Selects the first real option of a select, so a catalogue the frontend caches needs no seeded
   * entry.
   *
   * @param page the page
   * @param selectSelector the select's selector
   */
  private static void selectFirstOption(Page page, String selectSelector) {
    String value =
        page.locator(selectSelector + " option:not([value=''])").first().getAttribute("value");
    page.locator(selectSelector).selectOption(value);
  }
}
