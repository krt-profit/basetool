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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Ad-hoc visual verification harness for the blueprints master-detail page against an ALREADY
 * RUNNING local test stack ({@code E2E_BASE_URL} env; self-skipping without {@code BP_CHECK=true}).
 * Logs in as the synthetic test admin, walks the empty state, stages and adds a blueprint through
 * the typeahead, then screenshots the master-detail view and dumps console errors. Not part of CI.
 */
@Tag("e2e")
class BlueprintsMockupCheckE2eTest {

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Skips the class unless {@code BP_CHECK=true}, then boots the headless browser of the configured
   * engine ({@code -Pe2e.browser}) through {@link E2eSupport#launchBrowser}. The assumption runs
   * <em>before</em> any browser launch: a CI matrix cell installs only its own engine, so an
   * unconditional launch of a hard-coded one fails the whole class with a {@code DriverException}
   * instead of skipping it.
   */
  @BeforeAll
  static void setUp() {
    assumeTrue(
        "true".equals(System.getenv("BP_CHECK")),
        "ad-hoc harness: set BP_CHECK=true against an already running local stack");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, false);
  }

  /** Releases the browser and driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** Walks empty state, add flow, and master-detail rendering; captures screenshots. */
  @Test
  void walkBlueprintsPage() {
    String baseUrl = System.getenv().getOrDefault("E2E_BASE_URL", "https://localhost:18081");
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      StringBuilder consoleLog = new StringBuilder();
      page.onConsoleMessage(
          msg -> {
            if ("error".equals(msg.type()) || "warning".equals(msg.type())) {
              consoleLog
                  .append('[')
                  .append(msg.type())
                  .append("] ")
                  .append(msg.text())
                  .append('\n');
            }
          });
      page.onPageError(err -> consoleLog.append("[pageerror] ").append(err).append('\n'));

      E2eSupport.login(page, baseUrl, "test-admin", "test-admin-pw");

      E2eSupport.navigate(page, baseUrl + "/personal-inventory/blueprints");
      page.waitForLoadState();
      page.waitForTimeout(600);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "bp-initial.png")));

      // Add a blueprint through the typeahead if the collection is still empty.
      Locator rows = page.locator("#krt-bp-master-rows .master-row");
      if (rows.count() == 0) {
        page.locator("#krt-bp-search-input").fill("Demo");
        page.waitForTimeout(900);
        Locator hit = page.locator("#krt-bp-search-results .krt-bp-result:not([disabled])");
        System.out.println("[bp-check] typeahead hits: " + hit.count());
        if (hit.count() > 0) {
          hit.first().click();
          page.waitForTimeout(300);
          page.screenshot(
              new Page.ScreenshotOptions()
                  .setFullPage(true)
                  .setPath(Paths.get("build", "e2e", "bp-staged.png")));
          page.locator("#krt-bp-add-selected").click();
          page.waitForTimeout(2000);
          E2eSupport.navigate(page, baseUrl + "/personal-inventory/blueprints");
          page.waitForLoadState();
        }
      }

      page.waitForTimeout(800);
      Object probe =
          page.evaluate(
              "() => ({ rows: document.querySelectorAll('#krt-bp-master-rows .master-row').length,"
                  + " active: document.querySelectorAll('.master-row.is-active').length,"
                  + " detailVisible: !!document.querySelector('#krt-bp-detail-content') &&"
                  + " !document.querySelector('#krt-bp-detail-content').hidden,"
                  + " blocks: document.querySelectorAll('.quality-block').length,"
                  + " bpParam: new URLSearchParams(location.search).get('bp') })");
      System.out.println("[bp-check] probe: " + probe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "bp-master-detail.png")));

      // Open the edit-note modal: must use the wide KRT frame without scrollbars.
      if (page.locator("#krt-bp-detail-edit").isVisible()) {
        page.locator("#krt-bp-detail-edit").click();
        page.waitForTimeout(300);
        Object modalProbe =
            page.evaluate(
                "() => { const m = document.querySelector('#krt-bp-edit-modal .krt-modal');"
                    + " const b = m.querySelector('.krt-modal-body');"
                    + " return { width: Math.round(m.getBoundingClientRect().width),"
                    + " wide: m.classList.contains('krt-modal--wide'),"
                    + " bodyVScroll: b.scrollHeight > b.clientHeight,"
                    + " bodyHScroll: b.scrollWidth > b.clientWidth }; }");
        System.out.println("[bp-check] note modal probe: " + modalProbe);
        page.screenshot(
            new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(Paths.get("build", "e2e", "bp-note-modal.png")));
      }

      System.out.println("[bp-check] console messages:\n" + consoleLog);
    }
  }
}
