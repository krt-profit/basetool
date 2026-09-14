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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exemplar for epic #571 / #573 (REQ-FE-005): filtering the missions list swaps the results in
 * place — no page navigation — and keeps the address-bar URL in sync so a refresh re-renders the
 * same filter. Two distinctively-named missions are seeded; filtering by one's token must keep that
 * row and drop the other, prove no reload happened (a window marker survives), and leave the URL
 * carrying the {@code search} parameter.
 */
@Tag("e2e")
class MissionListFilterInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Distinctive tokens so the search matches exactly one seeded mission and no other test's data.
   */
  private static final String MATCH = "E2EListFilterAlphaZ9";

  private static final String OTHER = "E2EListFilterBravoZ9";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds two distinctively-named missions in the user's squadron. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      seeder.createMission(USERNAME, PASSWORD, MATCH + " Mission", true);
      seeder.createMission(USERNAME, PASSWORD, OTHER + " Mission", true);
    }
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
   * Types a distinctive token into the missions search field and asserts the list narrows in place
   * (matching row kept, other dropped), without a page reload, with the {@code search} parameter
   * reflected in the URL.
   */
  @Test
  void filtersMissionsInPlaceWithUrlSyncAndNoReload() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        // showPast=true so a seeded mission whose planned start is not in the future still lists.
        E2eSupport.navigate(page, baseUrl + "/missions?showPast=true");
        page.waitForLoadState();
        E2eSupport.openFilterPanel(page);

        // Mark the live document: a full navigation/reload wipes it.
        page.evaluate("() => { window.__krtNoReload = true; }");

        page.locator("#mission-search").fill(MATCH);

        // The matching mission stays, the other is filtered out — an in-place fragment swap ran.
        assertThat(page.getByText(MATCH + " Mission")).isVisible();
        assertThat(page.getByText(OTHER + " Mission")).hasCount(0);

        // No navigation happened, and the URL reflects the filter (history sync).
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Filtering the missions list must update in place — no page reload.");
        assertThat(page).hasURL(Pattern.compile(".*[?&]search=" + MATCH + ".*"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-list-filter");
        throw failure;
      }
    }
  }
}
