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
 * <p><b>Tagged both {@code smoke} and {@code e2e}</b>, and the second tag is the one that makes
 * this guard real. It mutates nothing and is target-agnostic, which is what {@code smoke} means —
 * but the smoke workflow is nightly and gated on an {@code E2E_BASE_URL} staging host that does not
 * exist yet, so on its own this file would only ever run when somebody remembered to start it
 * locally. The {@code e2e} tag puts it on every pull request carrying the {@code e2e} label, which
 * is exactly the set that touches frontend flows, auth or controllers. Owner decision 2026-09-13,
 * at a cost of roughly five minutes on those runs.
 */
// LAST in the suite, and that is a dependency rather than a preference: this class measures
// against the rows the destructive CRUD flows create, and a fresh stack hides what it exists to
// find. `junit-platform.properties` selects ClassOrderer.OrderAnnotation, under which every class
// without @Order sorts at Integer.MAX_VALUE / 2 — so this one value is the whole mechanism.
@Order(Integer.MAX_VALUE)
// Deliberately NOT @Tag("smoke") (owner decision 2026-09-13).
//
// e2e-smoke.yml has a 15-minute ceiling and passes no `-Pe2e.device`, so with the smoke tag
// this class walked all FIVE device classes over all 65 routes in one job — the exact shape
// that had just blown a 45-minute budget in the e2e gate and forced the browser x device
// fan-out. It is dormant only because `vars.E2E_BASE_URL` is empty.
//
// The e2e gate already measures all five classes on three engines, which is what REQ-UI-009
// needs. Smoke asks a different question — is this deployment alive — against a REAL
// environment, and the suite is documented as non-destructive and safe to run there; this
// class opens every modal on every route, which is more than that contract allows.
@Tag("e2e")
class TouchClassLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Where the screenshots go; one PNG per page and device class. */
  // `build/e2e/...`, which is what e2e.yml's upload step globs — `build/e2e-artifacts/`
  // matched none of its three patterns, so every capture this class took was discarded
  // with the runner. Every other e2e class writes under `build/e2e` for exactly that
  // reason. Sharper since screenshots were confined to one shard so a reviewer would have
  // one good set to open: the set existed and never left the machine.
  private static final Path ARTIFACTS = Path.of("build", "e2e", "touch-layout");

  /**
   * Share of the viewport height the sticky header and the fixed footer may occupy together.
   *
   * <p>A third is already generous: it leaves two thirds for the page on a 812px phone. The number
   * is a ceiling on an obvious failure, not a design target — it exists so "the chrome grew" is
   * caught, not to tune the chrome.
   */
  private static final int MAX_CHROME_SHARE_PERCENT = 33;

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
          // 810x1080, NOT 768x1024. `PHONE_MAX_WIDTH`, the `setIsMobile` gate and the
          // stylesheet's `@media (width <= 768px)` are all INCLUSIVE, so a 768px-wide entry renders
          // and is asserted as the phone class — static footer, `--krt-footer-height: 0px`, mobile
          // emulation — while being documented as tablet portrait. That left REQ-UI-009's
          // 769-1023px band covered by no class at all, and a regression confined to it invisible:
          // the header's bell-clearance reserve is scoped `<= 768px` while the bell itself stays a
          // 44px fixed overlay up to 1024px, which is exactly that shape.
          new int[] {810, 1080},
          new int[] {1024, 768},
          new int[] {1280, 800},
          new int[] {1600, 900});

  // THE ROUTE LIST USED TO LIVE HERE, hand-maintained, and it was wrong once: seventeen page routes
  // were absent until 2026-09-13 while the Javadoc in this place and REQ-UI-009's "Enforced by"
  // clause both said every route was covered. CorePagesSmokeE2eTest and AdminPagesSmokeE2eTest kept
  // their own copies of the same information, so three lists had to agree by hand, and did not.
  //
  // It is now `FrontendPageRoutes.PAGES` in the `test-support` module, and PageRouteCatalogueTest
  // fails the build when the dispatcher answers a variable-free GET route that is in no list at
  // all — so what was "somebody remembered" is a gate. The catalogue lives there rather than on
  // E2eSupport because that gate has to run in `check`, which does not compile this source set.
  //
  // What has NOT changed is how a page is recognised: at runtime, by its app shell. An entry that
  // turns out not to be a page is skipped by the sweep below, never filtered out of the catalogue —
  // which is how a route that quietly stops rendering its shell surfaces here, instead of vanishing
  // from a curated list.

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

    // Coverage is ASSERTED, not merely printed. Two silent-pass modes were possible without this
    // and both were found in review: a list with no rows yields no detail link and dropped out of
    // the audit, and a page without an app shell returns no findings — so a session that expired
    // part-way through would SKIP every remaining combination and the suite would pass having
    // measured almost nothing. `assertTrue(findings.isEmpty())` cannot tell "clean" from "never
    // looked".
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
                  // Reported as a touch device, because REQ-UI-009's floors are touch-class rules
                  // and some of them sit behind hover/pointer media queries.
                  .setHasTouch(true)
                  .setIsMobile(width <= PHONE_MAX_WIDTH && ENGINE_SUPPORTS_IS_MOBILE))) {
        Page page = context.newPage();
        // Two collectors, because they answer different questions. `measured` is about the ROUTE
        // LIST — the floor and the cross-class comparison are only meaningful against something of
        // known size. Detail views are reached by following whatever link a list happens to render,
        // so their paths vary per run and per device and cannot be compared to anything; counting
        // them in the same set produced lines like "66 of 65 routes measured".
        Set<String> measured = new LinkedHashSet<>();
        Set<String> measuredDetails = new LinkedHashSet<>();
        measuredByDevice.put(deviceLabel, measured);
        detailsByDevice.put(deviceLabel, measuredDetails);
        for (String path : FrontendPageRoutes.PAGES) {
          // One page that hangs must not end the audit: the whole point is to name every offender,
          // so a failure to measure is itself a finding and the sweep carries on.
          findings.addAll(measureSafely(page, baseUrl, path, deviceLabel, width, height, measured));

          // Detail views are reached by FOLLOWING A LINK from their list, not by seeding an entity.
          // 33 of the frontend's routes carry a path variable and none of them can be visited by
          // name; seeding one of each would also make this suite destructive, and it is tagged
          // `smoke` precisely so it can run against a shared deployment. Taking the first detail
          // link the list actually renders keeps it read-only and measures whatever data is really
          // there. A list with no rows yields no link and is reported as uncovered rather than
          // silently passing.
          String detail = firstDetailLink(page, path);
          if (detail != null) {
            findings.addAll(
                measureSafely(page, baseUrl, detail, deviceLabel, width, height, measuredDetails));
          } else if (FrontendPageRoutes.DETAIL_LIST_PAGES.contains(path)) {
            // Named, not silently skipped - this is the half the comment above promised and the
            // code did not do. It is reported rather than failed because an empty list is a
            // legitimate state of a fresh or shared stack, which is exactly what the `smoke` tag
            // exists to let this run against.
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
    // The UNION of every class, not the first one. `entrySet().iterator().next()` is always
    // 375x812, and `missing = first - measured` can only ever report a LATER class going quiet: if
    // the phone class is the one that dies, every wider class measures a superset and nothing is
    // missing anywhere. The floor was the only backstop, and 30 of 64 is met by a session that
    // expires halfway — which is the phone class, the one this guard exists for, reporting clean
    // on 35 routes. Against the union, a class that stops short is named whichever class it is.
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
      // The WHOLE message, newlines folded — not its first line.
      //
      // The pattern is `\\s+` and the SECOND BACKSLASH IS LOAD-BEARING: since Java 15 `\s` is
      // a valid string escape for a space, so the single-backslash form compiles without
      // complaint to the pattern " +" and folds runs of SPACES while leaving every newline in
      // place — the exact character this exists to fold. It shipped that way and was caught in
      // review; the page-side JS in this same file had it right, which is what gave it away.
      //
      // Taking `.lines().findFirst()`
      // threw
      // away the only useful half: Playwright formats a page-side error as a multi-line `Error {`
      // block whose first line is literally "Error {", so five findings on /ship-data named the
      // brace and nothing else. A diagnostic that cannot say what went wrong costs a CI round trip
      // per attempt.
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
   * Finds the first detail link a list page renders under its own path.
   *
   * <p>Matches {@code <listPath>/<id>} where the id looks like a UUID or a number — the two shapes
   * the frontend's detail routes use — and deliberately ignores the {@code /create}, {@code /new}
   * and {@code /search} siblings, which are pages in their own right and already in the sweep.
   *
   * @param page the page currently showing the list
   * @param listPath the app-relative path of that list, used as the href prefix
   * @return the app-relative path of the first detail view, or {@code null} when the list is empty
   */
  private static String firstDetailLink(Page page, String listPath) {
    if ("/".equals(listPath)) {
      return null;
    }
    Object href =
        page.evaluate(
            """
            (prefix) => {
              // Plain string work rather than a built RegExp: escaping a path into a pattern
              // inside a Java text block inside a JS string is three layers of backslash and
              // exactly the kind of cleverness that fails to compile for an hour.
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
    // Re-assert the viewport before every page, and force it to actually take.
    //
    // A full-page capture resizes the viewport to the content size to take its picture. Playwright
    // does not consider that its own viewport change, so a plain `setViewportSize(width, height)`
    // afterwards is a no-op — the size it tracks already equals the one being asked for — and the
    // page stays as wide as the previous screenshot left it. Measured: six pages reported viewports
    // of 401-526px on the 375px class, and every width comparison on them was against that wrong
    // reference. Going through a different height first makes it a real resize.
    // Only where a screenshot can actually have happened. A full-page capture resizes the
    // viewport and Playwright's own `setViewportSize` back to the same numbers is then a no-op, so
    // this goes through a different height to force a real resize. But `screenshotSafely` returns
    // early unless the engine is the screenshot one, so on the other ten jobs of the matrix this
    // was
    // 130 real relayouts each, undoing a state they cannot enter.
    if (SCREENSHOT_ENGINE.equals(ENGINE)) {
      page.setViewportSize(width, height + 1);
      page.setViewportSize(width, height);
    }
    E2eSupport.navigate(page, baseUrl + path);
    // NOT `NETWORKIDLE`: this app never goes idle. The notification badge polls, the live-sync
    // WebSocket stays open, and the P4K import page polls its job list — so waiting for a quiet
    // network times out after 30s and takes the whole sweep down with it, which is exactly how the
    // first modal run died 43 measurements in.
    //
    // What the measurement actually needs is a settled LAYOUT: the document parsed, the webfont
    // applied (Lato changes every text box, and the header height is text-driven), and one beat for
    // the ResizeObserver that publishes --krt-footer-height.
    //
    // The THIRD condition is the real one, and it replaced a flat `waitForTimeout(150)`. That sleep
    // was a guess at the ResizeObserver beat that publishes `--krt-footer-height`: ~50 seconds of
    // dead wall clock per shard across ~350 measurements, and still a guess — a slow runner can
    // miss the beat, so it bought no flakiness resistance either. Waiting for the property to exist
    // waits for exactly what the measurement needs, and returns as soon as it does.
    //
    // It is CONDITIONAL on the page having a footer, and that guard is load-bearing. `sidebar.js`
    // publishes the property inside `if (footerEl)`, and eight templates render no
    // `fragments/sidebar` at all — the five error pages, `terms-accept.html` and
    // `pending-approval.html`. On any of them an unconditional wait runs to Playwright's full 30s
    // default, `measureSafely` turns the TimeoutError into a FINDING, and the documented
    // "SKIPPED — no app shell" line ten lines below is never reached. Two ways that bites: a route
    // that answers 403/404/500 during a run — exactly what this sweep exists to report — costs 30s
    // per device class and reports a timeout instead of the status; and `/terms/accept`, which the
    // consent gate makes mandatory reading, would hang five classes on a page that renders
    // perfectly. That one is no longer hypothetical: `FrontendPageRoutes.PAGES` carries the route,
    // and `PageRouteCatalogueTest` fails the build if it stops carrying it, so this guard is what
    // keeps it cheap. The catalogue promises the opposite of a hang — an entry that is not a page
    // is skipped at runtime, never filtered out of the list — and the sleep this replaced did have
    // that property.
    page.waitForFunction(
        "() => document.readyState === 'complete'"
            + " && (!document.fonts || document.fonts.status === 'loaded')"
            + " && (!document.querySelector('.krt-footer')"
            + "     || getComputedStyle(document.documentElement)"
            + "         .getPropertyValue('--krt-footer-height').trim() !== '')");

    // Did the route actually SERVE this path, or send us somewhere else?
    //
    // Without this, a redirect is counted as a measurement of the page it landed on.
    // `/pending-approval` is the case that proved it: `PendingApprovalPageController` answers
    // `redirect:/` for an ACTIVE role-bearing session, which is the only kind this suite has, so
    // the
    // browser sat on the dashboard, the shell check passed, and the route joined `measured` having
    // never been looked at — every class measured the dashboard twice and the coverage line
    // over-reported by one. The comment on that entry claimed it "skips at every class and cancels
    // out of the coverage comparison"; it did neither.
    //
    // Reported rather than silently dropped, because a route that has started redirecting is worth
    // seeing. Query and fragment are ignored: a redirect that only adds `?foo` is still this page.
    // The base URL is normalised before stripping, because it is operator-supplied on staging
    // (`E2E_BASE_URL` / `-Pe2e.baseUrl`, which is how the smoke workflow points at a deployment).
    // A trailing slash there would leave every landed path without its leading one, every route
    // would look redirected, and the sweep would skip all 66 and fail on the coverage floor —
    // loud, but for entirely the wrong reason. The ephemeral stack's own URL carries no slash.
    String origin = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String landedPath = page.url().replace(origin, "").replaceAll("[?#].*$", "");
    if (landedPath.isEmpty()) {
      // The dashboard strips to the empty string, since the origin has no trailing slash.
      landedPath = "/";
    }
    if (!landedPath.equals(path) && !landedPath.equals(path + "/")) {
      System.out.printf(
          "[touch-layout] %-34s SKIPPED — redirected to %s (not measured, not counted)%n",
          deviceLabel + " " + path, landedPath);
      return findings;
    }

    // Is this a page at all? The route list is taken from the controllers rather than curated, so
    // it contains fragment and JSON endpoints too. A Basetool page is recognised by its app shell;
    // anything without one is skipped and said so, which is also how a page that quietly stops
    // rendering the shell would surface.
    //
    // `main` OR `.page-wrapper`, because two pages have no `<main>` and were being skipped as if
    // they were not pages: `operation-detail.html` has none at all, and `mission-detail.html`'s is
    // gated on `th:if="${isNew}"`, so an existing mission has none either. Those are the two
    // densest views in the app and the ones the phone-class contract most needs measured, and the
    // guard was silently declining both. The brand check still keeps fragments and JSON endpoints
    // out, which is what this test is for.
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

    // MEASURE FIRST, screenshot second — the order is load-bearing. A full-page capture widens the
    // viewport to the content size to take the picture, and a measurement taken afterwards can
    // still see that widened state: `window.innerWidth` grows with it, the overflow comparison
    // becomes 534 > 534 and the page reports clean. That is exactly what happened on the second
    // run of this guard — every check passed while the PNGs it had just written were 10-159px
    // wider than the viewport.
    @SuppressWarnings("unchecked")
    Map<String, Object> probe =
        (Map<String, Object>)
            page.evaluate(PROBE_JS, Map.of("slack", SLACK_PX, "deviceWidth", width));

    String slug = path.equals("/") ? "dashboard" : path.replaceAll("^/", "").replace('/', '-');
    // The screenshot is EVIDENCE, the measurement above is the assertion — so a screenshot that
    // cannot be taken may not discard a measurement that already succeeded. It did exactly that:
    // `probe` is computed first, then this call threw, and `measureSafely` turned the whole page
    // into "could not be measured" with every finding it had just computed thrown away. A browser
    // refusing to rasterise a very long page (WebKit caps its surface where Chromium stitches) is
    // not an app defect and must not read like one, but it must not be silent either.
    screenshotSafely(
        page,
        new Page.ScreenshotOptions()
            .setPath(ARTIFACTS.resolve(deviceLabel + "__" + slug + ".png"))
            .setFullPage(true),
        deviceLabel + " " + path);

    // Photograph every modal too, on the touch classes.
    //
    // The measurement above already covers them, but a modal is `display: none` in the page
    // screenshot — so an audit that is supposed to be looked at would contain no picture of the 42
    // surfaces where the tool asks for input. Showing one at a time and capturing the viewport
    // gives a reviewer the same evidence for a dialog as for a page, and the touch classes are
    // where a dialog gets tight. The device label belongs in the FILENAME: without it the 768px
    // pass silently overwrote the 375px pictures, and the directory looked complete while holding
    // only the wider half.
    if (width <= PHONE_MAX_WIDTH && SCREENSHOT_ENGINE.equals(ENGINE)) {
      int count = (int) number(probe.get("modalCount"));
      for (int i = 0; i < count; i++) {
        String id =
            String.valueOf(
                page.evaluate(
                    "(i) => { const o = document.querySelectorAll('.krt-modal-overlay')[i];"
                        + " if (!o) return ''; o.dataset.krtPrevDisplay = o.style.display;"
                        + " o.style.display = 'flex'; return o.id || ('modal-' + i); }",
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
            "(i) => { const o = document.querySelectorAll('.krt-modal-overlay')[i]; if (!o) return;"
                + " o.style.display = o.dataset.krtPrevDisplay || '';"
                + " delete o.dataset.krtPrevDisplay; }",
            i);
      }
    }

    String where = deviceLabel + " " + path;

    // The rules themselves live in their own methods. This one was 282 lines that navigated,
    // resized, skipped non-pages, ran the probe, wrote page and modal screenshots, printed two
    // diagnostics and evaluated twelve assertions — so a reviewer had to read all of it to see
    // whether a change to the footer branch could reach the chrome-budget branch. Each part now
    // takes the already-computed `probe` map and answers one question.
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
    // Printed for every page, pass or fail. The first version of this guard reported clean on five
    // pages whose screenshots were 10-159px wider than the viewport, and the only reason that was
    // caught is that somebody measured the PNGs. A guard whose measurements are invisible until it
    // fails cannot be sanity-checked against the artifacts it writes beside them.
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

    // On the touch classes the browser runs in MOBILE EMULATION, and there `innerWidth` is the
    // LAYOUT viewport — which the viewport meta lets content widen. So a page whose content does
    // not fit reports an innerWidth larger than the device width, and that is not a measurement
    // artefact but the overflow itself, expressed the way a phone expresses it: the page zooms out
    // and the member reads everything smaller. It is reported separately from the scrollWidth check
    // because the two catch it on different classes — and because every width comparison further
    // down is against `innerWidth`, so when this fires those comparisons are measuring a page that
    // has already given up fitting.
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

    // The footer's behaviour is the device class's, not the page's — but it is asserted per page
    // because it is published by a script that runs per page, and a template that forgets the
    // shared layout would take its footer with it.
    String footerPosition = String.valueOf(probe.get("footerPosition"));
    double headerH = number(probe.get("headerHeight"));
    double footerH = number(probe.get("footerHeight"));
    double reserved = number(probe.get("mainPaddingBottom"));
    String heightVar = String.valueOf(probe.get("footerHeightVar"));
    boolean phone = width <= PHONE_MAX_WIDTH;

    if (phone) {
      // Owner decision 2026-09-13: on a phone the footer scrolls away with the page, so it is
      // only in the way when the member has actually reached the end of it.
      if ("fixed".equals(footerPosition)) {
        findings.add(
            where
                + ": the footer is still position:fixed on the phone class — it must scroll with"
                + " the page there, so the viewport belongs to the content.");
      }
      // The eight consumers of --krt-footer-height all mean "how much of the viewport bottom is
      // covered". A footer that scrolls covers nothing, and a leftover non-zero value would have
      // every one of them reserve space for a footer that is not there.
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
      // Above the phone class the footer stays pinned, and then the reserve is what keeps the
      // last line of a long page reachable.
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

    // Only chrome that actually holds viewport space counts against the budget: a footer that
    // scrolls with the page is content, not chrome.
    double chrome = headerH + ("fixed".equals(footerPosition) ? footerH : 0);
    double sharePercent = chrome * 100.0 / height;
    if (sharePercent > MAX_CHROME_SHARE_PERCENT) {
      findings.add(
          String.format(
              "%s: header (%.0fpx) + pinned footer (%.0fpx) take %.0f%% of the %dpx viewport"
                  + " (ceiling %d%%).",
              where, headerH, footerH, sharePercent, height, MAX_CHROME_SHARE_PERCENT));
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
    // REQ-UI-009 puts the 44px floor on the TOUCH classes, not on every class: it is a fat-finger
    // rule, and the app's own touch block is scoped `width <= 1024px` for exactly that reason. The
    // first full sweep applied it everywhere and produced 490 findings at 1280px and 1600px that
    // were not defects at all — a dense desktop table is allowed to be dense.
    if (width <= TOUCH_CLASS_MAX_WIDTH) {
      for (Object offender : list(probe.get("badControls"))) {
        findings.add(where + ": form control — " + offender);
      }
      for (Object offender : list(probe.get("modalIssues"))) {
        findings.add(where + ": modal — " + offender);
      }
    } else {
      // Above the touch classes only GEOMETRY matters, never hit areas — for a page control as
      // much as for a modal's. `badControls` used to be discarded wholesale here, so a 1700px
      // <select> with no scrollable ancestor at 1600x900 was measured, pushed and thrown away,
      // while its sibling list was already filtered entry-by-entry to keep exactly that half.
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
        // Compare against the DEVICE width, not the layout viewport.
        //
        // Under mobile emulation a page whose content does not fit widens the layout viewport
        // instead of scrolling, so `innerWidth` becomes the overflowed width — and every check that
        // compares against it then finds nothing wrong, because everything fits the widened page by
        // definition. That is how three pages reported "does not fit the device" and produced no
        // offender at all. `ref` is the screen the member actually holds.
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
        // An element is excused only by an ancestor that BOTH scrolls horizontally AND fits on
        // screen. The first version of this asked only the first half, which excused everything
        // inside a .table-responsive that was itself wider than the phone — so five pages that
        // overflowed by 10-159px reported clean while their full-page screenshots came out wider
        // than the viewport. A scroll container that does not fit is not a solution, it is the bug.
        const scrollsHorizontally = (el) => {
          for (let n = el.parentElement; n; n = n.parentElement) {
            const ox = getComputedStyle(n).overflowX;
            if ((ox === 'auto' || ox === 'scroll')
                && n.getBoundingClientRect().right <= vw + slack) return true;
            if (n === document.body) break;
          }
          return false;
        };

        // The EFFECTIVE hit area, not the element's own border box.
        //
        // REQ-UI-009's floor is a rule about how big a target a finger has to find, and a border
        // box is only one of the three ways this app produces one. The CI sweep proved it the
        // expensive way: `.oc-collapse` reported 26px on 15 org-chart nodes and is not a defect at
        // all — org-chart.css deliberately keeps the chevron 1.6rem and stretches the target to
        // `var(--touch-target)` with a transparent centred `::after`, the standard "small glyph,
        // fat-finger target" pattern, and a border-box measurement cannot see it. Reporting those
        // as findings would have pushed the app to grow a control that is already compliant.
        //
        // Three candidate boxes, largest dimension wins:
        //   1. the element itself;
        //   2. a positioned `::before`/`::after` overlay it generates — skipped for form controls,
        //      because a replaced element renders no pseudo-element, so believing a declared one
        //      there would MASK a real defect rather than excuse a false one;
        //   3. a `<label>` that activates it — either wrapping it (and wrapping nothing else
        //      interactive, or the label is shared and cannot be claimed as this control's target)
        //      or pointing at it by `for`. Clicking a label activates its control, which is what
        //      makes the whole label row the target; /inventory/my's filter checkboxes are 20px
        //      squares inside 44px label rows.
        //
        // Deliberately NOT done with elementFromPoint sampling: a point outside the viewport or
        // under a sticky header returns null or the wrong element, which would invent findings.
        // This can only ever widen a measurement, so it removes false positives without hiding
        // anything.
        // The dense exemption of REQ-UI-009, as ONE helper: a repeated IN-ROW control may stop at
        // the dense floor, because raising every one of them would turn a scannable table into a
        // list of cards. `.master-row` joined by owner decision 2026-09-13 (the blueprint list rows
        // are a scan-and-tap list where density is the point); `item-checkbox`, `matrix-flag` and
        // `bank-row-toggle` joined on the same reading once seeded data first exposed them. Never a
        // form button or a standalone control: `.btn-xs2` was refused this exemption for that
        // reason.
        //
        // It was two byte-identical copies, one for page controls and one for controls inside a
        // dialog, and the list has taken four separate additions — so update one copy and the same
        // control is a defect in a dialog and compliant on a page, or the reverse. `hitBox` in this
        // same script is already one shared helper; this is the matching extraction.
        //
        // The SET IS READ FROM THE STYLESHEET, not written here. A hand-kept copy had already
        // drifted from the CSS in both directions at once: the list knew six classes while the
        // stylesheets declared eight `--touch-target-dense` consumers, so `.bank-chart-range-btn`
        // and `.krt-bp-imp-suggestion` — a real <button> — would be reported as 32px against a 44px
        // floor the design system does not put on them, the false-positive class the ::after
        // hit-area handling exists to avoid. The reverse is worse: a class added here and forgotten
        // in the CSS is silently exempt forever. The design system declares the exemption, so the
        // design system is asked.
        //        // The selectors are kept WHOLE and matched with `Element.matches`, rather than picked
        // apart into class names. Harvesting classes cannot express a compound: the design system
        // writes the dense override as `.btn.btn-xs` (deliberately — REQ-UI-009 records that the
        // extra specificity is what stops a page's inline rule winning), and lifting each class out
        // of it puts a bare `btn` in the exempt set. That would drop EVERY button in the app to the
        // 32px floor while the requirement says `.btn` itself keeps 44px on every class — a guard
        // that passes by measuring against the wrong number, which is the one failure mode worse
        // than a red build. Matching also gets `.pa-sort-controls .pa-sort-btn` and
        // `input.item-checkbox` exactly right for free, where the old "subject compound only"
        // approximation had to be reasoned about.
        const DENSE_SELECTORS = (() => {
          const out = new Set();
          const walk = (list) => {
            for (const rule of Array.from(list || [])) {
              // NOT `if (rule.cssRules)`. Since CSS Nesting shipped, a plain CSSStyleRule carries
              // an EMPTY CSSRuleList — an object, therefore truthy — so that test treated every
              // style rule as a container, skipped it, and returned an EMPTY SET in all three
              // engines. Verified against Chromium 151: `.btn-xs` reports
              // `cssRules=CSSRuleList(len=0), truthy=true`. Length is what distinguishes a
              // container from a leaf.
              if (rule.cssRules && rule.cssRules.length) { walk(rule.cssRules); }
              // `rule.style.cssText` is THIS rule's own declarations; `rule.cssText` would also
              // carry a nested child's, so a parent that merely CONTAINS a dense rule would be
              // exempted along with it.
              const own = rule.style ? rule.style.cssText : '';
              if (!rule.selectorText || !own.includes('--touch-target-dense')) continue;
              for (const one of rule.selectorText.split(',')) {
                const sel = one.trim();
                // `:root` declares the token and matches no control; it is harmless to keep and
                // cheaper than special-casing.
                if (sel) out.add(sel);
              }
            }
          };
          for (const sheet of Array.from(document.styleSheets)) {
            // A cross-origin sheet throws on .cssRules; there are none today, and skipping one
            // would under-populate the set, which fails loud below rather than exempting silently.
            try { walk(sheet.cssRules); } catch (e) { /* not readable */ }
          }
          return Array.from(out);
        })();
        const floorFor = (c) =>
          DENSE_SELECTORS.some((sel) => {
            // A selector out of the CSSOM is always valid, but `matches` is the only thing here
            // that can throw on one, and a throw would be read as "page unmeasurable".
            try { return c.matches(sel); } catch (e) { return false; }
          }) ? %d : %d;
        // An empty set means no stylesheet declaring the token was readable, which would put the
        // full 44px floor on every dense control at once. Reported rather than assumed: silence
        // here used to mean "compliant", and it would now mean "measured against the wrong floor".
        //
        // RECORDED here, REPORTED where `badControls` exists. This used to push straight into
        // `badControls`, which is declared ~100 lines further down — so the first time the guard
        // actually fired it threw `ReferenceError: Cannot access 'badControls' before
        // initialization` from inside the probe, `measureSafely` turned that into "could not be
        // measured", and EVERY page of the class came back unmeasurable. A guard that cannot run
        // is worse than no guard: it converted a precise diagnosis into a blanket failure.
        const DENSE_SET_EMPTY = DENSE_SELECTORS.length === 0;
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
        const main = document.querySelector('main');
        const headerHeight = header ? header.getBoundingClientRect().height : 0;
        const footerHeight = footer ? footer.getBoundingClientRect().height : 0;
        // The bottom reserve is not always on <main>. mission-detail.html (which also serves
        // /missions/new) puts it on an outer `.page-wrapper` and zeroes <main>'s own padding so the
        // two do not add up — a perfectly good arrangement that an assertion reading only <main>
        // calls a defect. Summing the chain from <main> up to <body> measures what actually keeps
        // content off the footer, wherever the page chose to put it.
        let mainPaddingBottom = 0;
        for (let n = main; n && n !== document.body; n = n.parentElement) {
          mainPaddingBottom += parseFloat(getComputedStyle(n).paddingBottom) || 0;
        }
        const footerHeightVar = getComputedStyle(document.documentElement)
          .getPropertyValue('--krt-footer-height').trim() || '(unset)';

        // NOTE: there is deliberately no "is this element under the footer right now" check.
        // A fixed footer overlays whatever happens to be beneath it at the current scroll
        // position — that is what fixed means, and the reserve above is what guarantees the
        // content can still be scrolled clear of it. The first version of this file flagged 60
        // such overlaps and every one was noise: sidebar drawer links (z-index 2000, painted
        // ABOVE the footer's 999) and ordinary content sitting below the fold at scroll 0.
        // The invariant worth testing is the reserve, not an instantaneous rectangle.
        // ONE walk of the document, not two. The widest-box diagnostic below used to repeat this
        // exact sweep — same selector, same getComputedStyle, same getBoundingClientRect — purely
        // to fill a printf, and its own comment called it "DIAGNOSTIC ONLY, never an assertion".
        // Style resolution plus forced layout per element is the dominant in-page cost, and on the
        // pages this file calls out as large (the materials matrix, /hangar, the Lager tables) it
        // was thousands of elements walked twice, ~350 times per shard. This loop's filter is a
        // superset of the old one — it additionally skips `position: fixed`, which for a "widest
        // laid-out box" line is at worst worth knowing.
        const cutOff = [];
        let maxRight = 0;
        let widest = '(none)';
        for (const el of document.body.querySelectorAll('*')) {
          const cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || cs.position === 'fixed') continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          if (r.right > maxRight) { maxRight = r.right; widest = label(el); }
          // Cap FIRST. `scrollsHorizontally` walks every ancestor to the root, so with the cap
          // last it ran for every remaining element on precisely the overflowing pages where this
          // probe is already slowest — and threw the answer away. The page-control loop below
          // already reads this way round.
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
        // The dense-floor diagnosis from above, reported now that there is a list to report into.
        if (DENSE_SET_EMPTY) {
          badControls.push('the dense-floor set is EMPTY — no stylesheet declaring'
            + ' --touch-target-dense was readable, so every floor below is the full 44px and'
            + ' every dense control will read as a defect');
        }
        for (const c of document.querySelectorAll('input, select, textarea, button, a.btn')) {
          const cs = getComputedStyle(c);
          if (cs.display === 'none' || cs.visibility === 'hidden') continue;
          if (c.type === 'hidden') continue;
          const r = c.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          // Two INDEPENDENT properties, so both are pushed. They used to be an if/else, and a
          // <select> that was 427px wide in a 375px viewport AND 22px tall reported only its width
          // — the developer fixed that, re-ran a five-minute sweep across three shards, and only
          // then learned about the height. The cap is checked once, up front, so `hitBox` (two
          // getComputedStyle calls plus a document-wide querySelector) is not evaluated for every
          // remaining control on precisely the overflowing pages where this is already slowest.
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

        // EVERY modal on this page, measured without knowing how any of them opens.
        //
        // 42 modal instances live across the templates and each has its own trigger — a row action,
        // a menu entry, a server-rendered flag. Driving all of them would mean encoding 42 click
        // paths and would still miss the ones a fixture cannot reach. Every one of them is instead
        // in the DOM already, hidden by `display: none` on `.krt-modal-overlay`; showing one, taking
        // its geometry and putting the inline style back measures it in its real layout context.
        // One at a time, so two overlays never stack.
        //
        // What this can NOT see, stated rather than implied: a modal whose body is filled by script
        // when it opens is measured empty, so its content height is understated. The structural
        // properties below — does the frame fit, do the footer buttons fit, does the body scroll,
        // are the controls hittable — hold regardless of what is poured into it.
        // The one overlap worth asserting: the notification bell floats over the header.
        //
        // `.notification-bell` is `position: fixed; top: 1rem; right: 1rem`, so it is not part of
        // the header's flex row and the header cannot reserve space for it. On a narrow screen the
        // wordmark then truncates at the header's own padding edge and its last characters —
        // ellipsis included — are painted underneath the bell. A general "does any fixed thing
        // cover any text" rule was tried first and produced 60 findings and no defects; this one
        // names the exact pair that actually collides.
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
        for (const ov of document.querySelectorAll('.krt-modal-overlay')) {
          const previous = ov.style.display;
          ov.style.display = 'flex';
          const modal = ov.querySelector('.krt-modal');
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
            const foot = ov.querySelector('.krt-modal-foot');
            if (foot && foot.scrollWidth > foot.clientWidth + slack) {
              modalIssues.push(name + ' footer buttons overflow their row ('
                + foot.scrollWidth + ' > ' + foot.clientWidth + 'px)');
            }
            const body = ov.querySelector('.krt-modal-body');
            if (body) {
              const oy = getComputedStyle(body).overflowY;
              if (body.scrollHeight > body.clientHeight + slack
                  && oy !== 'auto' && oy !== 'scroll') {
                modalIssues.push(name + ' body is taller than its box but does not scroll');
              }
            }
            for (const c of modal.querySelectorAll(
                'button, input, select, textarea, a.btn, .krt-modal-close')) {
              const cs = getComputedStyle(c);
              if (cs.display === 'none' || cs.visibility === 'hidden' || c.type === 'hidden') continue;
              const cr = c.getBoundingClientRect();
              if (cr.width === 0 || cr.height === 0) continue;
              // Cap first, for the same reason: `hitBox` makes two getComputedStyle calls per
              // control, and a modal with thirty controls paid all of them after the list was full.
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
        }

        // Widest right edge of any laid-out box — DIAGNOSTIC ONLY, never an assertion. It routinely
        // and legitimately exceeds the viewport: /hangar measured 838px and the materials matrix
        // 15759px, both of them content living inside a scroll container, which is precisely what
        // REQ-UI-009 asks for. What decides whether the PAGE scrolls sideways is scrollWidth, and
        // the two disagreeing is the normal, healthy case. Printed so a reader can tell the two
        // apart at a glance instead of re-deriving it from a screenshot.
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
                 modalCount: document.querySelectorAll('.krt-modal-overlay').length };
      }
      """
          .formatted(DENSE_ACTION_FLOOR, TOUCH_TARGET_FLOOR, HIT_AREA_MARK, HIT_AREA_MARK);
}
