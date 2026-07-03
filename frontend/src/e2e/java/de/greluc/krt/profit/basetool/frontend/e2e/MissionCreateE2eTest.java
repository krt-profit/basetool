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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow: create a Mission through the UI and verify it shows up in the list.
 *
 * <p>Exercises navigation -> the create form -> submit -> list verification using the stable {@code
 * data-testid} hooks ({@code missions-create-link}, {@code mission-name-input}, {@code
 * mission-row}). Reuses one authenticated session via {@link E2eSupport#authenticatedStorageState}.
 *
 * <p>A mission is staffel-scoped, so the test user needs an OrgUnit membership before the create
 * would be accepted; {@link BackendSeeder} assigns it to the IRIDIUM Squadron in {@link #setUp()}.
 */
@Tag("e2e")
class MissionCreateE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership. */
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
   * Creates a mission via the UI (already authenticated through a reused storageState) and asserts
   * the new mission appears in the missions list.
   */
  @Test
  void createsAMissionThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String missionName = "E2E Test Mission";
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions");
        page.getByTestId("missions-create-link").click();
        // Wait for the create page to finish loading: datetime-splitter.js clears the date/time
        // pickers on DOMContentLoaded (from the empty hidden field), so filling before that init
        // runs would be wiped out — the flaky cause of a silently-blocked submit.
        page.waitForURL(url -> url.contains("/missions/new"));
        page.waitForLoadState();

        page.getByTestId("mission-name-input").fill(missionName);
        // Planned start is the only required datetime (client-side `required` on the date/time
        // pickers, which feed the hidden plannedStartTime field on submit); without it the browser
        // silently blocks the submit. A future date also passes the not-in-the-past check.
        page.getByTestId("mission-start-date")
            .fill(java.time.LocalDate.now().plusDays(7).toString());
        page.getByTestId("mission-start-time").fill("12:00");
        // The save button is bound to the form via the form= attribute and now floats fixed at the
        // bottom-right of the Verwaltung pane, above the fixed footer (REQ-MISSION-015). The
        // footer-safe submit (scroll + dispatch) still submits it robustly regardless of position.
        E2eSupport.clickSubmitClearingFooter(
            page.locator("button[type='submit'][form='mission-form']"));
        page.waitForLoadState();

        // Back on the list, the freshly created mission must be present (auto-waiting assertion).
        // Post-submit GET via the retry helper — WebKit can abort it (HTTP/2 INTERNAL_ERROR) even
        // after the redirect settled. See E2eSupport#navigate.
        E2eSupport.navigate(page, baseUrl + "/missions");
        assertThat(
                page.getByTestId("mission-row")
                    .filter(new Locator.FilterOptions().setHasText(missionName)))
            .isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-create");
        throw failure;
      }
    }
  }
}
