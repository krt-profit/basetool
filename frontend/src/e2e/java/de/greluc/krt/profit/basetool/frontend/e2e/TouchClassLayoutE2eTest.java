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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Layout guard for the two <b>touch</b> device classes of REQ-UI-009 — Smartphone (≤768px) and
 * Tablet (768–1024px) — across the pages a member actually works on.
 *
 * <p><b>Why this exists.</b> REQ-UI-009 has required both classes since it was written, and nothing
 * measured them: {@code MissionDatetimeSplitLayoutE2eTest}, the only other geometric layout guard,
 * sweeps 1280–1800px, and the smoke suite loads pages at the default desktop viewport and asserts
 * that the sidebar rendered. So "works on a phone" was review-enforced on a UI nobody opens on a
 * phone. It became load-bearing with REQ-UI-020: the web app is now installable to a home screen
 * and <b>is</b> the mobile client for iPhone and iPad, which have no native app.
 *
 * <p><b>What it asserts</b>, one check per thing that actually goes wrong on a narrow screen:
 *
 * <ol>
 *   <li><b>The page does not scroll sideways.</b> REQ-UI-009 says <i>wide tables</i> scroll
 *       horizontally — not the document. A page-level horizontal scrollbar means something is wider
 *       than the viewport and the member has to pan the whole layout to read it.
 *   <li><b>The chrome does not eat the screen.</b> Only chrome that actually holds viewport space
 *       counts: the sticky header always, the footer only where it is pinned. Together they must
 *       stay under {@value #MAX_CHROME_SHARE_PERCENT}% of the viewport height.
 *   <li><b>The footer behaves per device class.</b> On the phone class it must be {@code static} —
 *       it scrolls with the page so the viewport belongs to the content (owner decision 2026-09-13)
 *       — and {@code --krt-footer-height} must then be {@code 0px}, because its eight consumers all
 *       mean "how much of the viewport bottom is covered" and a scrolling footer covers nothing.
 *       Above that class it must still be {@code fixed}, and then {@code main}'s bottom padding
 *       must cover its measured height, or the last rows of a long page can never be scrolled clear
 *       of it.
 *   <li><b>Nothing is cut off.</b> Any element whose right edge lies beyond the viewport and that
 *       sits in no horizontally scrollable ancestor is unreachable, not merely ugly.
 *   <li><b>Wide tables scroll inside their own container.</b> The positive form of check 1: a table
 *       wider than the viewport is fine, provided some ancestor scrolls it.
 *   <li><b>Inputs fit.</b> A form control wider than the viewport cannot be filled in, and one
 *       whose EFFECTIVE HIT AREA is shorter than the 44px touch floor cannot be hit reliably
 *       (REQ-UI-009's floor; its documented exception for dense row actions is honoured).
 *       Effective, not the border box: a control may reach the floor through a positioned {@code
 *       ::after} overlay or through the {@code <label>} that activates it, and both are real
 *       targets to a finger. Measuring the border box alone reported 15 compliant org-chart
 *       chevrons as defects.
 * </ol>
 *
 * <p><b>What this class measures depends on data other classes create, and nothing enforces the
 * order.</b> Carrying the {@code e2e} tag is what puts it in the destructive suite where the CRUD
 * flows have populated the tables — and that is where it found 118 defects a fresh stack hid. But
 * the ordering that currently runs it after those classes is JUnit's unspecified discovery order:
 * it used to rest on JUnit's unspecified discovery order. {@code junit-platform.properties} now
 * selects {@code ClassOrderer.OrderAnnotation} and this class carries
 * {@code @Order(Integer.MAX_VALUE)}, so it runs after every class that has no {@code @Order} —
 * which is all 93 of them. Belt and braces: the coverage report below still names any list page
 * that rendered no row, so if the ordering is ever defeated the degradation shows in the log
 * instead of passing quietly.
 *
 * <p><b>Artifacts come from one engine, assertions from all three.</b> Every engine measures every
 * page at every device class and fails on its own findings; only the Chromium shard writes the
 * screenshots. They are review evidence rather than assertions, and paying for ~430 captures three
 * times over cost the Firefox shard its 45-minute job timeout — it was cancelled on every run of a
 * branch whose other two engines reported green.
 *
 * <p><b>Bounding rectangles, not {@code scrollWidth} alone.</b> The same lesson {@code
 * MissionDatetimeSplitLayoutE2eTest} records: overflow that lands inside a container's padding does
 * not show up in {@code scrollWidth - clientWidth}. Checks 4 and 6 therefore compare rectangles
 * against the viewport, and check 1 takes the larger of the document element's and the body's
 * scroll width, since either can carry it.
 *
 * <p><b>Two mistakes this file made first, kept as comments where they were made.</b> Check 4
 * excused any element with a horizontally scrollable ancestor, without asking whether that ancestor
 * itself fits — so five pages that overflowed the phone by 10–159px reported clean, and were caught
 * only because Playwright's full-page screenshots came out wider than the viewport. And an earlier
 * check flagged every element that happened to lie under the fixed footer at scroll 0, which
 * produced 60 findings and no defects: that is what a fixed footer does, and the reserve above is
 * the invariant that makes it harmless.
 *
 * <p><b>Every finding is collected before anything fails.</b> A layout audit that stops at the
 * first offending page is worth much less than one that names all of them, so each page/width
 * combination appends to one list and the assertion happens at the end. Screenshots of every
 * combination land in {@code build/e2e-artifacts/touch-layout/} whether the run passes or fails —
 * the point is to look at them, not only to read a diff.
 *
 * <p><b>Tagged {@code e2e} only, and deliberately not {@code smoke}</b> (owner decision 2026-09-13;
 * the comment on the class says why). The {@code e2e} tag puts it on every pull request carrying
 * the {@code e2e} label, which is exactly the set that touches frontend flows, auth or controllers,
 * at a cost of roughly five minutes on those runs. It opens every modal on every route and needs
 * the rows the destructive CRUD flows create, which is more than the non-destructive smoke contract
 * allows.
 */
@Order(Integer.MAX_VALUE)
@Tag("e2e")
class TouchClassLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Where the screenshots go; one PNG per page and device class. */
  private static final Path ARTIFACTS = Path.of("build", "e2e", "touch-layout");

  /**
   * Share of the viewport height the sticky header and the fixed footer may occupy together.
   *
   * <p>A third is already generous: it leaves two thirds for the page on a 812px phone. The number
   * is a ceiling on an obvious failure, not a design target — it exists so "the chrome grew" is
   * caught, not to tune the chrome.
   */
  private static final int MAX_CHROME_SHARE_PERCENT = 33;

  /**
   * Ceiling for the header's own height on the phone class, in px.
   *
   * <p>Separate from {@link #MAX_CHROME_SHARE_PERCENT} because that ceiling cannot see this: a
   * header of 110px on an 812px phone is 13% of the viewport and sails through, which is exactly
   * what happened. The compact header (REQ-UI-009, owner decision 2026-09-13) took it from 76px to
   * about 48px by putting the wordmark on one line; a later structural wrap rule then matched the
   * header's own nav — it has a button and a link as direct children — and wrapped the brand onto a
   * second line again, undoing it silently in the same release.
   *
   * <p>72px is measured-plus-slack: the fixed header renders at 62px at both 375px and 412px, and
   * the ceiling sits below the 76px the compact-header change removed, so this guard would have
   * caught the state before that change AND the regression after it.
   *
   * <p>It has since caught a second, unrelated way into the same 108px: {@code sidebar.js} appends
   * an "ADMIN" chip into the header nav on every {@code /admin/*} page, and the two-column grid
   * that fixed the wrap above placed that third child in row 2. Worth knowing because the height is
   * the only symptom the two share — the ceiling is deliberately a bound on the outcome, not a test
   * for either cause.
   */
  private static final int MAX_PHONE_HEADER_HEIGHT_PX = 72;

  /** Minimum touch target per REQ-UI-009, in CSS pixels. */
  private static final int TOUCH_TARGET_FLOOR = 44;

  /**
   * Upper bound of the touch classes, and therefore of the 44px floor.
   *
   * <p>Mirrors the app's own `width <= 1024px` touch block. Above it a control may be as dense as
   * the design wants — a mouse hits a 24px row action, a thumb does not.
   */
  private static final int TOUCH_CLASS_MAX_WIDTH = 1024;

  /**
   * Upper bound of REQ-UI-009's Smartphone class, and the breakpoint the footer behaviour turns on.
   *
   * <p>Mirrors the {@code width <= 768px} media query in {@code styles.css}; the two must move
   * together, which is why the value is named here rather than spelled into a comparison.
   */
  private static final int PHONE_MAX_WIDTH = 768;

  /**
   * Dense row actions REQ-UI-009 exempts from the 44px floor at 32px.
   *
   * <p>Owner-approved 2026-08-01; density is what keeps a wide Lager or bank table readable.
   * Mirrors {@code --touch-target-dense} in {@code styles.css}; the two must move together.
   */
  private static final int DENSE_ACTION_FLOOR = 32;

  /** Sub-pixel slack, for the same reason the sibling layout guard carries one. */
  private static final double SLACK_PX = 1.0;

  /**
   * The engine that WRITES the screenshots. Every engine still measures and still asserts.
   *
   * <p>The sweep takes a full-page capture per page per device class plus one per modal on the two
   * phone classes — around 430 images — and in Firefox that is the dominant cost. Growing the route
   * list from 47 to 64 pushed the Firefox shard from ~18 minutes past the workflow's {@code
   * timeout-minutes: 45}, where it was cancelled on every run of the branch while Chromium finished
   * in 20 and WebKit in 23. Chromium and WebKit reported green and the third of the matrix that
   * never finished went unnoticed for six runs.
   *
   * <p>The images are review evidence, and one engine's set is what a reviewer opens; three
   * near-identical copies buy nothing. This is the same distinction {@link #screenshotSafely}
   * already draws — the measurement is the assertion, the picture is what lets a reader check it —
   * applied to which shard pays for the picture. A local run defaults to this engine, so nobody
   * loses artifacts by accident.
   */
  private static final String SCREENSHOT_ENGINE = "chromium";

  /**
   * Prefix marking a finding as a HIT-AREA one rather than a geometry one.
   *
   * <p>The two are filtered differently: above the touch classes only geometry matters, because a
   * 44px floor is a touch rule and a mouse has no thumb. That filter used to substring-match {@code
   * "px tall, floor"} out of the probe's own prose, so reformatting a message — adding a unit,
   * rewording the floor — would silently turn the desktop filter into a pass-through. The comment
   * on it records that getting this wrong once cost 490 false findings.
   *
   * <p>Interpolated into the probe script, so there is one spelling and the compiler moves it.
   */
  private static final String HIT_AREA_MARK = "hit-area: ";

  /**
   * The modal ROOT selector the templates use, and the inner parts to measure in it.
   *
   * <p><b>There is one shape, and this list is how that is visible from the test side.</b> It held
   * three until #1891: the canonical {@code .krt-modal-overlay} shell (42 instances) and two legacy
   * shapes that predated it — {@code .modal} / {@code .modal-content} (47) and its promotion-admin
   * sister {@code .modal-overlay} / {@code .modal-box} (7). This sweep saw none of the 54 legacy
   * roots until 2026-09-13, while the comment on the measurement loop called 42 "the full set".
   *
   * <p>All 54 were ported onto the canonical shell and both legacy shapes deleted (ADR-0177), so
   * the entry that used to be "canonical first" is now the only one. The list stays a list, and the
   * four call sites still derive from it, because that is what makes a second shape cheap to
   * measure on the day one is deliberately introduced — the point was never that three was too many
   * to write down, it was that each one multiplied every dialog fix.
   *
   * <p>Declared ONCE because the selector has to agree at four sites — {@code modalCount}, the
   * probe's own measurement loop, and the Java-side un-hide and restore around the screenshot.
   * Widening one alone does nothing: if the probe measured a wider set than the un-hide reveals,
   * the extra modals are still {@code display: none}, are skipped by the zero-rect guards, and the
   * change looks harmless while buying nothing.
   *
   * <p><b>A FOURTH shape is now detected, but not here.</b> This list is still hand-maintained and
   * still cannot report a family nobody entered — the failure mode {@link #PAGES} had. What closed
   * it is {@code SingleModalShapeTest} in the frontend {@code test} source set: it reads the
   * templates and the stylesheets as text and fails the build when any dialog class outside this
   * shape appears, on every push rather than only on a PR carrying the {@code e2e} label. That is
   * the {@code check}-time guard this comment used to ask for; it did not need {@code test-support}
   * after all, because reading templates as text needs no browser.
   *
   * @param root the overlay or scrim element — the thing that is hidden and shown
   * @param box the framed dialog inside it, whose geometry is the assertion
   * @param body the scrolling region, or {@code null} where the family has no such class
   * @param foot the button row, or {@code null} where the family has no such class
   * @param openClass a class the family's own JS adds to open it, or {@code null} when display
   *     alone opens it. No current shape needs one — it existed for {@code .modal-overlay}, which
   *     centred its box in {@code .active} only, so revealing it with display alone would have
   *     stretched the dialog and measured a shape no user is ever shown. The parameter stays for
   *     the next shape that opens by a class rather than by display.
   */
  private record ModalShape(String root, String box, String body, String foot, String openClass) {}

  /** The one shape. New work is written against it; there is no other to choose from. */
  private static final List<ModalShape> MODAL_SHAPES =
      List.of(
          new ModalShape(
              ".krt-modal-overlay", ".krt-modal", ".krt-modal-body", ".krt-modal-foot", null));

  /**
   * {@link #MODAL_SHAPES} as one selector, for the three sites that only need to find the roots.
   *
   * <p>Derived rather than written out, so a family added above reaches all four sites at once.
   */
  private static final String MODAL_ROOT_SELECTOR =
      MODAL_SHAPES.stream().map(ModalShape::root).collect(Collectors.joining(", "));

  /** {@link #MODAL_SHAPES} as a JS array literal, for the two scripts that need the inner parts. */
  private static final String MODAL_SHAPES_JS =
      MODAL_SHAPES.stream()
          .map(
              shape ->
                  "{root:%s,box:%s,body:%s,foot:%s,openClass:%s}"
                      .formatted(
                          asJsString(shape.root()),
                          asJsString(shape.box()),
                          asJsString(shape.body()),
                          asJsString(shape.foot()),
                          asJsString(shape.openClass())))
          .collect(Collectors.joining(",", "[", "]"));

  /**
   * A selector as a JS string literal, or the bare token {@code null} for a part a family lacks.
   *
   * @param selector a CSS selector, or {@code null}
   * @return {@code 'selector'}, or {@code null}
   */
  private static String asJsString(String selector) {
    return selector == null ? "null" : "'" + selector + "'";
  }

  /**
   * The engine this run drives, as {@code e2e.browser}.
   *
   * <p>Read once instead of at each of the three call sites that used to inline the property and
   * its default, which is how they can drift.
   */
  private static final String ENGINE = System.getProperty("e2e.browser", "chromium");

  /**
   * Whether this engine accepts {@code isMobile}.
   *
   * <p><b>Firefox does not.</b> Playwright's own documentation says so for both {@code newContext}
   * and {@code newPage} — "Defaults to false and is not supported in Firefox" — and its Firefox
   * backend throws rather than ignoring it. Gating on the width alone made the phone class the one
   * context Firefox refuses to open, and the {@code browser x device} matrix then gave that its own
   * job: {@code firefox / 375x812} could not get past its first {@code newContext}, while the four
   * wider Firefox jobs passed because {@code width <= 768} was false for them and the option was
   * never set. With {@code fail-fast: false} that is a permanently red E2E gate that looks like a
   * flake.
   *
   * <p>What is lost on Firefox is the mobile-device emulation (the meta viewport being honoured);
   * {@code setHasTouch(true)} and the viewport size still apply, so the touch-class media queries
   * and every geometry assertion in this class still hold. Chromium and WebKit keep the full
   * emulation, so the property is still measured on two engines out of three.
   */
  private static final boolean ENGINE_SUPPORTS_IS_MOBILE = !"firefox".equals(ENGINE);

  /**
   * How many of {@link FrontendPageRoutes#PAGES} a device class must actually measure before the
   * run counts.
   *
   * <p>A floor, not a target: it exists so a sweep that skipped everything cannot report clean. The
   * relative check in {@link #reportCoverage} is the real guard; this only catches the case where
   * every class is equally empty, which the relative check cannot see.
   */
  private static final int MIN_MEASURED_ROUTES = 30;

  /**
   * How much of a failure-to-measure message is kept in the finding.
   *
   * <p>Enough for a Playwright page-side error to name its cause and its call site, short enough
   * that one unmeasurable page cannot bury the other findings in stack trace.
   */
  private static final int MAX_REASON_CHARS = 600;

  /**
   * Restricts the sweep to one device class, as {@code WxH}.
   *
   * <p>Set by CI, which fans the five classes out across runners ({@code browser x device}) rather
   * than walking them one after another in a single job — the shape that put the Firefox shard on
   * its job timeout. Unset locally and in the smoke suite, where all five run as before.
   *
   * <p>It changes what {@link #reportCoverage} can assert, and that is worth stating rather than
   * discovering: the relative check compares each class against the union across classes, so with
   * exactly one class the union IS that class and the comparison is vacuous. The absolute floor is
   * what still catches a sweep that skipped everything, and the five runners together still cover
   * the same routes — the cross-class comparison simply moves from inside one JVM to a reader
   * comparing five job logs.
   */
  private static final String DEVICE_FILTER = System.getProperty("e2e.device", "").trim();

  /**
   * The five device classes of REQ-UI-009, with the tablet taken at both orientations.
   *
   * <p>375×812 is the iPhone viewport the phone class is written for. 810×1080 and 1024×768 are the
   * tablet class at both orientations, and they really are different layouts here because every
   * breakpoint is width-only ({@code width <= 768px} / {@code <= 1024px}), so a rotation crosses
   * them. 1280×800 is the desktop class and 1600×900 the ultra-wide one, where {@code main} gains
   * its {@code max-width} — the two classes that were already covered by review and by {@code
   * MissionDatetimeSplitLayoutE2eTest} but never by a whole-page sweep.
   *
   * <p>Ordered narrow to wide so the output reads as a ladder.
   *
   * <p>This block sat ABOVE {@link #DEVICE_FILTER} as a second consecutive Javadoc comment, which
   * javac discards — so it documented nothing, and what it said had stopped being true: "all four
   * device classes" for a five-entry list, and 768×1024 for the entry deliberately changed to
   * 810×1080. `REQ-UI-009` and the ADR named the same retired number and are corrected with it.
   */
  private static final List<int[]> DEVICE_CLASSES =
      List.of(
          new int[] {375, 812},
          new int[] {810, 1080},
          new int[] {1024, 768},
          new int[] {1280, 800},
          new int[] {1600, 900});

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /**
   * Launches the browser and captures one authenticated session reused across every measurement.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Sweeps every page at every touch device class, collects every finding, then fails once.
   *
   * <p>One browser context per device class rather than a {@code setViewportSize} per page: the
   * media queries are evaluated at load time by some of the page scripts (the footer measurement
   * among them), and resizing an already-rendered page exercises a state a phone never reaches.
   */
  @Test
  void touchClassesRenderWithoutOverflowOrOverlap() {
    List<String> findings = new ArrayList<>();
    String baseUrl = STACK.baseUrl();

    Map<String, Set<String>> measuredByDevice = new LinkedHashMap<>();
    Map<String, Set<String>> detailsByDevice = new LinkedHashMap<>();
    List<String> uncoveredLists = new ArrayList<>();

    for (int[] device : DEVICE_CLASSES) {
      int width = device[0];
      int height = device[1];
      String deviceLabel = width + "x" + height;
      if (!DEVICE_FILTER.isEmpty() && !DEVICE_FILTER.equals(deviceLabel)) {
        continue;
      }

      try (BrowserContext context =
          browser.newContext(
              new Browser.NewContextOptions()
                  .setIgnoreHTTPSErrors(true)
                  .setStorageStatePath(storageState)
                  .setViewportSize(width, height)
                  .setHasTouch(true)
                  .setIsMobile(width <= PHONE_MAX_WIDTH && ENGINE_SUPPORTS_IS_MOBILE))) {
        Page page = context.newPage();
        Set<String> measured = new LinkedHashSet<>();
        Set<String> measuredDetails = new LinkedHashSet<>();
        measuredByDevice.put(deviceLabel, measured);
        detailsByDevice.put(deviceLabel, measuredDetails);
        for (String path : FrontendPageRoutes.PAGES) {
          findings.addAll(measureSafely(page, baseUrl, path, deviceLabel, width, height, measured));

          String detail = firstDetailLink(page, FrontendPageRoutes.detailPrefixOf(path));
          if (detail != null) {
            findings.addAll(
                measureSafely(page, baseUrl, detail, deviceLabel, width, height, measuredDetails));
          } else if (FrontendPageRoutes.DETAIL_LIST_PAGES.contains(path)) {
            uncoveredLists.add(
                deviceLabel
                    + " "
                    + path
                    + ": no row, so its detail view was not"
                    + " measured at this class");
          }
        }
      }
    }

    reportCoverage(measuredByDevice, detailsByDevice, uncoveredLists, findings);

    assertTrue(
        findings.isEmpty(),
        "Touch-class layout findings (REQ-UI-009). Screenshots: "
            + ARTIFACTS.toAbsolutePath()
            + System.lineSeparator()
            + String.join(System.lineSeparator(), findings));
  }

  /**
   * Turns what the sweep actually looked at into an assertion.
   *
   * <p>Two ways to pass without measuring anything were possible before this, and the suite's only
   * assertion — {@code findings.isEmpty()} — cannot tell "clean" from "never looked". The check is
   * deliberately RELATIVE rather than a magic number: every device class must have measured the
   * same set of routes. A route with no app shell (a fragment, a JSON endpoint, a redirect) skips
   * at all five classes and cancels out, while a session that expires during the third class
   * shrinks that class's set and is named here. One absolute floor guards the degenerate case where
   * nothing is measured anywhere.
   *
   * @param measuredByDevice per device class, the {@link FrontendPageRoutes#PAGES} routes that
   *     reached the measurement
   * @param detailsByDevice per device class, the detail views reached by following a list link —
   *     reported but never compared, since which one a list renders varies per run
   * @param uncoveredLists list pages that rendered no row, so their detail view went unmeasured
   * @param findings the sweep's findings, appended to when coverage is short
   */
  private static void reportCoverage(
      Map<String, Set<String>> measuredByDevice,
      Map<String, Set<String>> detailsByDevice,
      List<String> uncoveredLists,
      List<String> findings) {
    measuredByDevice.forEach(
        (device, measured) ->
            System.out.printf(
                "[touch-layout] coverage %-10s %d of %d routes measured, plus %d detail view(s)%n",
                device,
                measured.size(),
                FrontendPageRoutes.PAGES.size(),
                detailsByDevice.getOrDefault(device, Set.of()).size()));
    uncoveredLists.forEach(line -> System.out.println("[touch-layout] uncovered " + line));

    if (measuredByDevice.isEmpty()) {
      findings.add(
          DEVICE_FILTER.isEmpty()
              ? "no device class ran at all"
              : "no device class ran at all — e2e.device="
                  + DEVICE_FILTER
                  + " matched none of "
                  + DEVICE_CLASSES.size()
                  + " classes, so this runner measured nothing");
      return;
    }
    Set<String> union = new LinkedHashSet<>();
    measuredByDevice.values().forEach(union::addAll);
    int widest = measuredByDevice.values().stream().mapToInt(Set::size).max().orElse(0);
    if (widest < MIN_MEASURED_ROUTES) {
      findings.add(
          String.format(
              "coverage floor: no device class measured more than %d of %d routes (minimum %d) —"
                  + " the sweep did not run, it skipped",
              widest, FrontendPageRoutes.PAGES.size(), MIN_MEASURED_ROUTES));
    }
    measuredByDevice.forEach(
        (device, measured) -> {
          Set<String> missing = new LinkedHashSet<>(union);
          missing.removeAll(measured);
          if (!missing.isEmpty()) {
            findings.add(
                String.format(
                    "coverage gap: %s measured %d routes where the union across classes is %d —"
                        + " missing %s. A class going quiet mid-sweep is what an expired session"
                        + " looks like.",
                    device, measured.size(), union.size(), missing));
          }
        });
  }

  /**
   * Runs {@link #measure} and turns any failure to measure into a finding instead of an abort.
   *
   * @param page the page to navigate
   * @param baseUrl origin of the stack under test
   * @param path app-relative path to measure
   * @param deviceLabel {@code WxH}
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @param measured collects the paths this device class actually measured, for the coverage
   *     assertion; a path that skips or throws is deliberately absent from it
   * @return the page's findings, or a single line naming why it could not be measured
   */
  private static List<String> measureSafely(
      Page page,
      String baseUrl,
      String path,
      String deviceLabel,
      int width,
      int height,
      Set<String> measured) {
    try {
      return measure(page, baseUrl, path, deviceLabel, width, height, measured);
    } catch (RuntimeException e) {
      String reason =
          e.getClass().getSimpleName()
              + ": "
              + String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
      if (reason.length() > MAX_REASON_CHARS) {
        reason = reason.substring(0, MAX_REASON_CHARS) + "…";
      }
      System.out.printf(
          "[touch-layout] %-34s COULD NOT MEASURE — %s%n", deviceLabel + " " + path, reason);
      return List.of(deviceLabel + " " + path + ": could not be measured — " + reason);
    }
  }

  /**
   * Takes a screenshot, and reports rather than throws when the browser will not produce one.
   *
   * <p>The audit's assertion is the measurement; the picture is what lets a reader check the
   * measurement against something. Losing the picture costs review convenience, losing the
   * measurement costs the audit — so the two may not share a failure.
   *
   * @param page the page to photograph
   * @param options where and how to write the PNG
   * @param where {@code WxH /path}, for the printed line
   */
  private static void screenshotSafely(Page page, Page.ScreenshotOptions options, String where) {
    if (!SCREENSHOT_ENGINE.equals(ENGINE)) {
      return;
    }
    try {
      page.screenshot(options);
    } catch (RuntimeException e) {
      String reason = String.valueOf(e.getMessage()).replaceAll("\\s+", " ").trim();
      if (reason.length() > MAX_REASON_CHARS) {
        reason = reason.substring(0, MAX_REASON_CHARS) + "…";
      }
      System.out.printf(
          "[touch-layout] %-34s NO SCREENSHOT (measurement kept) — %s: %s%n",
          where, e.getClass().getSimpleName(), reason);
    }
  }

  /**
   * Finds the first detail link a list page renders under the given prefix — the list's own path,
   * or the one {@link FrontendPageRoutes#detailPrefixOf} names for a list whose rows link out of
   * it.
   *
   * <p>Matches {@code <listPath>/<id>} where the id looks like a UUID or a number — the two shapes
   * the frontend's detail routes use — and deliberately ignores the {@code /create}, {@code /new}
   * and {@code /search} siblings, which are pages in their own right and already in the sweep.
   *
   * @param page the page currently showing the list
   * @param listPath the href prefix the detail links carry: the list's own app-relative path, or
   *     its override from {@link FrontendPageRoutes#DETAIL_PREFIX_OVERRIDES}
   * @return the app-relative path of the first detail view, or {@code null} when the list is empty
   */
  private static String firstDetailLink(Page page, String listPath) {
    if ("/".equals(listPath)) {
      return null;
    }
    Object href;
    try {
      href =
          page.evaluate(
              """
              (prefix) => {
                for (const a of document.querySelectorAll('a[href]')) {
                  const path = new URL(a.getAttribute('href'), location.origin).pathname;
                  if (!path.startsWith(prefix + '/')) continue;
                  const rest = path.slice(prefix.length + 1);
                  if (rest.length >= 6 && /^[0-9a-fA-F-]+$/.test(rest)) return path;
                }
                return null;
              }
              """,
              listPath);
    } catch (RuntimeException e) {
      System.out.printf(
          "[touch-layout] %-22s detail link unreadable (%s) — its detail view is not measured%n",
          listPath, e.getClass().getSimpleName());
      return null;
    }
    return href instanceof String str ? str : null;
  }

  /**
   * Loads one page at one device class, screenshots it, and returns its findings.
   *
   * @param page the page to navigate; its context already carries the viewport and the session
   * @param baseUrl origin of the stack under test
   * @param path app-relative path to measure
   * @param deviceLabel {@code WxH}, used in artifact names and in every finding line
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @param measured receives {@code path} once the page is established as a real page, i.e. after
   *     the app-shell check; the coverage assertion reads it
   * @return one line per finding; empty when the page is clean at this size
   */
  private static List<String> measure(
      Page page,
      String baseUrl,
      String path,
      String deviceLabel,
      int width,
      int height,
      Set<String> measured) {
    List<String> findings = new ArrayList<>();
    if (SCREENSHOT_ENGINE.equals(ENGINE)) {
      page.setViewportSize(width, height + 1);
      page.setViewportSize(width, height);
    }
    E2eSupport.navigate(page, baseUrl + path);
    page.waitForFunction(
        "() => document.readyState === 'complete'"
            + " && (!document.fonts || document.fonts.status === 'loaded')"
            + " && (!document.querySelector('.krt-footer')"
            + "     || getComputedStyle(document.documentElement)"
            + "         .getPropertyValue('--krt-footer-height').trim() !== '')");

    String origin = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String landedPath = page.url().replace(origin, "").replaceAll("[?#].*$", "");
    if (landedPath.isEmpty()) {
      landedPath = "/";
    }
    if (!landedPath.equals(path) && !landedPath.equals(path + "/")) {
      System.out.printf(
          "[touch-layout] %-34s SKIPPED — redirected to %s (not measured, not counted)%n",
          deviceLabel + " " + path, landedPath);
      return findings;
    }

    boolean hasAppShell =
        Boolean.TRUE.equals(
            page.evaluate(
                "() => !!document.querySelector('header nav .brand') &&"
                    + " (!!document.querySelector('main')"
                    + "  || !!document.querySelector('.page-wrapper'))"));
    if (!hasAppShell) {
      System.out.printf(
          "[touch-layout] %-34s SKIPPED — no app shell (fragment, redirect or non-HTML); now at"
              + " %s%n",
          deviceLabel + " " + path, page.url().replace(baseUrl, ""));
      return findings;
    }
    measured.add(path);

    @SuppressWarnings("unchecked")
    Map<String, Object> probe =
        (Map<String, Object>)
            page.evaluate(PROBE_JS, Map.of("slack", SLACK_PX, "deviceWidth", width));

    String slug = path.equals("/") ? "dashboard" : path.replaceAll("^/", "").replace('/', '-');
    screenshotSafely(
        page,
        new Page.ScreenshotOptions()
            .setPath(ARTIFACTS.resolve(deviceLabel + "__" + slug + ".png"))
            .setFullPage(true),
        deviceLabel + " " + path);

    if (width <= PHONE_MAX_WIDTH && SCREENSHOT_ENGINE.equals(ENGINE)) {
      int count = (int) number(probe.get("modalCount"));
      for (int i = 0; i < count; i++) {
        String id =
            String.valueOf(
                page.evaluate(
                    """
                    (i) => {
                      const o = document.querySelectorAll('%s')[i];
                      if (!o) return '';
                      const shape = %s.find((s) => o.matches(s.root));
                      o.dataset.krtPrevDisplay = o.style.display;
                      if (shape && shape.openClass && !o.classList.contains(shape.openClass)) {
                        o.classList.add(shape.openClass);
                        o.dataset.krtAddedOpenClass = shape.openClass;
                      }
                      o.style.display = 'flex';
                      return o.id || ('modal-' + i);
                    }
                    """
                        .formatted(MODAL_ROOT_SELECTOR, MODAL_SHAPES_JS),
                    i));
        if (!id.isEmpty()) {
          screenshotSafely(
              page,
              new Page.ScreenshotOptions()
                  .setPath(
                      ARTIFACTS
                          .resolve("modals")
                          .resolve(deviceLabel + "__" + slug + "__" + id + ".png")),
              deviceLabel + " " + path + " > " + id);
        }
        page.evaluate(
            """
            (i) => {
              const o = document.querySelectorAll('%s')[i];
              if (!o) return;
              o.style.display = o.dataset.krtPrevDisplay || '';
              delete o.dataset.krtPrevDisplay;
              if (o.dataset.krtAddedOpenClass) {
                o.classList.remove(o.dataset.krtAddedOpenClass);
                delete o.dataset.krtAddedOpenClass;
              }
            }
            """
                .formatted(MODAL_ROOT_SELECTOR),
            i);
      }
    }

    String where = deviceLabel + " " + path;

    printDiagnostics(where, probe);
    findings.addAll(checkGeometry(where, probe, width, height));
    findings.addAll(collectOffenders(where, probe, width));
    return findings;
  }

  /**
   * Prints what was measured, pass or fail.
   *
   * <p>The first version of this guard reported clean on five pages whose screenshots were 10-159px
   * wider than the viewport, and the only reason that was caught is that somebody measured the
   * PNGs. A guard whose measurements are invisible until it fails cannot be sanity-checked against
   * the artifacts it writes beside them.
   *
   * @param where {@code WxH /path}
   * @param probe the page-side measurement
   */
  private static void printDiagnostics(String where, Map<String, Object> probe) {
    System.out.printf(
        "[touch-layout] %-22s viewport=%.0f pageScrollWidth=%.0f (html=%s body=%s"
            + " overflow-x html/body=%s/%s) footer=%s header=%.0f footerH=%.0f reserve=%.0f"
            + " var=%s | widest box %s at %s%n",
        where,
        number(probe.get("innerWidth")),
        number(probe.get("docScrollWidth")),
        String.valueOf(probe.get("scrollWidthHtml")),
        String.valueOf(probe.get("scrollWidthBody")),
        String.valueOf(probe.get("overflowXHtml")),
        String.valueOf(probe.get("overflowXBody")),
        String.valueOf(probe.get("footerPosition")),
        number(probe.get("headerHeight")),
        number(probe.get("footerHeight")),
        number(probe.get("mainPaddingBottom")),
        String.valueOf(probe.get("footerHeightVar")),
        String.valueOf(probe.get("widestElement")),
        String.valueOf(probe.get("widestRight")));
    int modalCount = (int) number(probe.get("modalCount"));
    if (modalCount > 0) {
      System.out.printf("[touch-layout] %-34s %d modal(s) measured%n", where, modalCount);
    }
  }

  /**
   * The whole-page geometry rules: does the page fit, does the footer behave, is the chrome budget
   * respected.
   *
   * @param where {@code WxH /path}
   * @param probe the page-side measurement
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @return one line per finding
   */
  private static List<String> checkGeometry(
      String where, Map<String, Object> probe, int width, int height) {
    List<String> findings = new ArrayList<>();
    double docScrollWidth = number(probe.get("docScrollWidth"));
    double innerWidth = number(probe.get("innerWidth"));

    if (innerWidth > width + SLACK_PX) {
      findings.add(
          String.format(
              "%s: the page does not fit the device — the layout viewport widened to %.0fpx on a"
                  + " %dpx screen, so the whole page is scaled down or panned.",
              where, innerWidth, width));
    } else if (innerWidth < width - SLACK_PX) {
      findings.add(
          String.format(
              "%s: MEASUREMENT FAULT — the viewport read %.0fpx while %dpx was set.",
              where, innerWidth, width));
    }
    if (docScrollWidth > innerWidth + SLACK_PX) {
      findings.add(
          where
              + ": the PAGE scrolls sideways — document scrollWidth "
              + (long) docScrollWidth
              + "px vs viewport "
              + (long) innerWidth
              + "px. REQ-UI-009 lets wide tables scroll, not the document.");
    }

    String footerPosition = String.valueOf(probe.get("footerPosition"));
    double headerH = number(probe.get("headerHeight"));
    double footerH = number(probe.get("footerHeight"));
    double reserved = number(probe.get("mainPaddingBottom"));
    String heightVar = String.valueOf(probe.get("footerHeightVar"));
    boolean phone = width <= PHONE_MAX_WIDTH;

    if (phone) {
      if ("fixed".equals(footerPosition)) {
        findings.add(
            where
                + ": the footer is still position:fixed on the phone class — it must scroll with"
                + " the page there, so the viewport belongs to the content.");
      }
      if (!"0px".equals(heightVar)) {
        findings.add(
            where
                + ": --krt-footer-height is "
                + heightVar
                + " on the phone class, but a scrolling footer covers nothing — every consumer of"
                + " it (main's reserve, the max-height calcs, the org-chart scrollbar) would"
                + " reserve space twice.");
      }
    } else {
      if (!"fixed".equals(footerPosition)) {
        findings.add(
            where
                + ": the footer is "
                + footerPosition
                + " above the phone class; expected fixed.");
      } else if (footerH > reserved + SLACK_PX) {
        findings.add(
            String.format(
                "%s: the fixed footer is %.0fpx tall but main reserves only %.0fpx"
                    + " (--krt-footer-height=%s) — the bottom of a long page stays covered.",
                where, footerH, reserved, heightVar));
      }
    }

    double chrome = headerH + ("fixed".equals(footerPosition) ? footerH : 0);
    double sharePercent = chrome * 100.0 / height;
    if (sharePercent > MAX_CHROME_SHARE_PERCENT) {
      findings.add(
          String.format(
              "%s: header (%.0fpx) + pinned footer (%.0fpx) take %.0f%% of the %dpx viewport"
                  + " (ceiling %d%%).",
              where, headerH, footerH, sharePercent, height, MAX_CHROME_SHARE_PERCENT));
    }

    if (phone && headerH > MAX_PHONE_HEADER_HEIGHT_PX) {
      findings.add(
          String.format(
              "%s: the header is %.0fpx tall on a phone (ceiling %dpx) — something in its nav"
                  + " has been pushed onto a second row. The two ways that happens: the brand"
                  + " wrapping, or a third nav child landing in a new grid row.",
              where, headerH, MAX_PHONE_HEADER_HEIGHT_PX));
    }

    for (Object offender : list(probe.get("cutOff"))) {
      findings.add(where + ": cut off at the right edge — " + offender);
    }
    for (Object offender : list(probe.get("overlaps"))) {
      findings.add(where + ": overlap — " + offender);
    }
    for (Object offender : list(probe.get("unscrollableTables"))) {
      findings.add(where + ": wide table with no scrollable ancestor — " + offender);
    }
    return findings;
  }

  /**
   * The per-element offender lists the probe collected.
   *
   * @param where {@code WxH /path}
   * @param probe the page-side measurement
   * @param width viewport width in CSS pixels, which decides whether the touch floors apply
   * @return one line per finding
   */
  private static List<String> collectOffenders(String where, Map<String, Object> probe, int width) {
    List<String> findings = new ArrayList<>();
    if (width <= TOUCH_CLASS_MAX_WIDTH) {
      for (Object offender : list(probe.get("badControls"))) {
        findings.add(where + ": form control — " + offender);
      }
      for (Object offender : list(probe.get("modalIssues"))) {
        findings.add(where + ": modal — " + offender);
      }
    } else {
      for (Object offender : list(probe.get("badControls"))) {
        if (!offender.toString().startsWith(HIT_AREA_MARK)) {
          findings.add(where + ": form control — " + offender);
        }
      }
      for (Object offender : list(probe.get("modalIssues"))) {
        if (!offender.toString().startsWith(HIT_AREA_MARK)) {
          findings.add(where + ": modal — " + offender);
        }
      }
    }
    return findings;
  }

  /**
   * Reads a JS number that may arrive as {@code Integer} or {@code Double}.
   *
   * @param value the value Playwright unwrapped from the page
   * @return its {@code double} value, or {@code 0} when absent
   */
  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0.0;
  }

  /**
   * Reads a JS array of strings that may be absent.
   *
   * @param value the value Playwright unwrapped from the page
   * @return the list, or an empty one when absent
   */
  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value instanceof List<?> l ? (List<Object>) l : List.of();
  }

  /**
   * The measurement, as one page-side pass.
   *
   * <p>It runs in the page rather than through many Playwright round trips because the element set
   * is large (every element for the cut-off scan) and a per-element {@code boundingBox()} call over
   * a phone-sized Lager table costs seconds. Elements are described by a short CSS-ish label so a
   * finding names something a developer can find.
   */
  private static final String PROBE_JS =
      """
      ({ slack, deviceWidth }) => {
        const vw = Math.min(window.innerWidth, deviceWidth);
        const layoutWidth = window.innerWidth;
        const vh = window.innerHeight;
        const label = (el) => {
          const id = el.id ? '#' + el.id : '';
          const cls = (el.getAttribute('class') || '').trim().split(/\\s+/).filter(Boolean)
            .slice(0, 3).map((c) => '.' + c).join('');
          const text = (el.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 40);
          return el.tagName.toLowerCase() + id + cls + (text ? ' "' + text + '"' : '');
        };
        const scrollsHorizontally = (el) => {
          for (let n = el.parentElement; n; n = n.parentElement) {
            const ox = getComputedStyle(n).overflowX;
            if ((ox === 'auto' || ox === 'scroll')
                && n.getBoundingClientRect().right <= vw + slack) return true;
            if (n === document.body) break;
          }
          return false;
        };

        const DENSE_SELECTORS = (() => {
          const out = new Set();
          const walk = (list) => {
            for (const rule of Array.from(list || [])) {
              if (rule.cssRules && rule.cssRules.length) { walk(rule.cssRules); }
              const own = rule.style ? rule.style.cssText : '';
              if (!rule.selectorText || !own.includes('--touch-target-dense')) continue;
              for (const one of rule.selectorText.split(',')) {
                const sel = one.trim();
                if (sel) out.add(sel);
              }
              if (rule.cssRules) walk(rule.cssRules);
            }
          };
          for (const sheet of Array.from(document.styleSheets)) {
            try { walk(sheet.cssRules); } catch (e) {}
          }
          return Array.from(out);
        })();
        const floorFor = (c) =>
          DENSE_SELECTORS.some((sel) => {
            try { return c.matches(sel); } catch (e) { return false; }
          }) ? %d : %d;
        const DENSE_SET_EMPTY = DENSE_SELECTORS.length === 0;
        const CONTROLS = 'input, select, textarea, button, a.btn, summary, [role="button"],'
          + ' .krt-modal-close, .close-modal, .btn-close';
        const REPLACED = new Set(['input', 'select', 'textarea']);
        const hitBox = (c) => {
          const r = c.getBoundingClientRect();
          let w = r.width;
          let h = r.height;
          if (!REPLACED.has(c.tagName.toLowerCase())) {
            for (const which of ['::before', '::after']) {
              const ps = getComputedStyle(c, which);
              if (!ps || ps.content === 'none' || ps.content === 'normal') continue;
              if (ps.position === 'static') continue;
              const pw = parseFloat(ps.width);
              const ph = parseFloat(ps.height);
              if (pw > w) w = pw;
              if (ph > h) h = ph;
            }
          }
          const labels = [];
          const wrapping = c.closest('label');
          if (wrapping
              && wrapping.querySelectorAll('input, select, textarea, button, a').length === 1) {
            labels.push(wrapping);
          }
          if (c.id) {
            const bound = document.querySelector('label[for="' + CSS.escape(c.id) + '"]');
            if (bound) labels.push(bound);
          }
          for (const l of labels) {
            const lr = l.getBoundingClientRect();
            if (lr.width > w) w = lr.width;
            if (lr.height > h) h = lr.height;
          }
          return { width: w, height: h };
        };
        const header = document.querySelector('header');
        const footer = document.querySelector('.krt-footer');
        const main = document.querySelector('main') || document.querySelector('.page-wrapper');
        const headerHeight = header ? header.getBoundingClientRect().height : 0;
        const footerHeight = footer ? footer.getBoundingClientRect().height : 0;
        let mainPaddingBottom = 0;
        for (let n = main; n && n !== document.body; n = n.parentElement) {
          mainPaddingBottom += parseFloat(getComputedStyle(n).paddingBottom) || 0;
        }
        const footerHeightVar = getComputedStyle(document.documentElement)
          .getPropertyValue('--krt-footer-height').trim() || '(unset)';

        const cutOff = [];
        let maxRight = 0;
        let widest = '(none)';
        for (const el of document.body.querySelectorAll('*')) {
          const cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || cs.position === 'fixed') continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          if (r.right > maxRight) { maxRight = r.right; widest = label(el); }
          if (cutOff.length < 6 && r.right > vw + slack && !scrollsHorizontally(el)) {
            cutOff.push(label(el) + ' right=' + Math.round(r.right) + 'px');
          }
        }

        const unscrollableTables = [];
        for (const t of document.querySelectorAll('table')) {
          const r = t.getBoundingClientRect();
          if (r.width === 0) continue;
          if ((t.scrollWidth > vw + slack || r.right > vw + slack) && !scrollsHorizontally(t)) {
            unscrollableTables.push(label(t) + ' width=' + Math.round(t.scrollWidth) + 'px');
          }
        }

        const badControls = [];
        if (DENSE_SET_EMPTY) {
          badControls.push('the dense-floor set is EMPTY — no stylesheet declaring'
            + ' --touch-target-dense was readable, so every floor below is the full 44px and'
            + ' every dense control will read as a defect');
        }
        for (const c of document.querySelectorAll(CONTROLS)) {
          const cs = getComputedStyle(c);
          if (cs.display === 'none' || cs.visibility === 'hidden') continue;
          if (c.type === 'hidden') continue;
          const r = c.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          if (badControls.length < 6) {
            const floor = floorFor(c);
            if (r.width > vw + slack && !scrollsHorizontally(c)) {
              badControls.push('too wide: ' + label(c) + ' is ' + Math.round(r.width)
                + 'px wide in a ' + vw + 'px viewport');
            }
            const hit = hitBox(c);
            if (hit.height < floor - slack) {
              badControls.push('%s' + label(c) + ' is ' + Math.round(hit.height)
                + 'px tall, floor ' + floor + 'px');
            }
          }
        }

        const overlaps = [];
        const bell = document.querySelector('.notification-bell');
        const logoText = document.querySelector('nav .logo-text');
        if (bell && logoText) {
          const b = bell.getBoundingClientRect();
          const t = logoText.getBoundingClientRect();
          if (b.width > 0 && t.width > 0
              && t.right > b.left + slack && t.top < b.bottom && t.bottom > b.top) {
            overlaps.push('the wordmark runs to ' + Math.round(t.right)
              + 'px, under the notification bell which starts at ' + Math.round(b.left) + 'px');
          }
        }

        const modalIssues = [];
        const MODAL_SHAPES = %s;
        for (const ov of document.querySelectorAll('%s')) {
          const shape = MODAL_SHAPES.find((s) => ov.matches(s.root));
          if (!shape) continue;
          const previous = ov.style.display;
          const opened =
            shape.openClass && !ov.classList.contains(shape.openClass) ? shape.openClass : null;
          if (opened) ov.classList.add(opened);
          ov.style.display = 'flex';
          const modal = ov.querySelector(shape.box);
          if (modal) {
            const mr = modal.getBoundingClientRect();
            const name = (ov.id ? '#' + ov.id : label(modal));
            if (mr.right > vw + slack || mr.left < -slack) {
              modalIssues.push(name + ' frame spans ' + Math.round(mr.left) + '..'
                + Math.round(mr.right) + 'px in a ' + vw + 'px viewport');
            }
            if (mr.height > vh + slack) {
              modalIssues.push(name + ' is ' + Math.round(mr.height)
                + 'px tall in a ' + vh + 'px viewport');
            }
            const foot = shape.foot ? ov.querySelector(shape.foot) : null;
            if (foot && foot.scrollWidth > foot.clientWidth + slack) {
              modalIssues.push(name + ' footer buttons overflow their row ('
                + foot.scrollWidth + ' > ' + foot.clientWidth + 'px)');
            }
            const body = shape.body ? ov.querySelector(shape.body) : null;
            if (body) {
              const oy = getComputedStyle(body).overflowY;
              if (body.scrollHeight > body.clientHeight + slack
                  && oy !== 'auto' && oy !== 'scroll') {
                modalIssues.push(name + ' body is taller than its box but does not scroll');
              }
            }
            for (const c of modal.querySelectorAll(CONTROLS)) {
              const cs = getComputedStyle(c);
              if (cs.display === 'none' || cs.visibility === 'hidden' || c.type === 'hidden') continue;
              const cr = c.getBoundingClientRect();
              if (cr.width === 0 || cr.height === 0) continue;
              if (modalIssues.length < 12) {
                const floor = floorFor(c);
                const chit = hitBox(c);
                if (chit.height < floor - slack) {
                  modalIssues.push('%s' + name + ' > ' + label(c) + ' is ' + Math.round(chit.height)
                    + 'px tall, floor ' + floor + 'px');
                }
              }
            }
          }
          ov.style.display = previous;
          if (opened) ov.classList.remove(opened);
        }

        const docScrollWidth = Math.max(
          document.documentElement.scrollWidth, document.body.scrollWidth);

        return { innerWidth: layoutWidth, docScrollWidth, widestElement: widest,
                 widestRight: Math.round(maxRight),
                 scrollWidthHtml: document.documentElement.scrollWidth,
                 scrollWidthBody: document.body.scrollWidth,
                 overflowXHtml: getComputedStyle(document.documentElement).overflowX,
                 overflowXBody: getComputedStyle(document.body).overflowX,
                 footerPosition: footer ? getComputedStyle(footer).position : '(no footer)',
                 headerHeight, footerHeight, mainPaddingBottom, footerHeightVar,
                 cutOff, unscrollableTables, badControls, modalIssues, overlaps,
                 modalCount: document.querySelectorAll('%s').length };
      }
      """
          .formatted(
              DENSE_ACTION_FLOOR,
              TOUCH_TARGET_FLOOR,
              HIT_AREA_MARK,
              MODAL_SHAPES_JS,
              MODAL_ROOT_SELECTOR,
              HIT_AREA_MARK,
              MODAL_ROOT_SELECTOR);
}
