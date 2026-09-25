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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The mission page's two free-text user autocompletes (participant add, party lead) announce an
 * overflow instead of looking complete (REQ-FE-016).
 *
 * <p>The {@code /users/search} relay fetches {@code PickerSearch.PAGE_SIZE} (51) rows; the lists
 * render 50 and show the "keep typing" row when the 51st arrives. The e2e realm has a handful of
 * users, so the relay is answered by a Playwright route with a synthetic roster: what is under test
 * is the browser half — the render cap and the hint — while the page size itself is pinned by
 * {@code UserProxyControllerTest} and the cap parity by {@code PickerSearchLimitsParityTest}.
 */
@Tag("e2e")
class MissionUserAutocompleteOverflowE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Rows the autocompletes render at most; mirrors {@code USER_SEARCH_RENDER_CAP}. */
  private static final int RENDER_CAP = 50;

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /** Launches the browser and seeds the user's IRIDIUM membership plus an editable mission. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId =
          seeder.createMission(USERNAME, PASSWORD, "E2E Autocomplete Overflow Mission", true);
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
   * Builds a {@code /users/search} response of {@code count} synthetic users, each named so the
   * typed term matches.
   *
   * @param count how many users the relay returns
   * @return the JSON array body
   */
  private static String roster(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                "{\"id\":\""
                    + UUID.randomUUID()
                    + "\",\"username\":\"overflow"
                    + i
                    + "\",\"effectiveName\":\"Overflow Pilot "
                    + i
                    + "\"}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  /**
   * Answers every {@code /users/search} call in the context with {@code count} users.
   *
   * @param context the browser context whose relay calls are intercepted
   * @param count how many users each call returns
   */
  private static void stubUserSearch(BrowserContext context, int count) {
    String body = roster(count);
    context.route(
        "**/users/search?**",
        (Route route) ->
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody(body)));
  }

  /**
   * The hint text the page provides ({@code krtComboboxI18n.hint}), read from the page itself so
   * the assertion holds in either locale.
   *
   * @param page the loaded mission page
   * @return the localized hint
   */
  private static String hintText(Page page) {
    return (String) page.evaluate("window.krtComboboxI18n.hint");
  }

  /**
   * Fifty-one users back: the participant list renders fifty of them plus the non-selectable hint.
   */
  @Test
  void participantAutocompleteCapsTheListAndAnnouncesTheOverflow() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      stubUserSearch(context, RENDER_CAP + 1);
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId);
        page.waitForLoadState();

        page.locator("#add-participant-btn").click();
        assertThat(page.locator("#participant-modal")).isVisible();
        page.locator("#participant-search-input").fill("Overflow");

        assertThat(page.locator("#participant-search-results .autocomplete-notice"))
            .hasText(hintText(page), new LocatorAssertions.HasTextOptions().setTimeout(20_000));
        assertThat(page.locator("#participant-search-results > div:not(.autocomplete-notice)"))
            .hasCount(RENDER_CAP);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-participant-autocomplete-overflow");
        throw failure;
      }
    }
  }

  /**
   * The party-lead autocomplete on the Verwaltung tab: the same cap and hint, and no hint at all
   * when the result fits — the row must mean "there are more", never appear unconditionally.
   */
  @Test
  void partyLeadAutocompleteShowsTheHintOnlyOnOverflow() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      stubUserSearch(context, RENDER_CAP + 1);
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=verw");
        page.waitForLoadState();

        page.locator("#party-lead-search-input").fill("Overflow");
        assertThat(page.locator("#party-lead-search-results .autocomplete-notice"))
            .hasText(hintText(page), new LocatorAssertions.HasTextOptions().setTimeout(20_000));
        assertThat(page.locator("#party-lead-search-results > div:not(.autocomplete-notice)"))
            .hasCount(RENDER_CAP);

        context.unrouteAll();
        stubUserSearch(context, 3);
        page.locator("#party-lead-search-input").fill("Overflow P");
        assertThat(page.locator("#party-lead-search-results > div"))
            .hasCount(3, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertThat(page.locator("#party-lead-search-results .autocomplete-notice")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-party-lead-autocomplete-overflow");
        throw failure;
      }
    }
  }
}
