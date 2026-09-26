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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the mission core-edit form (Verwaltung tab) saves in place: no full-page reload, no
 * 409 on a second consecutive save, and inline rendering of a server-side validation failure.
 *
 * <p>Drives the UI and verifies via {@link BackendSeeder}, as {@code test-admin}.
 */
@Tag("e2e")
class MissionCoreEditInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership + a mission.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Core Edit Mission", true);
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
   * Edits the mission name on the Verwaltung tab and saves twice in a row: the first save persists
   * without a reload, and the second save succeeds without a 409 — proving the twin's four-version
   * writeback kept the form's optimistic-lock counters fresh.
   */
  @Test
  void savesCoreEditInPlaceAndDoubleSaveDoesNotConflict() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String firstName = "E2E Edited " + UUID.randomUUID();
    String secondName = "E2E Re-edited " + UUID.randomUUID();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=verw");
        page.waitForLoadState();

        page.locator("[data-testid='mission-name-input']").fill(firstName);
        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            response ->
                response.url().endsWith("/missions/" + missionId)
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='mission-form'][type='submit']").click());
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the core-edit save must update in place — no full-page reload cleared the window"
                + " marker");
        assertEquals(firstName, missionName(), "the first edit must persist");

        page.locator("[data-testid='mission-name-input']").fill(secondName);
        page.waitForResponse(
            response ->
                response.url().endsWith("/missions/" + missionId)
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='mission-form'][type='submit']").click());
        assertEquals(
            secondName,
            missionName(),
            "the second consecutive save must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-core-edit");
        throw failure;
      }
    }
  }

  /**
   * A server-side validation failure (a non-https calendar link, which the browser's {@code
   * type=url} accepts but the {@code @Pattern} rejects) renders the inline field error without a
   * navigation, then clears on a valid re-save.
   */
  @Test
  void invalidCalendarLinkShowsInlineErrorWithoutReload() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=verw");
        page.waitForLoadState();

        page.locator("input[name='calendarLink']").fill("http://example.com/not-https");
        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            response ->
                response.url().endsWith("/missions/" + missionId)
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='mission-form'][type='submit']").click());

        assertThat(page.locator(".field-error[data-error-for='calendarLink']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the validation error must render inline — no full-page reload cleared the window"
                + " marker");

        page.locator("input[name='calendarLink']").fill("https://example.com/ok");
        page.waitForResponse(
            response ->
                response.url().endsWith("/missions/" + missionId)
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='mission-form'][type='submit']").click());
        assertThat(page.locator(".field-error[data-error-for='calendarLink']")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-core-edit-validation");
        throw failure;
      }
    }
  }

  /**
   * Reads the seeded mission's current name straight from the backend ({@code GET
   * /api/v1/missions/{id}}), so persistence assertions don't race the client's in-place save.
   *
   * @return the mission's current {@code name}
   */
  private static String missionName() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/missions/" + missionId);
    return JsonParser.parseString(body).getAsJsonObject().get("name").getAsString();
  }
}
