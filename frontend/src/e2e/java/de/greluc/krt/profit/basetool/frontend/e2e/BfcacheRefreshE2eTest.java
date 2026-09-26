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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Asserts that a page restored from the back/forward cache is reloaded by the global {@code
 * pageshow} handler (REQ-FE-008).
 *
 * <p>Dispatches a synthetic {@code pageshow} event with {@code persisted: true}, because a real
 * bfcache restore is not reproducible across engines.
 */
@Tag("e2e")
class BfcacheRefreshE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and performs the single shared login (ephemeral stack only). */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Stamps a marker on the live document, dispatches {@code pageshow{persisted:true}} and waits for
   * the marker to disappear through the handler's reload.
   */
  @Test
  void bfcacheRestoreForcesAFreshServerRender() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/");
        page.waitForLoadState();

        page.evaluate(
            "() => {"
                + "  window.__bfcacheMarker = 'present';"
                + "  setTimeout(function () {"
                + "    window.dispatchEvent("
                + "      new PageTransitionEvent('pageshow', { persisted: true }));"
                + "  }, 0);"
                + "}");

        page.waitForFunction(
            "() => typeof window.__bfcacheMarker === 'undefined'",
            null,
            new Page.WaitForFunctionOptions().setTimeout(60_000));

        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => typeof window.__bfcacheMarker === 'undefined'"),
            "a bfcache restore must reload to a fresh document — the pre-restore marker is gone");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bfcache-refresh");
        throw failure;
      }
    }
  }
}
