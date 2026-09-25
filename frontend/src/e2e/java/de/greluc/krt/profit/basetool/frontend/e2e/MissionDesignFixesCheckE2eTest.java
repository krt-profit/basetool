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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Ad-hoc harness that checks the mission detail page's design fixes at an 1880px viewport against
 * an already running local stack; not part of CI.
 *
 * <p>Skipped unless {@code MISSION_FIX_CHECK=true}; reads {@code E2E_BASE_URL} and {@code
 * MISSION_ID}.
 */
@Tag("e2e")
class MissionDesignFixesCheckE2eTest {

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Skips the class unless {@code MISSION_FIX_CHECK=true}, then launches the configured browser
   * engine through {@link E2eSupport#launchBrowser}. The skip check runs first, so a CI cell
   * without that engine skips instead of failing.
   */
  @BeforeAll
  static void setUp() {
    assumeTrue(
        "true".equals(System.getenv("MISSION_FIX_CHECK")),
        "ad-hoc harness: set MISSION_FIX_CHECK=true against an already running local stack");
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

  /** Probes all five review fixes per tab on a 1880px viewport; captures screenshots. */
  @Test
  void walkMissionTabs() {
    String baseUrl = System.getenv().getOrDefault("E2E_BASE_URL", "https://localhost:18081");
    String missionId =
        System.getenv().getOrDefault("MISSION_ID", "75117606-d758-4ee3-8597-7938bb2850f2");
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(1880, 1200))) {
      Page page = context.newPage();
      StringBuilder consoleLog = new StringBuilder();
      page.onConsoleMessage(
          msg -> {
            if ("error".equals(msg.type())) {
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

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=ueb");
      page.waitForLoadState();
      page.waitForTimeout(400);
      Object uebProbe =
          page.evaluate("() => ({ navcards: document.querySelectorAll('.navcard').length })");
      System.out.println("[fix-check] ueb probe: " + uebProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "fix-ueb.png")));

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
      page.waitForLoadState();
      page.waitForTimeout(400);
      Object crewProbe =
          page.evaluate(
              "() => { const l = document.querySelector('.sec-h .legend');"
                  + " const h = document.querySelector('.drop-hint');"
                  + " const cs = el => el ? { color: getComputedStyle(el).color,"
                  + " weight: getComputedStyle(el).fontWeight } : null;"
                  + " return { legend: cs(l), dropHint: cs(h) }; }");
      System.out.println("[fix-check] crew probe: " + crewProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "fix-crew.png")));

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=fin");
      page.waitForLoadState();
      page.waitForTimeout(400);
      Object finProbe =
          page.evaluate(
              "() => { const w ="
                  + " document.querySelector('.payout-table')?.closest('.table-responsive');"
                  + " let d = document.querySelector('#pane-fin details.hud-details');"
                  + " let synthetic = false;"
                  + " if (!d) { d = document.createElement('details'); d.className = 'hud-details';"
                  + " document.getElementById('pane-fin').appendChild(d); synthetic = true; }"
                  + " return { payoutFits: w ? w.scrollWidth <= w.clientWidth : null,"
                  + " payoutOverflowPx: w ? w.scrollWidth - w.clientWidth : null,"
                  + " detailsBg: getComputedStyle(d).backgroundColor, syntheticDetails: synthetic"
                  + " }; }");
      System.out.println("[fix-check] fin probe: " + finProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "fix-fin.png")));

      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=verw");
      page.waitForLoadState();
      page.waitForTimeout(400);
      Object verwProbe =
          page.evaluate(
              "() => Array.from(document.querySelectorAll('[data-trigger=mission-set-now]')).map(b"
                  + " => { const r = b.getBoundingClientRect(); const p ="
                  + " b.closest('.form-group').getBoundingClientRect(); return { visible: r.width >"
                  + " 0, insidePane: r.right <= p.right + 1, insideViewport: r.right <="
                  + " window.innerWidth }; })");
      System.out.println("[fix-check] verw probe: " + verwProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "fix-verw.png")));

      System.out.println("[fix-check] console errors:\n" + consoleLog);
    }
  }
}
