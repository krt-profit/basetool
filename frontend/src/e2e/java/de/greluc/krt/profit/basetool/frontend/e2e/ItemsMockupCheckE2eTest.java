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
 * Ad-hoc visual verification harness for the personal-inventory items page against an ALREADY
 * RUNNING local test stack ({@code E2E_BASE_URL} env; self-skipping without {@code PI_CHECK=true}).
 * Logs in as the synthetic test admin, probes the shared tab-nav, the empty state and the KRT modal
 * frames (including the inline validation re-render), and screenshots each step while dumping
 * console errors. Not part of CI.
 */
@Tag("e2e")
class ItemsMockupCheckE2eTest {

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Skips the class unless {@code PI_CHECK=true}, then boots the headless browser of the configured
   * engine ({@code -Pe2e.browser}) through {@link E2eSupport#launchBrowser}. The assumption runs
   * <em>before</em> any browser launch: a CI matrix cell installs only its own engine, so an
   * unconditional launch of a hard-coded one fails the whole class with a {@code DriverException}
   * instead of skipping it.
   */
  @BeforeAll
  static void setUp() {
    assumeTrue(
        "true".equals(System.getenv("PI_CHECK")),
        "ad-hoc harness: set PI_CHECK=true against an already running local stack");
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

  /** Walks tab-nav, empty state, create modal and validation re-render; captures screenshots. */
  @Test
  void walkItemsPage() {
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

      E2eSupport.navigate(page, baseUrl + "/personal-inventory");
      page.waitForLoadState();
      page.waitForTimeout(500);
      Object probe =
          page.evaluate(
              "() => ({ tabNav: !!document.querySelector('.tab-nav'), activeTab:"
                  + " document.querySelector('.tab-nav .tab.active span')?.textContent, tabCount:"
                  + " document.querySelector('.tab-nav .tab.active .tab-count')?.textContent,"
                  + " oldTabs: !!document.querySelector('.krt-pi-tabs'), emptyState:"
                  + " !!document.querySelector('.krt-personal-inventory .empty-state'),"
                  + " tableVisible: !!document.querySelector('.krt-pi-table'), modalFrame:"
                  + " !!document.querySelector('#krt-pi-modal .krt-modal'), dangerFrame:"
                  + " !!document.querySelector('#krt-pi-delete-modal .krt-modal--danger'),"
                  + " dangerBtn: !!document.querySelector('#krt-pi-delete-modal .btn-danger') })");
      System.out.println("[pi-check] probe: " + probe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "pi-items-initial.png")));

      // Open the create modal and screenshot the KRT frame.
      page.locator(".krt-pi-create").click();
      page.waitForTimeout(300);
      Object modalProbe =
          page.evaluate(
              "() => { const m = document.querySelector('#krt-pi-modal .krt-modal');"
                  + " const b = m.querySelector('.krt-modal-body');"
                  + " return { width: Math.round(m.getBoundingClientRect().width),"
                  + " frameVScroll: m.scrollHeight > m.clientHeight,"
                  + " frameHScroll: m.scrollWidth > m.clientWidth,"
                  + " bodyVScroll: b.scrollHeight > b.clientHeight,"
                  + " bodyHScroll: b.scrollWidth > b.clientWidth }; }");
      System.out.println("[pi-check] modal probe: " + modalProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "pi-items-create-modal.png")));

      // Submit name + quantity without a location: the server re-renders the page inline
      // with the modal open (showItemModal) and the validation error box visible.
      page.locator("#krt-pi-name").fill("Mockup Check Item");
      page.locator("#krt-pi-quantity").fill("3");
      page.locator("#krt-pi-form button[type=submit]").click();
      page.waitForLoadState();
      page.waitForTimeout(500);
      Object validationProbe =
          page.evaluate(
              "() => ({ modalShown:"
                  + " getComputedStyle(document.getElementById('krt-pi-modal')).display, errorBox:"
                  + " !!document.querySelector('#krt-pi-modal .krt-pi-error-box'), nameKept:"
                  + " document.getElementById('krt-pi-name')?.value })");
      System.out.println("[pi-check] validation probe: " + validationProbe);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setFullPage(true)
              .setPath(Paths.get("build", "e2e", "pi-items-validation.png")));

      System.out.println("[pi-check] console messages:\n" + consoleLog);
    }
  }
}
