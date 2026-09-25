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
 * Layout guard for the touch device classes of REQ-UI-009 (Smartphone and Tablet), plus desktop and
 * ultra-wide, across every page a member works on.
 *
 * <p>Per page and device class it asserts:
 *
 * <ol>
 *   <li>the document does not scroll sideways;
 *   <li>header and pinned footer stay under {@value #MAX_CHROME_SHARE_PERCENT}% of the viewport
 *       height;
 *   <li>the footer is {@code static} with {@code --krt-footer-height: 0px} on phones, and {@code
 *       fixed} with matching {@code main} bottom padding above;
 *   <li>no element is cut off beyond the viewport outside a fitting scroll container;
 *   <li>wide tables scroll inside their own container;
 *   <li>form controls fit the viewport and reach the 44px touch floor by their effective hit area
 *       (dense row actions excepted).
 * </ol>
 *
 * <p>Geometry is measured with bounding rectangles. All findings are collected before failing, and
 * only the Chromium run writes screenshots to {@code build/e2e-artifacts/touch-layout/}. The class
 * runs last ({@code @Order(Integer.MAX_VALUE)}) so the CRUD flows have populated the tables.
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
   * Ceiling for the header's own height on the phone class, in px; the chrome-share ceiling alone
   * does not catch a too-tall header.
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
   * Height floor for the dense row actions REQ-UI-009 exempts from the 44px floor; mirrors {@code
   * --touch-target-dense} in {@code styles.css}.
   */
  private static final int DENSE_ACTION_FLOOR = 32;

  /** Sub-pixel slack, for the same reason the sibling layout guard carries one. */
  private static final double SLACK_PX = 1.0;

  /** The only engine that writes screenshots; every engine still measures and asserts. */
  private static final String SCREENSHOT_ENGINE = "chromium";

  /**
   * Prefix marking a hit-area finding, so hit-area findings can be filtered out above the touch
   * classes. Interpolated into the probe script.
   */
  private static final String HIT_AREA_MARK = "hit-area: ";

  /**
   * A modal shape: the root selector the templates use and the inner parts to measure in it.
   *
   * @param root the overlay element that is hidden and shown
   * @param box the framed dialog whose geometry is asserted
   * @param body the scrolling region, or {@code null} when the shape has none
   * @param foot the button row, or {@code null} when the shape has none
   * @param openClass a class the shape's JS adds to open it, or {@code null} when display alone
   *     opens it
   */
  private record ModalShape(String root, String box, String body, String foot, String openClass) {}

  /** The one shape. New work is written against it; there is no other to choose from. */
  private static final List<ModalShape> MODAL_SHAPES =
      List.of(
          new ModalShape(
              ".krt-modal-overlay", ".krt-modal", ".krt-modal-body", ".krt-modal-foot", null));

  /** The roots of {@link #MODAL_SHAPES} joined into one selector. */
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

  /** The engine this run drives, from the {@code e2e.browser} system property. */
  private static final String ENGINE = System.getProperty("e2e.browser", "chromium");

  /**
   * Whether this engine accepts {@code isMobile}; Firefox does not, so it runs without mobile
   * emulation but with touch and the viewport size.
   */
  private static final boolean ENGINE_SUPPORTS_IS_MOBILE = !"firefox".equals(ENGINE);

  /**
   * Minimum number of {@link FrontendPageRoutes#PAGES} each device class must measure, so a sweep
   * that skipped everything cannot pass.
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
   * Restricts the sweep to one device class ({@code WxH}); unset runs all classes. With a single
   * class, {@link #reportCoverage}'s cross-class comparison is vacuous and only the absolute floor
   * applies.
   */
  private static final String DEVICE_FILTER = System.getProperty("e2e.device", "").trim();

  /**
   * The five device classes of REQ-UI-009, ordered narrow to wide: the phone (375×812), the tablet
   * in both orientations (810×1080, 1024×768), desktop (1280×800) and ultra-wide (1600×900).
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
   * Sweeps every page at every device class, collects every finding, then fails once. Each device
   * class gets its own browser context.
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
   * Asserts that every device class measured the same set of routes and at least {@link
   * #MIN_MEASURED_ROUTES}, adding a finding otherwise.
   *
   * @param measuredByDevice per device class, the {@link FrontendPageRoutes#PAGES} routes measured
   * @param detailsByDevice per device class, the detail views reached from a list; reported only
   * @param uncoveredLists list pages that rendered no row
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
   * Runs {@link #measure}, turning a failure to measure into a finding instead of an abort.
   *
   * @param page the page to navigate
   * @param baseUrl origin of the stack under test
   * @param path app-relative path to measure
   * @param deviceLabel {@code WxH}
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @param measured collects the paths actually measured; skipped or failed paths are absent
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
   * Takes a screenshot on the screenshot engine, reporting instead of throwing when it fails.
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
   * Finds the first detail link {@code <listPath>/<id>} (UUID or numeric id) a list page renders,
   * ignoring {@code /create}, {@code /new} and {@code /search}.
   *
   * @param page the page currently showing the list
   * @param listPath the href prefix of the detail links, possibly from {@link
   *     FrontendPageRoutes#DETAIL_PREFIX_OVERRIDES}
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
   * Prints the page-side measurements, whether the page passed or failed.
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
   * Applies the whole-page geometry rules: page fit, footer behavior and chrome budget.
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
   * Turns the per-element offender lists the probe collected into findings.
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
   * The page-side measurement script, run in one pass; elements are named by a short CSS-like
   * label.
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
