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
 * Regression coverage for the stale participant-count displays on the mission-detail page (epic
 * #571 / #574): adding a participant re-renders only the crew-board fragment (#crew-board-results),
 * but the participant counts shown in the page header — the facts-bar "Teilnehmer" value and the
 * crew tab badge "checkedIn/registered" — live OUTSIDE that fragment, so before the fix they kept
 * their page-load value until a full reload (the backend count was correct; only the UI was stale).
 *
 * <p>The fix carries the fresh counts inside the crewBoard fragment ({@code #crew-count-meta}) and
 * a {@code krt:swapped} listener patches the out-of-fragment {@code #facts-registered}, {@code
 * #facts-checked-in} and {@code #tab-crew .tab-count} after every crew swap — the generalisation of
 * the finance-badge precedent. This test seeds a participant-free mission, adds a guest through the
 * UI, and asserts the header count and tab badge update IN PLACE (no full reload).
 *
 * <p>A mission is staffel-scoped, so the user is assigned to the IRIDIUM Squadron first (mirrors
 * {@link MissionFinanceEntryE2eTest}). The distinctive guest name resolves to no realm user, so the
 * add stays on the guest path.
 */
@Tag("e2e")
class MissionParticipantCountE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Guest name matching no realm test user, so the add-participant call stays on the guest path.
   */
  private static final String GUEST_PARTICIPANT_NAME = "E2E Count Guest";

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
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Participant Count Mission", true);
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
   * Opens a participant-free mission (header shows Teilnehmer 0 and the crew tab badge 0/0), adds a
   * guest through the add-participant modal, and asserts the header facts-bar count and the crew
   * tab badge update IN PLACE to 1 and 0/1 — without a full-page reload (the {@code
   * window.__krtNoReload} marker survives).
   */
  @Test
  void addingParticipantUpdatesHeaderCountsInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId);
        page.waitForLoadState();

        assertThat(page.locator("#facts-registered")).hasText("0");
        assertThat(page.locator("#tab-crew .tab-count")).hasText("0/0");

        page.evaluate("window.__krtNoReload = true;");

        page.locator("#add-participant-btn").click();
        assertThat(page.locator("#participant-modal")).isVisible();
        page.locator("#participant-search-input").fill(GUEST_PARTICIPANT_NAME);
        page.locator("#add-participant-form button[type='submit']").click();

        assertThat(page.locator("#facts-registered"))
            .hasText("1", new LocatorAssertions.HasTextOptions().setTimeout(20_000));
        assertThat(page.locator("#tab-crew .tab-count")).hasText("0/1");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "adding a participant must update the header counts in place — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-participant-count");
        throw failure;
      }
    }
  }
}
