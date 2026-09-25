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
 * Regression for the back/forward-cache (bfcache) staleness bug class (spec REQ-FE-008, ADR-0013):
 * a document the browser replays from its bfcache reinstates the in-memory DOM snapshot taken when
 * the user navigated away — it does NOT re-run the GET — so a server-rendered aggregate (a bank
 * account-card balance, a list count) shows its pre-edit value after the user edits the entity on a
 * forward page and navigates back. The in-place mutation foundation (REQ-FE-001..007) keeps only
 * the active document fresh; the global {@code pageshow} handler in {@code common-handlers.js}
 * closes the gap by reloading when {@code event.persisted} is true.
 *
 * <p><b>Why a synthetic event.</b> A genuine bfcache restore is not reliably reproducible under
 * Playwright across all three engines (whether the engine bfcaches a given page is timing- and
 * heuristic-dependent). The signal the handler keys on — {@code PageTransitionEvent.persisted} — is
 * settable from the constructor, so dispatching {@code new PageTransitionEvent('pageshow',
 * {persisted: true})} drives the exact production code path deterministically on Chromium, Firefox
 * and WebKit.
 *
 * <p>The page under test is the neutral home page ({@code /}), which loads {@code
 * common-handlers.js} via the shared head fragment like every page; the behaviour is global, so the
 * assertion does not depend on the bank seeding it originally surfaced from. A marker stamped on
 * the live document proves the reload ran: the handler's {@code location.reload()} produces a fresh
 * document where the marker is gone, whereas a missing handler would leave the snapshot — and the
 * marker — in place, timing the wait out.
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
   * Loads a page, stamps a marker on the live document, then dispatches a {@code
   * pageshow{persisted:true}} — the exact signal a bfcache restore carries. The global handler must
   * react with a full {@code location.reload()}, producing a fresh document where the marker is
   * gone; its disappearance (awaited via {@code waitForFunction}, which survives the navigation) is
   * the discriminator. A build missing the handler would leave the marker in place and time the
   * wait out.
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
