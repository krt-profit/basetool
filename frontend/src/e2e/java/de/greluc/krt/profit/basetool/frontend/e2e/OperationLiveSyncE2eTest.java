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
 * Two-context live-sync coverage for the operation detail page (#1115, REQ-FE-015 / ADR-0092): a
 * core-data save one viewer makes on the Verwaltung tab must appear on another viewer's sticky
 * header without a manual reload — the {@code overview} section key crossing the {@code
 * operation:{id}} room.
 *
 * <p>The operation surface is the first non-mission consumer of the multiplexed {@code /ws/sync}
 * transport, so the deterministic pre-mutation wait is {@code
 * window.krtLiveSync.subscribedTopics()} becoming non-empty (a subscribe, unlike the mission legacy
 * socket, is only registered once its async server authorization has acked). Two browser contexts
 * as the same test user are two distinct {@code /ws/sync} sockets — exactly what the relay fans out
 * between.
 *
 * <p>The payout-toggle path drives the same {@code operation:{id}} receiver but needs a seeded
 * payout row (a linked mission with a checked-in participant and actual times); the core-data save
 * exercises the receiver end-to-end without that setup, so it is the mutation chosen here.
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

        // B starts with the original name (not the renamed one).
        assertThat(pageB.locator("#operation-title")).not().containsText(RENAMED);

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: B is registered with the relay once its operation:{id} subscribe is
        // acked (subscribedTopics non-empty), so A's change frame cannot race past it.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        // Context A renames the operation on the Verwaltung tab and saves.
        pageA.locator("#optab-verw").click();
        pageA.locator("#op-name").fill(RENAMED);
        pageA.locator("button[type='submit'][form='operation-form']").click();
        // A's own sticky header updates in place (sanity: the mutation succeeded).
        assertThat(pageA.locator("#operation-title"))
            .containsText(RENAMED, new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));

        // The assertion under test: context B — which did nothing — reflects the new name in its
        // sticky header, pushed over /ws/sync and applied as an in-place overview swap + header
        // patch.
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
