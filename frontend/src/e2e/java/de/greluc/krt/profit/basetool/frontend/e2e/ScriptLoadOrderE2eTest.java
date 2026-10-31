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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
 * Guards the deferred script load order (REQ-FE-023) on every page route: after load, the shared
 * scripts are installed, the {@code krtEvents} bootstrap stub has been replaced, no script threw,
 * and nothing reached the client-error beacon.
 *
 * <p>Runtime counterpart of {@code InlineScriptLoadOrderTest}. It reads the {@code POST
 * /internal/client-error} beacon, which also counts the head stub's throw when {@code
 * event-delegation.js} has not replaced it five seconds after load.
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
                         'krtComboboxRemoteSources', 'krtI18nText', 'krtHtml'];
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

  /** The name Playwright gives the WebKit engine. */
  private static final String WEBKIT = "webkit";

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
   * Whether a beacon is the {@code style-src-attr} violation WebKit 27 reports for the user-agent
   * shadow tree of every native {@code <select>}: no source file, nothing the page wrote. A real
   * inline {@code style} attribute in a template still fails the Chromium and Firefox runs.
   *
   * @param payload the beacon's JSON body
   * @return {@code true} for exactly that violation
   */
  private static boolean isWebkitSelectShadowViolation(String payload) {
    JsonObject beacon;
    try {
      beacon = JsonParser.parseString(payload).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException _) {
      return false;
    }
    return "csp_violation".equals(text(beacon, "kind"))
        && "style-src-attr".equals(text(beacon, "message"))
        && "inline".equals(text(beacon, "source"))
        && "inline".equals(text(beacon, "codeOrigin"));
  }

  /**
   * Reads one string member of a beacon.
   *
   * @param beacon the parsed beacon
   * @param name the member name
   * @return the member's text, or {@code null} when it is absent or not a string
   */
  private static String text(JsonObject beacon, String name) {
    JsonElement value = beacon.get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
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
      boolean webkit = WEBKIT.equals(browser.browserType().name());
      page.onRequest(request -> {
        String payload = String.valueOf(request.postData());
        if (request.url().contains("/internal/client-error")
            && !(webkit && isWebkitSelectShadowViolation(payload))) {
          beacons.add(payload);
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
