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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * CRUD flows of the inline org-chart editor ({@code /org-chart}) as {@code test-admin}
 * (REQ-ORG-010, REQ-ORG-012, REQ-ORG-025):
 *
 * <ol>
 *   <li>{@link #createsRenamesAndDeletesAKommando()} — create, rename and remove a leaderless
 *       Kommando;
 *   <li>{@link #createsAssignsVacatesAndRemovesACommandLeader()} — assign, reassign and vacate a
 *       Kommandoleiter, then remove the group.
 * </ol>
 *
 * <p>Each test first deletes existing IRIDIUM Kommandos so the per-Staffel cap is never hit.
 * Mutates data, so it runs only against the ephemeral stack.
 */
@Tag("e2e")
class OrgChartPositionCrudE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The always-present "Kommandogruppe hinzufügen" add affordance on the first Staffel. */
  private static final String ADD_COMMAND_BUTTON =
      "[data-trigger='oc-add'][data-position-type='COMMAND_LEAD'][data-needs-name='true']";

  /** The "Kommandoleiter zuweisen" add affordance shown under a leaderless Kommando. */
  private static final String ASSIGN_LEAD_BUTTON = ".oc-add[data-trigger='oc-reassign']";

  /** The reassign (pencil) control on a Kommando that already has a Kommandoleiter. */
  private static final String REASSIGN_LEAD_ICON = ".oc-icon-btn[data-trigger='oc-reassign']";

  /** The vacate (clear-holder) control; present only while a Kommandoleiter is assigned. */
  private static final String VACATE_LEAD_BUTTON = "[data-trigger='oc-vacate']";

  /** The remove control on a Kommando group header. */
  private static final String COMMAND_REMOVE_BUTTON = ".oc-command-head [data-trigger='oc-remove']";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated ADMIN session reused across the tests. */
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
   * Creates a leaderless Kommando(gruppe), renames it, then removes it — the create / edit / delete
   * lifecycle of a position that needs no holder. Asserts the group header reflects each step.
   */
  @Test
  void createsRenamesAndDeletesAKommando() {
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/org-chart");
        clearAllKommandos(page);

        createLeaderlessKommando(page, "E2E CRUD Alpha");
        enterEditMode(page);
        assertThat(commandHeadNamed(page, "E2E CRUD Alpha")).isVisible();

        page.locator(".oc-command-head [data-trigger='oc-rename']").first().click();
        assertThat(page.locator("#oc-modal")).isVisible();
        page.locator("#oc-name").fill("E2E CRUD Bravo");
        submitModalAndAwaitRefresh(page);
        enterEditMode(page);
        assertThat(commandHeadNamed(page, "E2E CRUD Bravo")).isVisible();
        assertThat(commandHeadNamed(page, "E2E CRUD Alpha")).hasCount(0);

        confirmAndAwaitRefresh(page, page.locator(COMMAND_REMOVE_BUTTON).first());
        assertThat(commandHeadNamed(page, "E2E CRUD Bravo")).hasCount(0);
        assertThat(page.locator(".oc-command-head")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-chart-crud-kommando");
        throw failure;
      }
    }
  }

  /**
   * Creates a Kommando, assigns then reassigns a free-text Kommandoleiter, vacates the seat and
   * asserts the group survives (REQ-ORG-025), then removes the group.
   */
  @Test
  void createsAssignsVacatesAndRemovesACommandLeader() {
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/org-chart");
        clearAllKommandos(page);

        createLeaderlessKommando(page, "E2E CRUD Lead");
        enterEditMode(page);
        assertThat(page.locator(ASSIGN_LEAD_BUTTON)).isVisible();
        assertThat(page.locator(VACATE_LEAD_BUTTON)).hasCount(0);

        page.locator(ASSIGN_LEAD_BUTTON).first().click();
        assertThat(page.locator("#oc-modal")).isVisible();
        page.locator("#oc-display-name").fill("E2E Leiter Eins");
        submitModalAndAwaitRefresh(page);
        enterEditMode(page);
        assertThat(page.locator(VACATE_LEAD_BUTTON)).isVisible();
        assertThat(page.locator(REASSIGN_LEAD_ICON).first())
            .hasAttribute("data-display-name", "E2E Leiter Eins");

        page.locator(REASSIGN_LEAD_ICON).first().click();
        assertThat(page.locator("#oc-modal")).isVisible();
        page.locator("#oc-display-name").fill("E2E Leiter Zwei");
        submitModalAndAwaitRefresh(page);
        enterEditMode(page);
        assertThat(page.locator(REASSIGN_LEAD_ICON).first())
            .hasAttribute("data-display-name", "E2E Leiter Zwei");

        confirmAndAwaitRefresh(page, page.locator(VACATE_LEAD_BUTTON).first());
        enterEditMode(page);
        assertThat(commandHeadNamed(page, "E2E CRUD Lead")).isVisible();
        assertThat(page.locator(VACATE_LEAD_BUTTON)).hasCount(0);
        assertThat(page.locator(ASSIGN_LEAD_BUTTON)).isVisible();

        confirmAndAwaitRefresh(page, page.locator(COMMAND_REMOVE_BUTTON).first());
        assertThat(commandHeadNamed(page, "E2E CRUD Lead")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-chart-crud-leader");
        throw failure;
      }
    }
  }

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused ADMIN storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Turns the inline editor on, idempotently, so the add and per-node controls become visible.
   *
   * @param page the page showing the org chart
   */
  private static void enterEditMode(Page page) {
    Locator toggle = page.locator("[data-trigger='oc-toggle-edit']");
    toggle.waitFor();
    if (!"true".equals(toggle.getAttribute("aria-pressed"))) {
      toggle.click();
    }
    assertThat(page.locator("#oc-chart.editing")).hasCount(1);
  }

  /**
   * Removes every existing Kommando on the first Staffel so a subsequent create cannot trip the
   * per-Staffel cap. A sibling test may leave Kommandos behind; this resets the chart to a known,
   * Kommando-free baseline. Bounded well above the cap of four as a runaway guard.
   *
   * @param page the page showing the org chart
   */
  private static void clearAllKommandos(Page page) {
    for (int i = 0; i < 8; i++) {
      enterEditMode(page);
      Locator removes = page.locator(COMMAND_REMOVE_BUTTON);
      if (removes.count() == 0) {
        return;
      }
      confirmAndAwaitRefresh(page, removes.first());
    }
  }

  /**
   * Creates a single leaderless Kommando(gruppe) with the given name on the first Staffel through
   * the inline editor and blocks until the post-save in-place chart refresh has settled. The holder
   * is left empty (a {@code COMMAND_LEAD} is the one rank that may be created vacant).
   *
   * @param page the page showing the org chart
   * @param name the Kommando name to enter
   */
  private static void createLeaderlessKommando(Page page, String name) {
    enterEditMode(page);
    page.locator(ADD_COMMAND_BUTTON).first().click();
    assertThat(page.locator("#oc-modal")).isVisible();
    page.locator("#oc-name").fill(name);
    submitModalAndAwaitRefresh(page);
  }

  /**
   * Clicks the editor dialog's submit button and blocks until the resulting in-place chart refresh
   * has settled (see {@link #awaitChartRefresh(Page, Runnable)}).
   *
   * @param page the page whose dialog to submit
   */
  private static void submitModalAndAwaitRefresh(Page page) {
    Locator submit = page.locator("#oc-modal [data-trigger='oc-modal-submit']");
    awaitChartRefresh(page, submit::click);
  }

  /**
   * Clicks a control that raises the KRT confirmation dialog (remove / vacate), accepts it, and
   * blocks until the resulting in-place chart refresh has settled. The confirm overlay is created
   * synchronously by the trigger's click handler, so its OK button is available immediately after.
   *
   * @param page the page showing the org chart
   * @param trigger the control that opens the confirm dialog
   */
  private static void confirmAndAwaitRefresh(Page page, Locator trigger) {
    awaitChartRefresh(
        page,
        () -> {
          trigger.click();
          page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
        });
  }

  /**
   * Runs an action that saves and refreshes the chart in place, and waits for the {@code
   * krt:swapped} event on {@code #oc-chart} (REQ-FE-005).
   *
   * @param page the page that hosts the chart
   * @param action the action that starts the save
   */
  private static void awaitChartRefresh(Page page, Runnable action) {
    page.evaluate(
        "() => {"
            + "  window.__ocSwapped = false;"
            + "  const chart = document.getElementById('oc-chart');"
            + "  document.addEventListener('krt:swapped', function onSwap(e) {"
            + "    if (e && e.detail && e.detail.container === chart) {"
            + "      window.__ocSwapped = true;"
            + "      document.removeEventListener('krt:swapped', onSwap);"
            + "    }"
            + "  });"
            + "}");
    action.run();
    page.waitForFunction(
        "() => window.__ocSwapped === true",
        null,
        new Page.WaitForFunctionOptions().setTimeout(30_000));
  }

  /**
   * Locates a Kommando group header carrying the given name.
   *
   * @param page the page showing the org chart
   * @param name the Kommando name to match
   * @return a locator for the matching {@code .oc-command-name} element(s)
   */
  private static Locator commandHeadNamed(Page page, String name) {
    return page.locator(".oc-command-name", new Page.LocatorOptions().setHasText(name));
  }
}
