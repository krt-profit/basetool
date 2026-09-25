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
 * Ad-hoc visual harness for the mission tabs against an already running local stack ({@code
 * E2E_BASE_URL}, {@code MISSION_ID}): screenshots every tab, dumps console errors, probes the
 * chip-select styles and performs two board drag-and-drops.
 *
 * <p>Not part of CI; skipped without {@code MISSION_ID}.
 */
@Tag("e2e")
class MissionTabsMockupCheckE2eTest {

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Skips the class unless {@code MISSION_ID} names the mission to walk, then launches the
   * configured browser engine through {@link E2eSupport#launchBrowser}. The skip check runs first,
   * so a CI cell without that engine skips instead of failing.
   */
  @BeforeAll
  static void setUp() {
    assumeTrue(
        System.getenv("MISSION_ID") != null && !System.getenv("MISSION_ID").isBlank(),
        "ad-hoc harness: set MISSION_ID to a mission of an already running local stack");
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

  /** Walks all four tabs as test-admin, screenshotting each and probing the board controls. */
  @Test
  void walkTabsAndCaptureEvidence() {
    String baseUrl = System.getenv().getOrDefault("E2E_BASE_URL", "https://localhost:18081");
    String missionId = System.getenv("MISSION_ID");
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

      for (String tab : new String[] {"ueb", "crew", "fin", "verw"}) {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=" + tab);
        page.waitForLoadState();
        page.waitForTimeout(800);
        page.screenshot(
            new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(Paths.get("build", "e2e", "tab-" + tab + ".png")));
      }

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
      page.waitForLoadState();
      Locator chip = page.locator(".crew-role-select").first();
      if (chip.count() > 0) {
        Object probe =
            chip.evaluate(
                "el => { const cs = getComputedStyle(el); return { bg: cs.backgroundColor, color:"
                    + " cs.color, appearance: cs.appearance, width: el.offsetWidth, options:"
                    + " el.options.length, optBg: el.options.length ?"
                    + " getComputedStyle(el.options[0]).backgroundColor : null }; }");
        System.out.println("[mockup-check] chip-select probe: " + probe);
      } else {
        System.out.println("[mockup-check] no chip-select found");
      }

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=fin");
      page.waitForLoadState();
      Object finProbe =
          page.evaluate(
              "() => ({ sumstrip: !!document.querySelector('#pane-fin .sumstrip'),"
                  + " sums: document.querySelectorAll('#pane-fin .sum').length,"
                  + " financeRows: document.querySelectorAll('#pane-fin table tbody tr').length,"
                  + " ecoDetails: document.querySelectorAll('#pane-fin details').length,"
                  + " pageWidth: document.querySelector('.page-wrapper').offsetWidth })");
      System.out.println("[mockup-check] fin probe: " + finProbe);

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
      page.waitForLoadState();
      int poolBefore = page.locator("#board-pool .person-row").count();
      System.out.println("[mockup-check] pool rows before drag: " + poolBefore);
      if (poolBefore > 0) {
        page.locator("#board-pool .person-row")
            .first()
            .dragTo(page.locator(".board-units .drop-zone").first());
        page.waitForTimeout(2500);
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
        page.waitForLoadState();
        int poolAfter = page.locator("#board-pool .person-row").count();
        System.out.println("[mockup-check] pool rows after drag: " + poolAfter);
        page.screenshot(
            new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(Paths.get("build", "e2e", "tab-crew-after-drag.png")));
      }

      Locator unitRow = page.locator(".board-units .drop-zone .person-row").first();
      if (unitRow.count() > 0) {
        int poolBeforeRemove = page.locator("#board-pool .person-row").count();
        System.out.println("[mockup-check] pool rows before drag-out: " + poolBeforeRemove);
        unitRow.dragTo(page.locator(".sec-h").first());
        page.waitForTimeout(2500);
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
        page.waitForLoadState();
        int poolAfterRemove = page.locator("#board-pool .person-row").count();
        System.out.println("[mockup-check] pool rows after drag-out: " + poolAfterRemove);
        page.screenshot(
            new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(Paths.get("build", "e2e", "tab-crew-after-drag-out.png")));
      }

      System.out.println("[mockup-check] console messages:\n" + consoleLog);
    }
  }
}
