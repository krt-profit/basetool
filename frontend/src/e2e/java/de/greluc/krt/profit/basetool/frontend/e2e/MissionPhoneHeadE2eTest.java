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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ReducedMotion;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the compact mission head on the phone class: the head scrolls away, the tabs stay pinned
 * and the condensed mini bar takes over (REQ-MISSION-033).
 */
@Tag("e2e")
class MissionPhoneHeadE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Phone viewport width of REQ-UI-009. */
  private static final int PHONE_WIDTH = 375;

  private static final int PHONE_HEIGHT = 812;

  /** Tallest a one-line facts bar or tab row may render, in CSS pixels. */
  private static final int ONE_ROW_MAX_PX = 60;

  /** Scroll distance that carries the head past the pinned dock, in CSS pixels. */
  private static final int SCROLL_PAST_HEAD_PX = 900;

  /** Room appended below the panes so the page can always scroll the head away, in CSS pixels. */
  private static final int SCROLL_ROOM_PX = 3000;

  /** Matches the dock's class list once the mini bar has taken over. */
  private static final Pattern CONDENSED = Pattern.compile("\\bis-condensed\\b");

  /** Tolerance for sub-pixel layout positions, in CSS pixels. */
  private static final double LAYOUT_EPSILON_PX = 1.5;

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /** Launches the browser and, for the ephemeral stack, seeds one mission to open. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Phone Head Mission", true);
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
   * At the top the facts and the tabs each take one row and the mini bar is hidden; scrolled down,
   * the head is gone, the dock sits right below the mini bar and the mini bar shows the mission.
   */
  @Test
  void theHeadScrollsAwayAndTheMiniBarTakesOver() {
    withMissionOnAPhone(page -> {
      assertTrue(height(page, ".facts-bar") <= ONE_ROW_MAX_PX, "facts bar is one row");
      assertTrue(height(page, "#mission-head-dock .tab-nav") <= ONE_ROW_MAX_PX, "tabs are one row");
      assertThat(page.locator("#mission-head-mini")).isHidden();

      page.evaluate("y => window.scrollTo(0, y)", SCROLL_PAST_HEAD_PX);
      assertThat(page.locator("#mission-head-dock")).hasClass(CONDENSED);
      assertThat(page.locator("#mission-head-mini")).isVisible();
      assertThat(page.locator("#mission-head-mini-title")).hasText("E2E Phone Head Mission");

      @SuppressWarnings("unchecked")
      Map<String, Object> box =
          (Map<String, Object>)
              page.evaluate(
                  """
                  () => {
                    const mini = document.getElementById('mission-head-mini');
                    const dock = document.getElementById('mission-head-dock');
                    const head = document.querySelector('.mission-head-sticky > .page-head');
                    return {
                      miniBottom: mini.getBoundingClientRect().bottom,
                      dockTop: dock.getBoundingClientRect().top,
                      headBottom: head.getBoundingClientRect().bottom,
                      miniTop: mini.getBoundingClientRect().top
                    };
                  }
                  """);
      assertEquals(
          number(box, "dockTop"),
          number(box, "miniBottom"),
          LAYOUT_EPSILON_PX,
          "mini sits on dock");
      assertTrue(
          number(box, "headBottom") <= number(box, "miniTop") + LAYOUT_EPSILON_PX,
          "page head scrolled away: " + box);
    });
  }

  /** The mini bar's sign-up opens the participant dialog and its title scrolls back to the top. */
  @Test
  void theMiniBarSignsUpAndReturnsToTheTop() {
    withMissionOnAPhone(page -> {
      page.evaluate("y => window.scrollTo(0, y)", SCROLL_PAST_HEAD_PX);
      assertThat(page.locator("#mission-head-mini")).isVisible();

      page.locator("#mission-head-mini-cta").click();
      assertThat(page.locator("#participant-modal")).isVisible();
      page.keyboard().press("Escape");
      assertThat(page.locator("#participant-modal")).isHidden();

      page.locator("#mission-head-mini-title").click();
      page.waitForFunction("() => window.scrollY === 0");
      assertThat(page.locator("#mission-head-mini")).isHidden();
    });
  }

  /**
   * Reads the rendered height of the first element matching the selector.
   *
   * @param page the opened mission
   * @param selector the CSS selector
   * @return the element's height in CSS pixels
   */
  private static double height(@NotNull Page page, @NotNull String selector) {
    return ((Number)
            page.evaluate(
                "sel => document.querySelector(sel).getBoundingClientRect().height", selector))
        .doubleValue();
  }

  /**
   * Reads a numeric entry of a box measured in the page.
   *
   * @param box the measured values
   * @param key the entry's key
   * @return the entry as a double
   */
  private static double number(@NotNull Map<String, Object> box, @NotNull String key) {
    return ((Number) box.get(key)).doubleValue();
  }

  /**
   * Opens the seeded mission in a touch-reporting phone-class context, appends scroll room below
   * the panes and hands the page to the caller.
   *
   * @param body the assertions to run against the opened mission
   */
  private void withMissionOnAPhone(@NotNull Consumer<Page> body) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                .setHasTouch(true)
                .setReducedMotion(ReducedMotion.REDUCE))) {
      Page page = context.newPage();
      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=ueb");
      page.waitForLoadState();
      page.evaluate(
          """
          px => {
            const room = document.createElement('div');
            room.style.height = px + 'px';
            document.querySelector('.tab-panes').append(room);
          }
          """,
          SCROLL_ROOM_PX);
      body.accept(page);
    }
  }
}
