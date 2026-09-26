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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that the owner change on the Verwaltung tab is optimistically locked via {@code
 * ownershipVersion}: a change saves in place and moves the counter, and a change with a stale
 * counter is refused with 409.
 *
 * <p>Drives the UI and verifies via {@link BackendSeeder}, as {@code test-admin}.
 */
@Tag("e2e")
class MissionOwnerChangeE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String OFFICER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;
  private static String memberId;
  private static String officerId;

  /**
   * Launches the browser and, on the ephemeral stack, seeds a mission and two owner candidates.
   *
   * <p>Only the admin is homed in IRIDIUM; the candidates are materialised as {@code app_user} rows
   * by logging in once, since {@link BackendSeeder#ensureIridiumMembership} fails for a non-admin
   * without a Staffel.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      memberId = seeder.getUserId(MEMBER, MEMBER_PASSWORD);
      officerId = seeder.getUserId(OFFICER, OFFICER_PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Owner Change Mission", false);
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
   * Hands the mission to the member (in place, counter moves to 1), then replays a change with the
   * pre-change counter 0 — the second manager who opened the page earlier — and expects the 409.
   */
  @Test
  void ownerChangeMovesTheCounterAndAStaleCounterIsRefused() {
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
        assertEquals("0", ownerRowVersion(page), "a never-transferred mission starts at 0");

        page.evaluate("window.__krtNoReload = true;");
        Response first = changeOwner(page, memberId, MEMBER);
        assertEquals(200, first.status(), "the owner change must succeed");
        assertEquals(memberId, ownerId(), "the new owner must be persisted");
        page.waitForFunction(
            "() => document.getElementById('owner-row')"
                + " && document.getElementById('owner-row').getAttribute('data-ownership-version')"
                + " === '1'");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the owner change must update in place — no full-page reload");

        page.evaluate(
            "document.getElementById('owner-row').setAttribute('data-ownership-version', '0')");
        Response stale = changeOwner(page, officerId, OFFICER);
        assertEquals(409, stale.status(), "a stale ownership version must be refused with 409");
        assertEquals(memberId, ownerId(), "the refused change must leave the owner untouched");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-owner-change");
        throw failure;
      }
    }
  }

  /**
   * Picks a candidate in the owner combobox, saves, confirms the dialog and returns the answer of
   * the owner-change write.
   *
   * @param page the mission detail page on the Verwaltung tab
   * @param userId the candidate's app-user id (the option value)
   * @param searchText what to type so the server-side search renders the candidate
   * @return the response of {@code PUT /missions/{id}/owner/ajax}
   */
  private static Response changeOwner(Page page, String userId, String searchText) {
    E2eSupport.selectComboboxByValue(
        page.locator("[data-testid='mission-owner-picker']"), userId, searchText);
    page.locator("[data-trigger='mission-change-owner']").click();
    Locator ok = page.locator(".krt-confirm-ok");
    return page.waitForResponse(
        response ->
            response.url().endsWith("/missions/" + missionId + "/owner/ajax")
                && "PUT".equals(response.request().method()),
        ok::click);
  }

  /**
   * Reads the counter the owner row currently carries.
   *
   * @param page the mission detail page
   * @return the {@code data-ownership-version} attribute
   */
  private static String ownerRowVersion(Page page) {
    return page.locator("#owner-row").getAttribute("data-ownership-version");
  }

  /**
   * Reads the mission's current owner straight from the backend, so the assertion does not race the
   * page's in-place update.
   *
   * @return the owner's app-user id
   */
  private static String ownerId() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/missions/" + missionId);
    return JsonParser.parseString(body)
        .getAsJsonObject()
        .getAsJsonObject("owner")
        .get("id")
        .getAsString();
  }
}
