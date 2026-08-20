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
 * Layout regression guard (REQ-UI-013): the date and time parts of a {@code .datetime-split-group}
 * must render INSIDE their {@code .form-row} column on the mission detail page — in the participant
 * edit modal and in the Verwaltung form alike.
 *
 * <p>Both parts are fixed-width and non-shrinkable (10.5rem + {@code --space-2} + 7rem = an 18rem
 * floor), while a multi-column {@code .form-row} hands out a generic 250px per-column floor. Where
 * the column ends up narrower than 18rem the parts simply spill out of it: the time part overran
 * its column by ~13px in the 600px {@code .krt-modal--wide} participant modal (leaving "Endzeit"
 * 3px from the modal border and collapsing the 16px column gap to 3px) and by ~14.7px per group in
 * the three-column Verwaltung time row. The fix declares the real floor so the row WRAPS instead of
 * overflowing.
 *
 * <p>Two things about this test are deliberate and easy to get wrong when editing it:
 *
 * <ul>
 *   <li><b>Every check runs at several desktop widths, not one.</b> Whether a group ends up below
 *       its 18rem floor depends on how many of them the flex row keeps on a line, which depends on
 *       the container width — so a single viewport proves very little. The modal, a fixed 600px
 *       frame, overflows at every desktop width; the Verwaltung form only does so in the band where
 *       its row is wide enough to keep two groups on one line but too narrow to give each 18rem,
 *       and it renders perfectly fine on either side of that band. Sweeping the four widths of the
 *       device-class ladder (REQ-UI-009) keeps the guard honest without hard-coding a band that a
 *       future layout change would silently move out from under it.
 *   <li><b>The assertion compares bounding rectangles, NOT {@code scrollWidth} vs {@code
 *       clientWidth}.</b> The overflow lands inside the container's right padding, which the
 *       scrollable overflow region does not account for: {@code scrollWidth - clientWidth} measured
 *       0 on the broken layout and would have passed straight through the bug.
 * </ul>
 *
 * <p>The mission and the acting user's registration as a participant (which is what makes the crew
 * board render an {@code .edit-participant-btn} at all) are seeded via {@link BackendSeeder}; a
 * mission is staffel-scoped, so the user is assigned to the IRIDIUM Squadron first.
 */
@Tag("e2e")
class MissionDatetimeSplitLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Desktop widths each surface is measured at, spanning the Desktop and Ultra-wide device classes
   * of REQ-UI-009. Narrower classes are covered by the {@code <= 768px} stacking rules and are not
   * multi-column layouts at all, so they cannot exhibit this defect.
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
          // Double.toString + replace, NOT String.formatted("%f"): the default locale decides
          // the decimal separator, so a German JVM would splice "0,500000" into the script and
          // evaluate() would die of a syntax error instead of reporting a layout verdict.
          // Double.toString is locale-independent by contract.
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
   * Opens a participant's edit modal from the crew board and asserts both of its time pickers
   * ("Startzeit", "Endzeit") render inside their column. This is the surface the defect was
   * reported on: the modal frame is a fixed 600px, so its two-column row gave each group ~275px
   * against an 18rem floor.
   */
  @Test
  void participantEditModalKeepsTheTimePickersInsideTheirColumn() {
    onMissionPage(
        "crew",
        "mission-datetime-split-modal",
        page -> {
          // The crew board renders one edit button per participant; the seeded self-registration
          // guarantees exactly one. Opening it runs krtModalOpen(), which is what makes the two
          // datetime groups measurable.
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
          // Planned row (meeting / planned start / planned end) plus the actual start/end row —
          // the latter only renders on an existing mission, which this seeded one is.
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
    // Wildcard types, not Map<String, Object>: Playwright hands back a raw Object and a
    // parameterised cast would be unchecked, which the project bans papering over with
    // @SuppressWarnings. Nothing here needs the element types.
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
