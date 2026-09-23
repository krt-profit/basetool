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

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import de.greluc.krt.profit.basetool.testsupport.web.FrontendPageRoutes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The one dialog contract, checked on every dialog the app renders (FE-SIMP-04 / FE-SIMP-04b,
 * REQ-UI-013, ADR-0177).
 *
 * <p>Every {@code .krt-modal-overlay} is rendered by {@code fragments/modal-wrapper.html} ({@code
 * SingleModalShapeTest} fails the build on one that is not), so every dialog should behave the
 * same: {@code window.krtModal.open} shows it modally, focus moves into it, the page behind it is
 * inert, Escape closes it, and so does its ✕. "Should" is what this test turns into "does". Before
 * 2026-09-23 it drove the hangar's add-ship dialog alone, on the argument that every other dialog
 * shares the code path; five order-detail dialogs whose ✕ closed nothing — and therefore whose
 * Escape closed nothing either — were the counter-example.
 *
 * <p>Two tests:
 *
 * <ul>
 *   <li>{@link #aDialogIsModalClosesOnEscapeReturnsFocusAndReopens} drives one dialog through its
 *       real opener, so focus return to that opener is checked end to end.
 *   <li>{@link #everyDialogFollowsTheContract} seeds one of each detail entity, visits every page
 *       route and the seeded detail pages, and runs the contract on every dialog it finds. Its
 *       coverage is asserted, not printed: every {@code modalId} a template declares must either
 *       have been exercised or be listed in {@link #UNREACHED} with the reason a fresh stack cannot
 *       show it.
 * </ul>
 *
 * <p><b>Runs second to last</b> ({@code @Order}, see {@code junit-platform.properties}), just
 * before the touch sweep and for a similar reason: the walk renders every page, and the first
 * render of some of them freezes a 10-minute frontend catalogue cache (the refinery pages fill
 * {@code MATERIALS}). A class that seeds a material after that and expects to pick it in a form —
 * {@code RefineryImportE2eTest}, {@code IngestHandoffE2eTest} — then finds an empty picker. Running
 * after the CRUD flows means the walk can no longer freeze a cache under them.
 */
@Tag("e2e")
@Order(Integer.MAX_VALUE - 1)
class DialogA11yE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Canonical IRIDIUM Squadron id, the org unit the seeded job order is placed with. */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The refinery-hosting location {@code E2eStackExtension} seeds into the catalog. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  /** The dialog under test in the opener flow, and the button that opens it. */
  private static final String DIALOG = "ship-modal";

  private static final String OPENER = "add-ship-btn";

  /**
   * Dialogs a template declares that a freshly seeded stack does not render, each with the reason.
   * An entry here is a known gap in this test's reach, not in the dialog: the shell is still the
   * wrapper's, which {@code SingleModalShapeTest} asserts statically.
   */
  private static final Map<String, String> UNREACHED = Map.of();

  /** Pages whose dialogs render only with a squadron pinned, walked again with IRIDIUM pinned. */
  private static final List<String> SQUADRON_PAGES =
      List.of("/promotion/admin/topics", "/promotion/admin/rank-requirements");

  /** Detail pages reached by the seeded entities' ids; filled in {@link #setUp}. */
  private static final List<String> SEEDED_DETAILS = new ArrayList<>();

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds one entity per detail page that renders dialogs. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Dialog Mission", false);
      SEEDED_DETAILS.add("/missions/" + missionId);
      String operationId = seeder.createOperation(USERNAME, PASSWORD, "E2E Dialog Operation");
      SEEDED_DETAILS.add("/operations/" + operationId);
      String jobMaterial = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Dialog Material");
      String orderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Dialog Order", jobMaterial, 650, 100.0);
      SEEDED_DETAILS.add("/orders/" + orderId);
      // An ITEM order renders two more dialogs (item handover, production record).
      String widget = seeder.seedOrderableItem("E2E Dialog Widget", jobMaterial);
      String itemOrderId =
          seeder.createItemJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E Dialog Item", widget, 2);
      // The item handover dialog renders only once something has been manufactured.
      seeder.manufactureItemOrderLineFully(
          USERNAME,
          PASSWORD,
          itemOrderId,
          seeder.createLocation(USERNAME, PASSWORD, "E2E Dialog Loc " + UUID.randomUUID()));
      SEEDED_DETAILS.add("/orders/" + itemOrderId);
      String specialCommandId =
          seeder.createSpecialCommand(USERNAME, PASSWORD, "E2E Dialog SK", "EDSK");
      SEEDED_DETAILS.add("/organisation/special-commands/" + specialCommandId);
      String orgUnitAccountId =
          seeder.ensureOrgUnitBankAccount(USERNAME, PASSWORD, "E2E Dialog OU Account", IRIDIUM_ID);
      SEEDED_DETAILS.add("/org-unit-bank/accounts/" + orgUnitAccountId);
      // An own PENDING request renders its per-request edit dialog (ou-req-edit-<id>) on the
      // org-unit bank page, which the route list already visits.
      seeder.raiseBankDepositRequest(USERNAME, PASSWORD, orgUnitAccountId, 100);
      String hub = seeder.findLocationIdByName(USERNAME, PASSWORD, REFINERY_HUB);
      String refineryMaterial =
          seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Dialog Refinery Material");
      String refineryOrderId =
          seeder.createRefineryOrder(USERNAME, PASSWORD, hub, refineryMaterial, null, null);
      SEEDED_DETAILS.add("/refinery-orders/" + refineryOrderId);
      String accountId =
          seeder.createBankAccount(USERNAME, PASSWORD, "E2E Dialog Account", "SPECIAL");
      SEEDED_DETAILS.add("/bank/accounts/" + accountId);
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
   * Opens the hangar's add-ship dialog through its real button, checks the modal state, the inert
   * background and the focus containment, closes it with Escape, checks focus came back to the
   * button, and opens it again.
   */
  @Test
  void aDialogIsModalClosesOnEscapeReturnsFocusAndReopens() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar");
        page.locator("#" + OPENER).click();

        assertThat(evaluate(page, "d => d.matches(':modal') && d.open"))
            .as("the dialog is shown with showModal(), in the top layer")
            .isEqualTo(true);
        assertThat(evaluate(page, "d => d.contains(document.activeElement)"))
            .as("focus moved into the dialog")
            .isEqualTo(true);
        assertThat(
                page.evaluate(
                    "() => { const b = document.getElementById('"
                        + OPENER
                        + "'); b.focus(); return document.activeElement === b; }"))
            .as("the page behind the dialog is inert: its controls cannot take focus")
            .isEqualTo(false);

        for (int i = 0; i < 12; i++) {
          page.keyboard().press("Tab");
          assertThat(
                  evaluate(
                      page,
                      "d => d.contains(document.activeElement)"
                          + " || document.activeElement === document.body"
                          + " || document.activeElement === null"))
              .as("Tab #%d never lands on a control behind the dialog", i + 1)
              .isEqualTo(true);
        }

        page.keyboard().press("Escape");
        assertThat(evaluate(page, "d => !d.open && getComputedStyle(d).display === 'none'"))
            .as("Escape closed the dialog, both its modal state and its display")
            .isEqualTo(true);
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id"))
            .as("focus returned to the button that opened the dialog")
            .isEqualTo(OPENER);

        page.locator("#" + OPENER).click();
        assertThat(evaluate(page, "d => d.open && getComputedStyle(d).display !== 'none'"))
            .as("the dialog opens again after an Escape close")
            .isEqualTo(true);
        page.locator("#" + DIALOG + " .krt-modal-close").click();
        assertThat(evaluate(page, "d => !d.open && getComputedStyle(d).display === 'none'"))
            .as("the close control closes it through the same contract")
            .isEqualTo(true);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "dialog-a11y");
        throw failure;
      }
    }
  }

  /**
   * Visits every page route and every seeded detail page, and on each runs the contract on every
   * dialog it renders: opened modally with an {@code <h2>} title, an accessible name and a ✕; focus
   * inside; Escape closes it; reopened, its ✕ closes it. Collects every finding, then fails once,
   * and fails as well when a dialog a template declares was neither exercised nor listed in {@link
   * #UNREACHED}.
   */
  @Test
  void everyDialogFollowsTheContract() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    List<String> findings = new ArrayList<>();
    Set<String> exercised = new TreeSet<>();
    Set<String> visited = new TreeSet<>();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      List<String> paths = new ArrayList<>(FrontendPageRoutes.PAGES);
      paths.addAll(SEEDED_DETAILS);
      for (int i = 0; i < paths.size(); i++) {
        String path = paths.get(i);
        if (!visited.add(path)) {
          continue;
        }
        try {
          E2eSupport.navigate(page, baseUrl + path);
          String detail = firstDetailLink(page, FrontendPageRoutes.detailPrefixOf(path));
          if (detail != null && !visited.contains(detail)) {
            paths.add(detail);
          }
          for (String id : dialogIds(page)) {
            checkDialog(page, baseUrl, path, id, findings);
            exercised.add(id);
          }
        } catch (PlaywrightException failure) {
          findings.add(path + ": could not be checked: " + firstLine(failure.getMessage()));
        }
      }
      // The promotion admin pages render their dialogs only with a squadron pinned; the admin's
      // default all-squadrons view shows a prompt instead (REQ-UI-013). Pin IRIDIUM through the
      // app's own switcher, walk them, and unpin again: the pin lives in the server-side session,
      // which the shared storage state hands to every later class.
      try {
        pinOrgUnit(page, baseUrl, IRIDIUM_ID);
        for (String path : SQUADRON_PAGES) {
          try {
            E2eSupport.navigate(page, baseUrl + path);
            visited.add(path + " (pinned)");
            for (String id : dialogIds(page)) {
              checkDialog(page, baseUrl, path, id, findings);
              exercised.add(id);
            }
          } catch (PlaywrightException failure) {
            findings.add(
                path + " (pinned): could not be checked: " + firstLine(failure.getMessage()));
          }
        }
      } finally {
        pinOrgUnit(page, baseUrl, "");
      }
    }

    List<String> unreached = new ArrayList<>();
    for (String declared : declaredDialogIds()) {
      boolean covered = exercisedAny(exercised, declared) || UNREACHED.containsKey(declared);
      if (!covered) {
        unreached.add(declared);
      }
    }
    System.out.printf(
        "[E2E][dialogs] %d dialogs exercised on %d pages%n", exercised.size(), visited.size());
    assertThat(findings)
        .as("every dialog follows the one dialog contract (REQ-UI-013, ADR-0177)")
        .isEmpty();
    assertThat(unreached)
        .as(
            "every dialog a template declares is exercised here, or listed in UNREACHED with the"
                + " reason a seeded stack cannot show it")
        .isEmpty();
  }

  /**
   * Runs the contract on one dialog of the current page.
   *
   * @param page the page
   * @param baseUrl the frontend origin
   * @param path the app-relative path the page was loaded from, for the finding and a reload
   * @param id the dialog's id
   * @param findings where each broken expectation is recorded
   */
  private static void checkDialog(
      Page page, String baseUrl, String path, String id, List<String> findings) {
    String where = path + " #" + id + ": ";
    Object shape =
        page.evaluate(
            """
            (id) => {
              const d = document.getElementById(id);
              window.__krtDialogProbe = true;
              window.krtModal.open(d);
              const head = d.querySelector(':scope > .krt-modal > .krt-modal-head');
              const h2 = head && head.querySelector(':scope > h2');
              const x = head && head.querySelector(':scope > button.krt-modal-close');
              const labelledBy = d.getAttribute('aria-labelledby');
              const name = labelledBy
                ? (document.getElementById(labelledBy) || {}).textContent
                : d.getAttribute('aria-label');
              const problems = [];
              if (!(d.open && d.matches(':modal'))) problems.push('not shown modally');
              if (!d.contains(document.activeElement)) problems.push('focus did not move into it');
              if (!h2 || !h2.textContent.trim()) problems.push('no <h2> title in its head');
              if (!x || !x.getAttribute('aria-label')) problems.push('no labelled close control');
              if (!name || !name.trim()) problems.push('no accessible name');
              if (x && x.getClientRects().length === 0) problems.push('its close control is not visible');
              return problems.join(', ');
            }
            """,
            id);
    // The probe joins its problems into one string, empty when there are none; anything else
    // (a null from a probe that threw) is itself a finding, so it is not filtered out here.
    String problems = String.valueOf(shape);
    if (!problems.isEmpty()) {
      findings.add(where + problems);
    }
    page.keyboard().press("Escape");
    if (!closedOrLeft(page, id)) {
      findings.add(where + "Escape did not close it");
      forceClose(page, id);
    }
    reloadIfLeft(page, baseUrl, path);
    page.evaluate(
        "(id) => { window.__krtDialogProbe = true;"
            + " window.krtModal.open(document.getElementById(id)); }",
        id);
    try {
      page.locator("#" + id + " > .krt-modal > .krt-modal-head > .krt-modal-close")
          .click(new Locator.ClickOptions().setTimeout(5_000));
      if (!closedOrLeft(page, id)) {
        findings.add(where + "its ✕ did not close it");
        forceClose(page, id);
      }
    } catch (PlaywrightException failure) {
      findings.add(where + "its ✕ could not be clicked: " + firstLine(failure.getMessage()));
      forceClose(page, id);
    }
    reloadIfLeft(page, baseUrl, path);
  }

  /**
   * Whether the dialog closed — or the page navigated away, which some close handlers do on purpose
   * (a server-driven dialog that drops its query parameter) and which also ends it.
   */
  private static boolean closedOrLeft(Page page, String id) {
    page.waitForTimeout(150);
    Object state =
        page.evaluate(
            "(id) => { if (!window.__krtDialogProbe) return true;"
                + " const d = document.getElementById(id);"
                + " return !d || (!d.open && getComputedStyle(d).display === 'none'); }",
            id);
    return Boolean.TRUE.equals(state);
  }

  /**
   * Pins the session's active org unit through the sidebar switcher's form, the way a user does,
   * and waits for the POST to be answered. An empty id returns to the all-org-units view.
   *
   * @param page the page
   * @param baseUrl the frontend origin
   * @param orgUnitId the org unit to pin, or {@code ""} to unpin
   */
  private static void pinOrgUnit(Page page, String baseUrl, String orgUnitId) {
    E2eSupport.navigate(page, baseUrl + "/");
    page.waitForResponse(
        response -> response.url().contains("/me/active-org-unit"),
        () ->
            page.evaluate(
                "(id) => { const s = document.getElementById('squadron-switcher-select'); s.value ="
                    + " id; document.getElementById('squadron-switcher-form').submit(); }",
                orgUnitId));
    page.waitForLoadState();
  }

  /** Closes a dialog that failed to close itself, so the next check starts from a clean page. */
  private static void forceClose(Page page, String id) {
    page.evaluate(
        "(id) => { const d = document.getElementById(id); if (d) window.krtModal.close(d); }", id);
  }

  /** Reloads the page when a close handler navigated away from it. */
  private static void reloadIfLeft(Page page, String baseUrl, String path) {
    if (!Boolean.TRUE.equals(page.evaluate("() => window.__krtDialogProbe === true"))) {
      E2eSupport.navigate(page, baseUrl + path);
    }
  }

  /** The ids of every dialog the current page renders. */
  private static List<String> dialogIds(Page page) {
    Object ids =
        page.evaluate(
            "() => Array.from(document.querySelectorAll('dialog.krt-modal-overlay[id]'))"
                + ".map(d => d.id)");
    return ids instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  /**
   * The first detail link the current list page renders, as {@code TouchClassLayoutE2eTest} finds
   * it: an href under the list's prefix whose last segment is an id.
   */
  private static String firstDetailLink(Page page, String listPath) {
    if ("/".equals(listPath)) {
      return null;
    }
    Object href =
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
    return href instanceof String s ? s : null;
  }

  /**
   * Every dialog id a template declares: the {@code modalId} of each call of the wrapper, read from
   * this checkout's templates. An id built as {@code 'literal-' + ${…}} is returned as {@code
   * literal-*}, a prefix. A fragment that takes its id as a parameter (the bank movement dialog)
   * declares no id of its own — its callers pass one, and that id is what the walk exercises — so a
   * bare expression is skipped.
   */
  private static Set<String> declaredDialogIds() {
    Path templates =
        repoRoot().resolve("frontend/src/main/resources/templates").toAbsolutePath().normalize();
    Pattern modalId = Pattern.compile("modal-wrapper :: modal\\(modalId='([^']*)'(\\s*\\+)?");
    Set<String> ids = new TreeSet<>();
    try (Stream<Path> tree = Files.walk(templates)) {
      for (Path template : tree.filter(p -> p.toString().endsWith(".html")).toList()) {
        Matcher m = modalId.matcher(Files.readString(template, StandardCharsets.UTF_8));
        while (m.find()) {
          ids.add(m.group(2) == null ? m.group(1) : m.group(1) + "*");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ids;
  }

  /**
   * Whether a declared id was exercised: exactly, or — for a prefix id ending in {@code *} — by any
   * dialog whose id starts with the prefix.
   */
  private static boolean exercisedAny(Set<String> exercised, String declared) {
    if (declared.endsWith("*")) {
      String prefix = declared.substring(0, declared.length() - 1);
      return exercised.stream().anyMatch(id -> id.startsWith(prefix));
    }
    return exercised.contains(declared);
  }

  /** The repository root: the nearest ancestor of the working directory with docker-compose.yml. */
  private static Path repoRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("docker-compose.yml"))) {
        return p;
      }
    }
    throw new IllegalStateException("repository root not found");
  }

  /** The first line of a Playwright message, which is the useful one. */
  private static String firstLine(String message) {
    return message == null ? "" : message.lines().findFirst().orElse("");
  }

  /**
   * Evaluates a predicate against the dialog under test of the opener flow.
   *
   * @param page the page
   * @param predicate a JavaScript arrow function taking the dialog element
   * @return the predicate's result
   */
  private static Object evaluate(Page page, String predicate) {
    return page.evaluate("(" + predicate + ")(document.getElementById('" + DIALOG + "'))");
  }
}
