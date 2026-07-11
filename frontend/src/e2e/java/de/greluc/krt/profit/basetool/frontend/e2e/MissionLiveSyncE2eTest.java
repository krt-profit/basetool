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
 * End-to-end coverage for live multi-user mission sync (REQ-FE-010 / ADR-0031): a change one viewer
 * makes to a mission must appear on every other open mission-detail view without a manual reload.
 *
 * <p>The relay broadcasts a {@code {"type":"changed","sections":[…]}} frame over the existing
 * presence WebSocket to every <em>other</em> socket on the mission and excludes the originating
 * <em>session</em> (not the user). This test therefore opens the same mission in <b>two browser
 * contexts</b> authenticated as the same test user — two distinct WebSocket sessions, exactly what
 * the relay fans out between — which exercises the full path (mutation in context A → relay →
 * context B re-fetches the crew fragment in place → the out-of-fragment header counts patch)
 * without needing a second seeded user. A genuine second user would hit the identical handler
 * branch.
 *
 * <p>A mission is staffel-scoped, so the user is assigned to the IRIDIUM Squadron first (mirrors
 * {@link MissionParticipantCountE2eTest}). The distinctive guest name resolves to no realm user, so
 * the add stays on the guest path.
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

        // Both views start with no participants.
        assertThat(pageB.locator("#facts-registered")).hasText("0");

        // A full reload on B would clear this marker; the live in-place swap leaves it intact,
        // which
        // is the whole point — B must update WITHOUT reloading.
        pageB.evaluate("window.__krtNoReload = true;");

        // Wait until B holds an ACKNOWLEDGED subscription to the mission room on the shared
        // /ws/sync
        // socket rather than sleeping a fixed interval before a race: a subscribe only appears in
        // subscribedTopics() once the server has authorized it and acked, which means B is
        // registered with the relay, so A's subsequent change frame deterministically reaches it.
        // This is the /ws/sync analogue of the retired missionPresence.socket.readyState wait
        // (#1236).
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().indexOf('mission:"
                            + missionId
                            + "') !== -1)")));

        // Context A adds a guest. A distinctive name resolves to no realm user, so it is a guest.
        pageA.locator("#add-participant-btn").click();
        assertThat(pageA.locator("#participant-modal")).isVisible();
        pageA.locator("#participant-search-input").fill(GUEST_PARTICIPANT_NAME);
        pageA.locator("#add-participant-form button[type='submit']").click();
        // A's own view updates in place (sanity: the mutation succeeded).
        assertThat(pageA.locator("#facts-registered"))
            .hasText("1", new LocatorAssertions.HasTextOptions().setTimeout(20_000));

        // The assertion under test: context B — which did nothing — reflects the new participant in
        // its header count, pushed over the presence WebSocket and applied as an in-place crew
        // swap.
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
   * Same two-context setup, but for the Ziele objectives editor: context A adds an objective on the
   * Verwaltung tab; context B — a passive viewer that never reloads — must gain the new objective
   * row in its (backgrounded) editor IN PLACE. This pins the {@code objectives} section key across
   * the full path (broadcast → relay whitelist → receiver container map): the key was once dropped
   * at the relay AND missing from the receiver, so peers' Ziele stayed stale until a manual reload
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
        // A lands on the Verwaltung tab (deeplink) where the objectives editor is interactable;
        // B stays on the default tab — its objectives container is in the DOM but backgrounded,
        // which is exactly the state the live-sync swap must still reach.
        E2eSupport.navigate(pageA, baseUrl + "/missions/" + missionId + "?tab=verw");
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/missions/" + missionId);
        pageB.waitForLoadState();

        int before = pageB.locator("#mission-objective-list .ae-row").count();

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Same deterministic wait as the participant test: an acked mission-room subscription on
        // /ws/sync implies B is registered with the relay, so A's change frame cannot race past it.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().indexOf('mission:"
                            + missionId
                            + "') !== -1)")));

        // Context A adds an objective (created with the localized default title + PRIMARY kind).
        pageA.locator("#mission-objective-add").click();
        // A's own editor re-renders in place (sanity: the mutation succeeded).
        assertThat(pageA.locator("#mission-objective-list .ae-row"))
            .hasCount(before + 1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));

        // The assertion under test: context B — which did nothing — gains the new objective row,
        // pushed over the presence WebSocket and applied as an in-place objectives swap.
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
