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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 *       shorter than the 44px touch floor cannot be hit reliably (REQ-UI-009's floor; its
 *       documented exception for {@code .btn-xs} / {@code .btn-icon} dense row actions is
 *       honoured).
 * </ol>
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
@Tag("smoke")
@Tag("e2e")
class TouchClassLayoutE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Where the screenshots go; one PNG per page and device class. */
  private static final Path ARTIFACTS = Path.of("build", "e2e-artifacts", "touch-layout");

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
   */
  private static final int DENSE_ACTION_FLOOR = 32;

  /** Sub-pixel slack, for the same reason the sibling layout guard carries one. */
  private static final double SLACK_PX = 1.0;

  /**
   * All four device classes of REQ-UI-009, with the tablet taken at both orientations.
   *
   * <p>375×812 is the iPhone viewport the phone class is written for. 768×1024 and 1024×768 are the
   * tablet class at both orientations, and they really are different layouts here because every
   * breakpoint is width-only (`width <= 768px` / `<= 1024px`), so a rotation crosses them. 1280×800
   * is the desktop class and 1600×900 the ultra-wide one, where `main` gains its `max-width` — the
   * two classes that were already covered by review and by {@code
   * MissionDatetimeSplitLayoutE2eTest} but never by a whole-page sweep.
   *
   * <p>Ordered narrow to wide so the output reads as a ladder.
   */
  private static final List<int[]> DEVICE_CLASSES =
      List.of(
          new int[] {375, 812},
          new int[] {768, 1024},
          new int[] {1024, 768},
          new int[] {1280, 800},
          new int[] {1600, 900});

  /**
   * Every route the frontend answers with a page, taken from its {@code @GetMapping}s rather than
   * chosen.
   *
   * <p>This is the full list of routes that need no path variable — the detail views are reached
   * from seeded entities instead (see the detail sweep). Entries that turn out not to be pages at
   * all are **skipped at runtime**, not guessed at here: a Basetool page is recognised by its app
   * shell, and a fragment or JSON endpoint simply does not have one. Keeping the non-pages in the
   * list and letting the run classify them is deliberate — it is how a route that quietly stops
   * rendering a shell shows up, instead of being silently absent from a curated list.
   *
   * <p>Only three kinds of route are left out, and none of them by judgement: the {@code /api/**}
   * proxies, the two machine descriptors ({@code assetlinks.json}, {@code manifest.webmanifest}),
   * and {@code /csrf}.
   */
  private static final List<String> PAGES =
      List.of(
          "/",
          "/missions",
          "/missions/new",
          "/operations",
          "/orders",
          "/orders/create",
          "/orders/material-demand",
          "/refinery-orders",
          "/refinery-orders/create",
          "/inventory",
          "/inventory/all",
          "/inventory/my",
          "/inventory/input",
          "/bank",
          "/bank/requests",
          "/bank/grants",
          "/bank/manage",
          "/org-unit-bank",
          "/materialboerse",
          "/materials",
          "/materials/overview",
          "/materials/profit-calculation",
          "/hangar",
          "/hangar/squadron",
          "/ship-data",
          "/blueprint-overview",
          "/personal-inventory",
          "/personal-inventory/blueprints",
          "/notifications",
          "/org-chart",
          "/profile",
          "/promotion/overview",
          "/promotion/my-evaluations",
          "/promotion/manage",
          "/promotion/admin/topics",
          "/promotion/admin/rank-requirements",
          "/admin/audit-log",
          "/admin/bank",
          "/admin/bank-audit",
          "/admin/sync-reports",
          "/admin/sync-reports/uex",
          "/admin/sync-reports/scwiki",
          "/admin/terms",
          // NOT `/admin/p4k-import/jobs` — that is the page's JSON polling endpoint
          // (@ResponseBody List<P4kImportJobDto>), which the first sweep proved by reporting it as
          // having no footer at all. The page itself is this one.
          "/admin/p4k-import",
          "/impressum",
          "/privacy",
          "/terms");

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

    for (int[] device : DEVICE_CLASSES) {
      int width = device[0];
      int height = device[1];
      String deviceLabel = width + "x" + height;

      try (BrowserContext context =
          browser.newContext(
              new Browser.NewContextOptions()
                  .setIgnoreHTTPSErrors(true)
                  .setStorageStatePath(storageState)
                  .setViewportSize(width, height)
                  // Reported as a touch device, because REQ-UI-009's floors are touch-class rules
                  // and some of them sit behind hover/pointer media queries.
                  .setHasTouch(true)
                  .setIsMobile(width <= 768))) {
        Page page = context.newPage();
        for (String path : PAGES) {
          // One page that hangs must not end the audit: the whole point is to name every offender,
          // so a failure to measure is itself a finding and the sweep carries on.
          findings.addAll(measureSafely(page, baseUrl, path, deviceLabel, width, height));

          // Detail views are reached by FOLLOWING A LINK from their list, not by seeding an entity.
          // 33 of the frontend's routes carry a path variable and none of them can be visited by
          // name; seeding one of each would also make this suite destructive, and it is tagged
          // `smoke` precisely so it can run against a shared deployment. Taking the first detail
          // link the list actually renders keeps it read-only and measures whatever data is really
          // there. A list with no rows yields no link and is reported as uncovered rather than
          // silently passing.
          String detail = firstDetailLink(page, path);
          if (detail != null) {
            findings.addAll(measureSafely(page, baseUrl, detail, deviceLabel, width, height));
          }
        }
      }
    }

    assertTrue(
        findings.isEmpty(),
        "Touch-class layout findings (REQ-UI-009). Screenshots: "
            + ARTIFACTS.toAbsolutePath()
            + System.lineSeparator()
            + String.join(System.lineSeparator(), findings));
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
   * @return the page's findings, or a single line naming why it could not be measured
   */
  private static List<String> measureSafely(
      Page page, String baseUrl, String path, String deviceLabel, int width, int height) {
    try {
      return measure(page, baseUrl, path, deviceLabel, width, height);
    } catch (RuntimeException e) {
      String reason =
          e.getClass().getSimpleName()
              + ": "
              + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
      System.out.printf(
          "[touch-layout] %-34s COULD NOT MEASURE — %s%n", deviceLabel + " " + path, reason);
      return List.of(deviceLabel + " " + path + ": could not be measured — " + reason);
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
   * @return one line per finding; empty when the page is clean at this size
   */
  private static List<String> measure(
      Page page, String baseUrl, String path, String deviceLabel, int width, int height) {
    List<String> findings = new ArrayList<>();
    // Re-assert the viewport before every page, and force it to actually take.
    //
    // A full-page capture resizes the viewport to the content size to take its picture. Playwright
    // does not consider that its own viewport change, so a plain `setViewportSize(width, height)`
    // afterwards is a no-op — the size it tracks already equals the one being asked for — and the
    // page stays as wide as the previous screenshot left it. Measured: six pages reported viewports
    // of 401-526px on the 375px class, and every width comparison on them was against that wrong
    // reference. Going through a different height first makes it a real resize.
    page.setViewportSize(width, height + 1);
    page.setViewportSize(width, height);
    E2eSupport.navigate(page, baseUrl + path);
    // NOT `NETWORKIDLE`: this app never goes idle. The notification badge polls, the live-sync
    // WebSocket stays open, and the P4K import page polls its job list — so waiting for a quiet
    // network times out after 30s and takes the whole sweep down with it, which is exactly how the
    // first modal run died 43 measurements in.
    //
    // What the measurement actually needs is a settled LAYOUT: the document parsed, the webfont
    // applied (Lato changes every text box, and the header height is text-driven), and one beat for
    // the ResizeObserver that publishes --krt-footer-height.
    page.waitForFunction(
        "() => document.readyState === 'complete'"
            + " && (!document.fonts || document.fonts.status === 'loaded')");
    page.waitForTimeout(150);

    // Is this a page at all? The route list is taken from the controllers rather than curated, so
    // it contains fragment and JSON endpoints too. A Basetool page is recognised by its app shell;
    // anything without one is skipped and said so, which is also how a page that quietly stops
    // rendering the shell would surface.
    boolean hasAppShell =
        Boolean.TRUE.equals(
            page.evaluate(
                "() => !!document.querySelector('header nav .brand') &&"
                    + " !!document.querySelector('main')"));
    if (!hasAppShell) {
      System.out.printf(
          "[touch-layout] %-34s SKIPPED — no app shell (fragment, redirect or non-HTML); now at"
              + " %s%n",
          deviceLabel + " " + path, page.url().replace(baseUrl, ""));
      return findings;
    }

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
    page.screenshot(
        new Page.ScreenshotOptions()
            .setPath(ARTIFACTS.resolve(deviceLabel + "__" + slug + ".png"))
            .setFullPage(true));

    // Photograph every modal too, on the touch classes.
    //
    // The measurement above already covers them, but a modal is `display: none` in the page
    // screenshot — so an audit that is supposed to be looked at would contain no picture of the 42
    // surfaces where the tool asks for input. Showing one at a time and capturing the viewport
    // gives a reviewer the same evidence for a dialog as for a page, and the touch classes are
    // where a dialog gets tight. The device label belongs in the FILENAME: without it the 768px
    // pass silently overwrote the 375px pictures, and the directory looked complete while holding
    // only the wider half.
    if (width <= PHONE_MAX_WIDTH) {
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
          page.screenshot(
              new Page.ScreenshotOptions()
                  .setPath(
                      ARTIFACTS
                          .resolve("modals")
                          .resolve(deviceLabel + "__" + slug + "__" + id + ".png")));
        }
        page.evaluate(
            "(i) => { const o = document.querySelectorAll('.krt-modal-overlay')[i]; if (!o) return;"
                + " o.style.display = o.dataset.krtPrevDisplay || '';"
                + " delete o.dataset.krtPrevDisplay; }",
            i);
      }
    }

    String where = deviceLabel + " " + path;

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
      // Above the touch classes only the geometry of a modal matters, never its hit areas.
      for (Object offender : list(probe.get("modalIssues"))) {
        if (!offender.toString().contains("px tall, floor")) {
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
        const cutOff = [];
        for (const el of document.body.querySelectorAll('*')) {
          const cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || cs.position === 'fixed') continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          if (r.right > vw + slack && !scrollsHorizontally(el) && cutOff.length < 6) {
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
        for (const c of document.querySelectorAll('input, select, textarea, button, a.btn')) {
          const cs = getComputedStyle(c);
          if (cs.display === 'none' || cs.visibility === 'hidden') continue;
          if (c.type === 'hidden') continue;
          const r = c.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          // `.master-row` joins the dense exemption by owner decision 2026-09-13: the blueprint
          // list rows are a scan-and-tap list where density is the point, and they were ruled
          // equivalent to a repeated row action rather than a standalone control.
          const dense = c.classList.contains('btn-xs') || c.classList.contains('btn-icon')
            || c.classList.contains('master-row');
          const floor = dense ? %d : %d;
          if (r.width > vw + slack && !scrollsHorizontally(c) && badControls.length < 6) {
            badControls.push(label(c) + ' is ' + Math.round(r.width)
              + 'px wide in a ' + vw + 'px viewport');
          } else if (r.height < floor - slack && badControls.length < 6) {
            badControls.push(label(c) + ' is ' + Math.round(r.height)
              + 'px tall, floor ' + floor + 'px');
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
              // `.master-row` joins the dense exemption by owner decision 2026-09-13: the blueprint
          // list rows are a scan-and-tap list where density is the point, and they were ruled
          // equivalent to a repeated row action rather than a standalone control.
          const dense = c.classList.contains('btn-xs') || c.classList.contains('btn-icon')
            || c.classList.contains('master-row');
              const floor = dense ? %d : %d;
              if (cr.height < floor - slack && modalIssues.length < 12) {
                modalIssues.push(name + ' > ' + label(c) + ' is ' + Math.round(cr.height)
                  + 'px tall, floor ' + floor + 'px');
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
        let maxRight = 0;
        let widest = '(none)';
        for (const el of document.body.querySelectorAll('*')) {
          const cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden') continue;
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          if (r.right > maxRight) { maxRight = r.right; widest = label(el); }
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
                 modalCount: document.querySelectorAll('.krt-modal-overlay').length };
      }
      """
          .formatted(
              DENSE_ACTION_FLOOR, TOUCH_TARGET_FLOOR, DENSE_ACTION_FLOOR, TOUCH_TARGET_FLOOR);
}
