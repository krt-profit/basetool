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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Layout guard (REQ-UI-013): the date and time parts of a {@code .datetime-split-group} must stay
 * inside their {@code .form-row} column on the mission detail page, in the participant edit modal
 * and the Verwaltung form.
 *
 * <p>Every check runs at several desktop widths, because the overflow depends on the container
 * width, and compares bounding rectangles, because the overflow lands in the container's padding
 * where {@code scrollWidth} does not see it.
 */
@Tag("e2e")
class MissionDatetimeSplitLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Desktop widths each surface is measured at, spanning the Desktop and Ultra-wide device classes
   * of REQ-UI-009.
   */
  private static final List<Integer> SWEPT_WIDTHS = List.of(1280, 1440, 1600, 1800);

  /** Viewport height, tall enough that the participant modal renders without its body scrolling. */
  private static final int VIEWPORT_HEIGHT = 1200;

  /**
   * Sub-pixel slack for the rectangle comparison. Fractional layout values differ in the last
   * decimal between engines; the defect this guards against overflows by 13-15px, so a half-pixel
   * tolerance separates the two without any risk of masking a real overrun.
   */
  private static final double OVERFLOW_TOLERANCE_PX = 0.5;

  /**
   * Collects every date/time part that renders outside its own {@code .datetime-split-group} box,
   * within the element matched by the {@code scope} selector. Returns the number of visible groups
   * it actually measured alongside the violations, so a caller can prove it probed a rendered pane
   * rather than a {@code display:none} one (whose zero-width rects would otherwise report a clean
   * result).
   */
  private static final String PROBE_JS =
      """
      (scope) => {
        const root = document.querySelector(scope);
        if (!root) return { measured: 0, violations: ['scope not found: ' + scope] };
        const violations = [];
        let measured = 0;
        root.querySelectorAll('.datetime-split-group').forEach((group) => {
          const gr = group.getBoundingClientRect();
          if (gr.width === 0) return;
          measured += 1;
          const label = (group.querySelector('label') || {}).textContent || group.id || '?';
          group.querySelectorAll('.datetime-split-inputs input').forEach((part) => {
            const pr = part.getBoundingClientRect();
            if (pr.width === 0) return;
            const over = Math.max(pr.right - gr.right, gr.left - pr.left);
            if (over > %s) {
              violations.push(
                label.trim() + ' / ' + part.type + ' overflows its column by '
                  + Math.round(over * 10) / 10 + 'px (column ' + Math.round(gr.width)
                  + 'px, part ' + Math.round(pr.width) + 'px)');
            }
          });
        });
        return { measured: measured, violations: violations };
      }
      """
          .replace("%s", Double.toString(OVERFLOW_TOLERANCE_PX));

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership, a
   * mission owned by that squadron and the user's own participant registration on it.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Datetime Layout Mission", true);
      seeder.addRegisteredParticipant(
          USERNAME, PASSWORD, missionId, seeder.getUserId(USERNAME, PASSWORD));
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
   * Opens a participant's edit modal from the crew board and asserts both time pickers
   * ("Startzeit", "Endzeit") render inside their column.
   */
  @Test
  void participantEditModalKeepsTheTimePickersInsideTheirColumn() {
    onMissionPage(
        "crew",
        "mission-datetime-split-modal",
        page -> {
          Locator editBtn = page.locator(".edit-participant-btn").first();
          assertThat(editBtn).isVisible();
          editBtn.click();
          assertThat(page.locator("#edit-participant-modal")).isVisible();

          assertNoOverflowAcrossWidths("#edit-participant-modal", page, 2);
        });
  }

  /**
   * Opens the Verwaltung tab and asserts every time picker of the mission form ("Treffen
   * Teamspeak", planned start/end, actual start/end) renders inside its column. Same root cause as
   * the modal, different container: three groups sharing one ~852px row.
   */
  @Test
  void missionFormKeepsTheTimePickersInsideTheirColumn() {
    onMissionPage(
        "verw",
        "mission-datetime-split-verwaltung",
        page -> {
          assertThat(page.locator("#pane-verw")).isVisible();
          assertNoOverflowAcrossWidths("#pane-verw", page, 5);
        });
  }

  /**
   * Re-measures {@code scope} at every width in {@link #SWEPT_WIDTHS}. Resizing the viewport
   * relayouts the page in place, so no reload (and no re-opening of the modal) is needed.
   *
   * @param scope CSS selector of the container to probe
   * @param page the page showing the rendered pane
   * @param expectedGroups how many visible {@code .datetime-split-group}s the scope must contain
   */
  private static void assertNoOverflowAcrossWidths(String scope, Page page, int expectedGroups) {
    for (int width : SWEPT_WIDTHS) {
      page.setViewportSize(width, VIEWPORT_HEIGHT);
      assertNoOverflow(scope, page, expectedGroups, width);
    }
  }

  /**
   * Runs the probe over {@code scope} and fails with the offending measurements spelled out.
   *
   * @param scope CSS selector of the container to probe
   * @param page the page showing the rendered pane
   * @param expectedGroups how many visible {@code .datetime-split-group}s the scope must contain —
   *     guards against a silently empty probe on a hidden pane reporting a false pass
   * @param width the viewport width currently applied, named in the failure message
   */
  private static void assertNoOverflow(String scope, Page page, int expectedGroups, int width) {
    Map<?, ?> probe = (Map<?, ?>) page.evaluate(PROBE_JS, scope);
    List<?> violations = (List<?>) probe.get("violations");
    assertTrue(
        violations.isEmpty(),
        () ->
            "date/time parts must stay inside their .form-row column (REQ-UI-013), but "
                + scope
                + " at a "
                + width
                + "px viewport reported: "
                + violations);
    assertEquals(
        expectedGroups,
        ((Number) probe.get("measured")).intValue(),
        "probe measured an unexpected number of visible .datetime-split-groups in "
            + scope
            + " at a "
            + width
            + "px viewport — the assertion above would pass vacuously on a hidden pane");
  }

  /**
   * Opens the seeded mission on the given tab in an authenticated, ultra-wide context and hands the
   * page to the caller, dumping diagnostics if the check fails.
   *
   * @param tab the {@code ?tab=} deeplink value ({@code crew} / {@code verw})
   * @param dumpLabel artifact label used if the flow fails
   * @param check the per-surface assertion
   */
  private void onMissionPage(String tab, String dumpLabel, Consumer<Page> check) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(SWEPT_WIDTHS.get(SWEPT_WIDTHS.size() - 1), VIEWPORT_HEIGHT))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=" + tab);
        page.waitForLoadState();
        check.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, dumpLabel);
        throw failure;
      }
    }
  }
}
