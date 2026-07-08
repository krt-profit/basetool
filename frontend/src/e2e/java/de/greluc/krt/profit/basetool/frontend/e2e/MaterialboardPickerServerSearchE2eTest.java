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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Regression for the Materialbörse "Material anbieten" item picker filtering <em>in the client</em>
 * instead of on the server (REQ-MARKET-002).
 *
 * <p>The backend picker query ({@code findReleasableForUser}) already filters by a material-name
 * fragment and caps the result at 50 rows ordered by material name. The first cut of {@code
 * materialboerse-release.js} loaded that capped list once with an empty query and then filtered it
 * <em>client-side</em> as the user typed — so a material whose rows fall past the alphabetical cap
 * (a user reported "Savrilium" — late alphabet — while "Iron"/"Construction" worked) never appeared
 * in the picker, the item could never be selected, and the release silently no-op'd with no error.
 *
 * <p>The fix debounces a fresh server query per keystroke, so this asserts that typing in the
 * picker issues a {@code /materialboerse/releasable-items?q=…} request — the exact behaviour a
 * regression back to client-side filtering would remove. It does not need a 50+ item seed to
 * reproduce the cap: proving the query goes to the server is what guards the reachability
 * guarantee.
 *
 * <p>The actor is {@code test-admin} (seeded IRIDIUM membership → KRT_MEMBER, the role the board
 * requires).
 */
@Tag("e2e")
class MaterialboardPickerServerSearchE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the actor's IRIDIUM membership. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
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
   * Opens the release dialog and types into the material picker, asserting the keystroke drives a
   * server-side {@code releasable-items?q=…} query rather than a purely client-side filter of the
   * initial, alphabetically-capped list. The initial modal-open request carries no {@code q}, so
   * the predicate specifically waits for the typed query.
   */
  @Test
  void typingInPickerQueriesServer() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materialboerse");
        page.waitForLoadState();
        page.waitForFunction("() => typeof window.krtMaterialRelease === 'object'");

        page.locator("[data-mb-open-release]").first().click();
        assertThat(page.locator("#mb-modal [data-mb-picker-input]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        // Typing must trigger a server query carrying the fragment; the debounced fetch fires
        // within
        // ~200 ms, well inside waitForRequest's default budget.
        Request search =
            page.waitForRequest(
                request ->
                    request.url().contains("/materialboerse/releasable-items")
                        && request.url().contains("q="),
                () -> page.locator("#mb-modal [data-mb-picker-input]").fill("savrilium"));
        assertTrue(
            search.url().contains("q=savrilium"),
            "picker search must send the typed fragment to the server, got: " + search.url());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-picker-server-search");
        throw failure;
      }
    }
  }
}
