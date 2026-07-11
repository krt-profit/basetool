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
 * Two-context live-sync coverage for the mission Verwaltung <em>Organisation</em> panel (#1120,
 * REQ-FE-015 / ADR-0094): a party-lead change one viewer makes on the Verwaltung tab must appear on
 * another viewer's (default-tab) overview without a manual reload — the {@code organisation}/{@code
 * overview} section keys crossing the mission relay.
 *
 * <p>Mission detail rides the shared {@code /ws/sync} socket via the {@code missionPresence}
 * adapter's {@code mission:{id}} subscription (the legacy per-mission socket was removed in #1236),
 * so the deterministic pre-mutation wait is the same acked-subscription check ({@code
 * window.krtLiveSync.subscribedTopics()} contains {@code mission:{id}}) the shipped {@link
 * MissionLiveSyncE2eTest} anchor uses. Two browser contexts authenticated as the same test user are
 * two distinct sockets — exactly what the relay fans out between. A distinctive party-lead name
 * resolves to no realm user, so the set stays on the guest path (no second member needed),
 * mirroring the anchor's guest-participant approach.
 */
@Tag("e2e")
class MissionOrganisationLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Party-lead guest name matching no realm user, so the set stays on the guest path. */
  private static final String GUEST_LEAD_NAME = "E2E LiveSync Lead";

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /** Launches the browser and seeds the user's IRIDIUM membership plus a mission. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Organisation Sync Mission", true);
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
   * Context A (on the Verwaltung tab) sets the party lead to a guest; context B — a passive viewer
   * on the default tab that never reloads — must reflect the new party lead in its overview IN
   * PLACE, driven purely by the mission change signal over the presence socket.
   */
  @Test
  void partyLeadSetByOneViewerPropagatesToAnotherViewerLive() {
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
        // A lands on the Verwaltung tab where the party-lead form is interactable; B stays on the
        // default tab, where the overview's #overview-party-lead is visible and must update live.
        E2eSupport.navigate(pageA, baseUrl + "/missions/" + missionId + "?tab=verw");
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/missions/" + missionId);
        pageB.waitForLoadState();

        // B starts with no party lead ("Keine" / none).
        assertThat(pageB.locator("#overview-party-lead")).not().hasText(GUEST_LEAD_NAME);

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: an acked mission-room subscription on /ws/sync implies B is
        // registered
        // with the relay, so A's subsequent change frame cannot race past it (anchor semantics).
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().indexOf('mission:"
                            + missionId
                            + "') !== -1)")));

        // Context A sets a guest party lead through the Organisation panel form.
        pageA.locator("#party-lead-search-input").fill(GUEST_LEAD_NAME);
        pageA.locator("#party-lead-form button[type='submit']").click();
        // A's own panel updates in place (sanity: the mutation succeeded).
        assertThat(pageA.locator("#party-lead-display"))
            .containsText(
                GUEST_LEAD_NAME, new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));

        // The assertion under test: context B — which did nothing — shows the new party lead in its
        // overview, pushed over the presence WebSocket and applied as an in-place overview swap.
        assertThat(pageB.locator("#overview-party-lead"))
            .containsText(
                GUEST_LEAD_NAME, new LocatorAssertions.ContainsTextOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "mission-organisation-livesync-a");
        E2eSupport.dump(pageB, "mission-organisation-livesync-b");
        throw failure;
      }
    }
  }
}
