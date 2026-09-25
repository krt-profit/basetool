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

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.CheckedNode;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Accessibility smoke check: logs in once and runs the axe-core WCAG 2.0/2.1 A+AA ruleset against
 * each core page, asserting there are no {@code critical}-impact violations. Read-only, so it
 * carries both {@code @Tag("smoke")} (staging smoke run) and {@code @Tag("e2e")} (the nightly /
 * label-gated ephemeral-stack run) — it mutates nothing and is safe in either.
 *
 * <p>axe is injected and executed through Playwright's {@code evaluate}, which runs in an isolated
 * world that bypasses the app's strict Content-Security-Policy, so the scan works even though the
 * page blocks inline scripts.
 *
 * <p>The gate fails on {@code critical} <em>and</em> {@code serious} impacts — the two most severe
 * axe levels. It started {@code critical}-only at introduction; the {@code serious} backlog
 * (icon-only hamburger name, the missions filter date/time inputs, the chrome contrast nits and the
 * missing {@code <html lang>}) was cleared in #441, so the floor was tightened to {@code serious}.
 * Every violation at every impact level is still written to {@code build/e2e/a11y-<page>.txt} and
 * logged, so the remaining {@code moderate} / {@code minor} findings stay visible for triage and
 * can drive a future tightening to {@code moderate}.
 */
@Tag("smoke")
@Tag("e2e")
class AccessibilitySmokeE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** WCAG 2.0 + 2.1, levels A and AA — the conformance target for the app. */
  private static final List<String> WCAG_TAGS = List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa");

  /** The axe impact levels that fail the gate: the two most severe of axe's four. */
  private static final Set<String> GATED_IMPACTS = Set.of("critical", "serious");

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated session reused across all page scans. */
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
   * Loads a core page with the authenticated session, runs the axe WCAG A+AA scan, records every
   * violation, and fails if any has {@code critical} or {@code serious} impact (see {@link
   * #GATED_IMPACTS}).
   *
   * @param path the app-relative path of the core page to scan
   */
  @ParameterizedTest(name = "a11y scan of {0}")
  @FieldSource("de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes#A11Y_SMOKE")
  void corePageHasNoCriticalOrSeriousAccessibilityViolations(String path) {
    String slug = path.equals("/") ? "home" : path.substring(1).replace('/', '-');
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      E2eSupport.navigate(page, STACK.baseUrl() + path);

      AxeResults results = new AxeBuilder(page).withTags(WCAG_TAGS).analyze();
      List<Rule> violations = results.getViolations();
      dump(slug, violations);

      List<Rule> gated =
          violations.stream().filter(rule -> GATED_IMPACTS.contains(rule.getImpact())).toList();
      assertTrue(
          gated.isEmpty(),
          () ->
              "Critical/serious accessibility violation(s) on "
                  + path
                  + ":\n"
                  + gated.stream()
                      .map(AccessibilitySmokeE2eTest::describe)
                      .collect(Collectors.joining()));
    }
  }

  /**
   * Writes a full impact-grouped violation summary to {@code build/e2e/a11y-<slug>.txt} and prints
   * the per-impact counts, so non-critical findings stay visible for triage even when the gate
   * passes. Best-effort: a dump failure never fails the scan.
   *
   * @param slug the page slug used in the artifact filename
   * @param violations all axe violations found on the page
   */
  private static void dump(String slug, List<Rule> violations) {
    long crit = countImpact(violations, "critical");
    long serious = countImpact(violations, "serious");
    long moderate = countImpact(violations, "moderate");
    long minor = countImpact(violations, "minor");
    System.out.printf(
        Locale.ROOT,
        "[E2E][a11y] %s: %d violations (critical=%d serious=%d moderate=%d minor=%d)%n",
        slug,
        violations.size(),
        crit,
        serious,
        moderate,
        minor);
    try {
      Path dir = Path.of("build", "e2e");
      Files.createDirectories(dir);
      String body =
          violations.isEmpty()
              ? "No WCAG A/AA violations.\n"
              : violations.stream()
                  .map(AccessibilitySmokeE2eTest::describe)
                  .collect(Collectors.joining());
      Files.writeString(dir.resolve("a11y-" + slug + ".txt"), body);
    } catch (RuntimeException | java.io.IOException e) {
      System.out.println("[E2E][a11y] dump failed for " + slug + ": " + e);
    }
  }

  /**
   * Counts violations of a given axe impact level.
   *
   * @param violations the violations to scan
   * @param impact the axe impact level ({@code critical} / {@code serious} / {@code moderate} /
   *     {@code minor})
   * @return how many violations carry that impact
   */
  private static long countImpact(List<Rule> violations, String impact) {
    return violations.stream().filter(rule -> impact.equals(rule.getImpact())).count();
  }

  /**
   * Renders one violation as a human-readable block: impact, rule id, help text, and for every
   * matching DOM node its selector, its markup (cut to 200 characters) and axe's failure summary —
   * for {@code color-contrast} that summary carries the measured colours and ratio.
   *
   * <p>Until 2026-09-25 only the node count was printed, so a finding that depended on rows other
   * test classes had created could not be traced to an element from the report.
   *
   * @param rule the axe violation
   * @return a multi-line description
   */
  private static String describe(Rule rule) {
    StringBuilder out =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "  [%s] %s — %s (%d node(s))%n",
                rule.getImpact(),
                rule.getId(),
                rule.getHelp(),
                rule.getNodes() == null ? 0 : rule.getNodes().size()));
    if (rule.getNodes() != null) {
      for (CheckedNode node : rule.getNodes()) {
        String html = node.getHtml() == null ? "" : node.getHtml().replaceAll("\\s+", " ");
        out.append(
            String.format(
                Locale.ROOT,
                "      at %s%n        %s%n        %s%n",
                node.getTarget(),
                html.length() > 200 ? html.substring(0, 200) + "…" : html,
                String.valueOf(node.getFailureSummary()).replaceAll("\\s+", " ")));
      }
    }
    return out.toString();
  }
}
