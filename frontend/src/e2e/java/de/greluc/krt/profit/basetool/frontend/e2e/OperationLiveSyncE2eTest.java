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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that a core-data save on an operation's Verwaltung tab reaches another viewer's header
 * without a reload, via the {@code overview} section of the {@code operation:{id}} room
 * (REQ-FE-015, ADR-0094).
 *
 * <p>Waits for {@code window.krtLiveSync.subscribedTopics()} to be non-empty before mutating.
 */
@Tag("e2e")
class OperationLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The renamed operation name context A saves; context B's header must reflect it live. */
  private static final String RENAMED = "E2E LiveSync Renamed Operation";

  private static Playwright playwright;
  private static Browser browser;
  private static String operationId;

  /** Launches the browser and seeds the user's IRIDIUM membership plus an operation. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      operationId = seeder.createOperation(USERNAME, PASSWORD, "E2E Operation Sync");
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
   * Context A (on the Verwaltung tab) renames the operation and saves; context B — a passive viewer
   * that never reloads — must reflect the new name in its sticky header IN PLACE, driven purely by
   * the change signal over {@code /ws/sync}.
   */
  @Test
  void coreSaveByOneViewerPropagatesToAnotherViewerLive() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext contextA =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState));
        BrowserContext contextB =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState))) {
      Page pageA = contextA.newPage();
      Page pageB = contextB.newPage();
      try {
        E2eSupport.navigate(pageA, baseUrl + "/operations/" + operationId);
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/operations/" + operationId);
        pageB.waitForLoadState();

        assertThat(pageB.locator("#operation-title")).not().containsText(RENAMED);

        pageB.evaluate("window.__krtNoReload = true;");

        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        pageA.locator("#optab-verw").click();
        pageA.locator("#op-name").fill(RENAMED);
        pageA.locator("button[type='submit'][form='operation-form']").click();
        assertThat(pageA.locator("#operation-title"))
            .containsText(RENAMED, new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));

        assertThat(pageB.locator("#operation-title"))
            .containsText(RENAMED, new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "operation-livesync-a");
        E2eSupport.dump(pageB, "operation-livesync-b");
        throw failure;
      }
    }
  }
}
