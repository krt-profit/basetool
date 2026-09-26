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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Read-only smoke suite: logs in once and asserts each route of {@link
 * FrontendPageRoutes#CORE_SMOKE} renders the authenticated app shell ({@code nav-logout}).
 *
 * <p>Safe against a shared staging deployment; the {@code smokeTest} Gradle task runs it against
 * the base URL {@link E2eStackExtension} resolves.
 */
@Tag("smoke")
class CorePagesSmokeE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated session reused across all page checks. */
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
   * Navigates to a core page with the authenticated session and asserts the authenticated sidebar
   * renders, proving the page loads for a logged-in user without touching any data.
   *
   * @param path the app-relative path of the core page to load
   */
  @ParameterizedTest(name = "core page {0} loads")
  @FieldSource("de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes#CORE_SMOKE")
  void corePageLoads(String path) {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + path);
        assertThat(page.getByTestId("nav-logout")).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        String slug = path.equals("/") ? "home" : path.substring(1).replace('/', '-');
        E2eSupport.dump(page, "smoke-" + slug);
        throw failure;
      }
    }
  }
}
