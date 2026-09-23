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

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Guards the deferred load order of FE-PERF-05 (REQ-FE-023) on every page route: after load, the
 * shared scripts are installed, the {@code krtEvents} bootstrap stub has been replaced, no script
 * threw, and the page reported nothing to the client-error beacon.
 *
 * <p>This load order has regressed three times, always the same way: an inline script called a
 * shared global before the file defining it had run, a guard like {@code if (window.krtFetch)} made
 * the call a silent no-op, and a control simply did nothing. Deferring every external script widens
 * exactly that window — an inline script now always runs before {@code krt-fetch.js} — so the
 * structural half ({@code InlineScriptLoadOrderTest}, no top-level call in an inline script) is
 * paired with this runtime half, which asks the browser what actually got installed.
 *
 * <p>The two signals it reads are the ones the app already has for this failure in production:
 * {@code krt-client-error.js} beacons every uncaught error and every script that failed to load to
 * {@code POST /internal/client-error}, and the head stub throws — so the beacon counts it — when
 * {@code event-delegation.js} has not replaced it five seconds after load.
 */
@Tag("smoke")
@Tag("e2e")
class ScriptLoadOrderE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Evaluated in the page after load: the name of every shared global that is missing, plus a
   * marker when the {@code krtEvents} stub is still the bootstrap one.
   */
  private static final String PROBE =
      """
      () => {
        const missing = [];
        const globals = ['krtFetch', 'krtCsrf', 'krtLiveSync', 'krtSearchableSelect',
                         'krtComboboxRemoteSources', 'krtI18nText', 'escapeHtml'];
        for (const name of globals) {
          if (window[name] === undefined || window[name] === null) {
            missing.push(name);
          }
        }
        if (!window.krtEvents || window.krtEvents._isBootstrapStub) {
          missing.push('krtEvents (bootstrap stub still in place)');
        }
        return missing;
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated session reused across all pages. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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
   * Loads one page route and asserts the deferred scripts installed everything and nothing failed.
   *
   * @param path the app-relative path of the page to load
   */
  @ParameterizedTest(name = "scripts load in order on {0}")
  @FieldSource("de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes#CORE_SMOKE")
  void sharedScriptsAreInstalledAndNothingFailed(String path) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      List<String> pageErrors = new CopyOnWriteArrayList<>();
      List<String> beacons = new CopyOnWriteArrayList<>();
      page.onPageError(pageErrors::add);
      page.onRequest(
          request -> {
            if (request.url().contains("/internal/client-error")) {
              beacons.add(String.valueOf(request.postData()));
            }
          });
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + path);
        page.waitForLoadState();
        List<?> missing = (List<?>) page.evaluate(PROBE);

        assertThat(missing)
            .as("shared globals missing after load on %s — a deferred script did not run", path)
            .isEmpty();
        assertThat(pageErrors).as("uncaught script errors on %s", path).isEmpty();
        assertThat(beacons).as("client-error beacons sent by %s", path).isEmpty();
      } catch (RuntimeException | AssertionError failure) {
        String slug = path.equals("/") ? "home" : path.substring(1).replace('/', '-');
        E2eSupport.dump(page, "script-order-" + slug);
        throw failure;
      }
    }
  }
}
