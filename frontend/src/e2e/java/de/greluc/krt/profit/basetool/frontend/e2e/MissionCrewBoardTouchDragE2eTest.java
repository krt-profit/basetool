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
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that on a touch device a participant can be dragged from the "Ohne Einheit" pool into a
 * unit by press-and-hold (REQ-MISSION-005).
 *
 * <p>Synthetic {@code PointerEvent}s drive the board's own drag logic end to end; the platform's
 * native long-press and scroll behaviour is asserted only indirectly, as computed style and a
 * cancelled event.
 */
@Tag("e2e")
class MissionCrewBoardTouchDragE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The unit the held participant is dropped into. */
  private static final String UNIT = "E2E Touch Drag Unit";

  /** Phone viewport width of REQ-UI-009. */
  private static final int PHONE_WIDTH = 375;

  private static final int PHONE_HEIGHT = 812;

  /**
   * Comfortably past the board's 320 ms hold. Long enough that a slow CI runner still gets the
   * activation timer in, short enough not to pad the suite.
   */
  private static final int HOLD_WAIT_MS = 700;

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership, a
   * mission owned by that squadron, the user as its one registered participant (who therefore
   * starts in the "Ohne Einheit" pool) and one empty unit to drop them into.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String userId = seeder.getUserId(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Touch Drag Mission", true);
      seeder.addRegisteredParticipant(USERNAME, PASSWORD, missionId, userId);
      seeder.addUnitWithResponsible(USERNAME, PASSWORD, missionId, UNIT, userId);
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
   * Holds the pool row and drags it onto the unit's drop zone, then asserts the participant is
   * aboard that unit and gone from the pool — i.e. the pointer drag reached the same crew endpoint
   * the mouse drop does.
   */
  @Test
  void aHeldTouchDragMovesAParticipantIntoAUnit() {
    withCrewBoardOnAPhone(
        page -> {
          assertThat(page.locator("#board-pool .person-row")).hasCount(1);
          assertThat(page.locator(".board-units .drop-zone .person-row")).hasCount(0);

          page.evaluate(
              """
              () => {
                const row = document.querySelector('#board-pool .person-row');
                const r = row.getBoundingClientRect();
                row.dispatchEvent(new PointerEvent('pointerdown', {
                  bubbles: true, cancelable: true, pointerId: 1, pointerType: 'touch',
                  isPrimary: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
                }));
              }
              """);
          page.waitForTimeout(HOLD_WAIT_MS);

          page.evaluate(
              """
              () => {
                const row = document.querySelector('#board-pool .person-row');
                const hint = document.querySelector('.board-units .drop-zone .drop-hint');
                const r = hint.getBoundingClientRect();
                const x = r.left + r.width / 2;
                const y = r.top + r.height / 2;
                for (const type of ['pointermove', 'pointerup']) {
                  row.dispatchEvent(new PointerEvent(type, {
                    bubbles: true, cancelable: true, pointerId: 1, pointerType: 'touch',
                    isPrimary: true, clientX: x, clientY: y
                  }));
                }
              }
              """);

          assertThat(page.locator(".board-units .drop-zone .person-row")).hasCount(1);
          assertThat(page.locator("#board-pool .person-row")).hasCount(0);
        });
  }

  /**
   * Asserts the two halves of the long-press suppression on a person row: the CSS that keeps the
   * browser's selection UI and callout off it while leaving vertical panning and pinch-zoom with
   * the browser, and the board's own cancellation of the {@code contextmenu} the platform raises on
   * the press the drag starts on.
   */
  @Test
  void aTouchPressOnARowSuppressesTheBrowsersLongPressMenu() {
    withCrewBoardOnAPhone(
        page -> {
          @SuppressWarnings("unchecked")
          Map<String, Object> style =
              (Map<String, Object>)
                  page.evaluate(
                      """
                      () => {
                        const cs = getComputedStyle(document.querySelector('.person-row'));
                        return {
                          touchAction: cs.touchAction,
                          userSelect: cs.userSelect || cs.webkitUserSelect,
                          callout: cs.webkitTouchCallout || 'unsupported'
                        };
                      }
                      """);
          assertTrue(
              String.valueOf(style.get("touchAction")).contains("pan-y"),
              "row touch-action keeps vertical panning, was: " + style.get("touchAction"));
          assertEquals("none", style.get("userSelect"), "row user-select");

          Object prevented =
              page.evaluate(
                  """
                  () => {
                    const row = document.querySelector('.person-row');
                    const r = row.getBoundingClientRect();
                    row.dispatchEvent(new PointerEvent('pointerdown', {
                      bubbles: true, cancelable: true, pointerId: 2, pointerType: 'touch',
                      isPrimary: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
                    }));
                    const menu = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
                    row.dispatchEvent(menu);
                    row.dispatchEvent(new PointerEvent('pointercancel', {
                      bubbles: true, cancelable: true, pointerId: 2, pointerType: 'touch',
                      isPrimary: true
                    }));
                    return menu.defaultPrevented;
                  }
                  """);
          assertTrue(Boolean.TRUE.equals(prevented), "contextmenu cancelled during a touch press");
        });
  }

  /**
   * Opens the seeded mission's crew tab in a touch-reporting phone-class context and hands the page
   * to the caller.
   *
   * @param body the assertions to run against the opened crew board
   */
  private void withCrewBoardOnAPhone(Consumer<Page> body) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                .setHasTouch(true))) {
      Page page = context.newPage();
      E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
      page.waitForLoadState();
      body.accept(page);
    }
  }
}
