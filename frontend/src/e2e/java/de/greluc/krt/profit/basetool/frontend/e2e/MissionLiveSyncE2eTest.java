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
 * Verifies live multi-user mission sync (REQ-FE-010, ADR-0031): a crew change in one browser
 * context appears in another open mission-detail view without a reload.
 *
 * <p>Two contexts of the same user are two WebSocket sessions, which is what the relay fans out
 * between. The user is assigned to the IRIDIUM Squadron first; the guest name keeps the add on the
 * guest path.
 */
@Tag("e2e")
class MissionLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Guest name matching no realm test user, so the add-participant call stays on the guest path.
   */
  private static final String GUEST_PARTICIPANT_NAME = "E2E LiveSync Guest";

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /**
   * Launches the browser and seeds the user's IRIDIUM membership plus a participant-free mission.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Live Sync Mission", true);
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
   * Opens the same mission in two contexts. Context A adds a guest participant through the modal;
   * context B — a passive viewer that never reloads — must reflect the new participant in its
   * header count IN PLACE, driven purely by the WebSocket change signal.
   */
  @Test
  void participantAddByOneViewerPropagatesToAnotherViewerLive() {
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
        E2eSupport.navigate(pageA, baseUrl + "/missions/" + missionId);
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/missions/" + missionId);
        pageB.waitForLoadState();

        assertThat(pageB.locator("#facts-registered")).hasText("0");

        pageB.evaluate("window.__krtNoReload = true;");

        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().indexOf('mission:"
                            + missionId
                            + "') !== -1)")));

        pageA.locator("#add-participant-btn").click();
        assertThat(pageA.locator("#participant-modal")).isVisible();
        pageA.locator("#participant-search-input").fill(GUEST_PARTICIPANT_NAME);
        pageA.locator("#add-participant-form button[type='submit']").click();
        assertThat(pageA.locator("#facts-registered"))
            .hasText("1", new LocatorAssertions.HasTextOptions().setTimeout(20_000));

        assertThat(pageB.locator("#facts-registered"))
            .hasText("1", new LocatorAssertions.HasTextOptions().setTimeout(20_000));
        assertThat(pageB.locator("#tab-crew .tab-count")).hasText("0/1");
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "mission-livesync-a");
        E2eSupport.dump(pageB, "mission-livesync-b");
        throw failure;
      }
    }
  }

  /**
   * Verifies that an objective added on the Verwaltung tab in one context appears in place in
   * another context's Ziele editor, pinning the {@code objectives} section key end to end
   * (REQ-FE-010).
   */
  @Test
  void objectiveAddByOneViewerPropagatesToAnotherViewerLive() {
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
        E2eSupport.navigate(pageA, baseUrl + "/missions/" + missionId + "?tab=verw");
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/missions/" + missionId);
        pageB.waitForLoadState();

        int before = pageB.locator("#mission-objective-list .ae-row").count();

        pageB.evaluate("window.__krtNoReload = true;");

        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().indexOf('mission:"
                            + missionId
                            + "') !== -1)")));

        pageA.locator("#mission-objective-add").click();
        assertThat(pageA.locator("#mission-objective-list .ae-row"))
            .hasCount(before + 1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));

        assertThat(pageB.locator("#mission-objective-list .ae-row"))
            .hasCount(before + 1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "mission-livesync-objectives-a");
        E2eSupport.dump(pageB, "mission-livesync-objectives-b");
        throw failure;
      }
    }
  }
}
