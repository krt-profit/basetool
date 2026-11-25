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
        page.waitForURL(url -> url.contains("/missions/new"));
        page.waitForLoadState();

        page.getByTestId("mission-name-input").fill(missionName);
        page.getByTestId("mission-start-date")
            .fill(java.time.LocalDate.now().plusDays(7).toString());
        page.getByTestId("mission-start-time").fill("12:00");
        assertEquals(201, E2eSupport.submitMissionDraft(page));
        page.waitForURL(url -> url.contains("?tab=verw"));

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

  /**
   * Reloads a half-filled mission create form with an objective and a step, restores the offered
   * draft and saves it (REQ-FE-041): the fields and the planning rows come back, and the save
   * removes the draft.
   */
  @Test
  void reloadOffersTheMissionDraftBackWithItsPlanningRows() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String startDate = java.time.LocalDate.now().plusDays(7).toString();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/new");
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isHidden();

        page.getByTestId("mission-name-input").fill("E2E Draft Mission");
        page.getByTestId("mission-start-date").fill(startDate);
        page.getByTestId("mission-start-time").fill("19:30");
        page.locator("#mission-description").fill("Drafted briefing");
        page.locator("#mission-create-objective-add").click();
        page.locator("#mission-create-objective-list .ae-title").last().fill("Hold the relay");
        page.locator("#mission-create-step-add").click();
        page.locator("#mission-create-step-list .ae-title").last().fill("Muster at Port Olisar");
        page.getByTestId("mission-name-input").click();
        page.waitForFunction(
            "() => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
                + " && k.includes('mission-create')"
                + " && sessionStorage.getItem(k).includes('Hold the relay')"
                + " && sessionStorage.getItem(k).includes('Muster at Port Olisar'))");

        page.reload();
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isVisible();
        assertThat(page.locator("#mission-create-objective-list .ae-row")).hasCount(0);
        page.getByTestId("draft-restore").click();

        assertThat(page.getByTestId("draft-offer")).isHidden();
        assertThat(page.getByTestId("mission-name-input")).hasValue("E2E Draft Mission");
        assertThat(page.getByTestId("mission-start-date")).hasValue(startDate);
        assertThat(page.getByTestId("mission-start-time")).hasValue("19:30");
        assertThat(page.locator("#mission-description")).hasValue("Drafted briefing");
        assertThat(page.locator("#mission-create-objective-list .ae-title"))
            .hasValue("Hold the relay");
        assertThat(page.locator("#mission-create-step-list .ae-title"))
            .hasValue("Muster at Port Olisar");

        assertEquals(201, E2eSupport.submitMissionDraft(page));
        page.waitForURL(url -> url.contains("?tab=verw"));
        page.waitForLoadState();
        Object left =
            page.evaluate(
                "() => Object.keys(sessionStorage).filter((k) =>"
                    + " k.startsWith('krt-draft:')).length");
        assertEquals(0, ((Number) left).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-create-draft");
        throw failure;
      }
    }
  }
}
