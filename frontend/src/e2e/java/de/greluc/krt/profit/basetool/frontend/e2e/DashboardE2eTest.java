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
import com.microsoft.playwright.options.BoundingBox;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the home dashboard of REQ-UI-034 in a browser: the member default, the edit mode (up/down,
 * column switch, remove, catalogue, options, restore), the cap of twelve, the conflict of a second
 * window, the single column on a phone, the ten-minute poll on visibility, the one-time hint and
 * the administrator's inline editor for the information.
 */
@Tag("e2e")
class DashboardE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-member");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-member-pw");

  private static final String WIDE = "[data-dashboard-column='WIDE'] [data-dashboard-item]";
  private static final String NARROW = "[data-dashboard-column='NARROW'] [data-dashboard-item]";

  private static final String RESET_DASHBOARD =
      """
      async () => {
        const board = document.getElementById('dashboard-board');
        await window.krtFetch.write({
          method: 'DELETE',
          url: '/dashboard/member?version=' + board.dataset.version,
          toast: false,
        });
        const state = document.querySelector('[data-favourites-state]');
        if (state) {
          await window.krtFetch.write({
            method: 'PUT',
            url: '/navigation/favourites',
            payload: { keys: [], version: Number(state.dataset.version) },
            toast: false,
          });
        }
      }
      """;

  private static final String FILL_TO_THE_CAP =
      """
      async () => {
        const board = document.getElementById('dashboard-board');
        const widgets = Array.from({ length: 12 }, () => ({
          id: null,
          type: 'INFORMATION',
          column: 'WIDE',
        }));
        await window.krtFetch.write({
          method: 'PUT',
          url: '/dashboard/member/layout',
          payload: { widgets, version: Number(board.dataset.version) },
          toast: false,
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path memberState;
  private static Path adminState;

  /** Launches the browser and captures the member and the administrator session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    memberState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    adminState =
        E2eSupport.authenticatedStorageState(
            browser, STACK.baseUrl(), "test-admin", "test-admin-pw");
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

  /** Leaves the member with the default dashboard and without favourites. */
  @BeforeEach
  void restoreTheDefault() {
    run(
        memberState,
        1440,
        "dashboard-restore",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.evaluate(RESET_DASHBOARD);
        });
  }

  /** The default puts two widgets in each column and every widget loads its own body. */
  @Test
  void theMemberDefaultFillsBothColumns() {
    run(
        memberState,
        1440,
        "dashboard-default",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");

          assertThat(page.locator(WIDE)).hasCount(2);
          assertThat(page.locator(NARROW)).hasCount(2);
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "UPCOMING_DAYS");
          assertThat(page.locator(WIDE).last()).hasAttribute("data-widget-type", "INFORMATION");
          assertThat(page.locator(NARROW).first()).hasAttribute("data-widget-type", "FAVOURITES");
          assertThat(page.locator(NARROW).last()).hasAttribute("data-widget-type", "NOTIFICATIONS");
          assertThat(page.locator("[data-widget-content]")).hasCount(4);
          assertThat(page.getByTestId("dashboard-favourites-empty")).isVisible();
        });
  }

  /** Up, column switch and remove change the layout in place and survive a reload. */
  @Test
  void theEditModeMovesSwitchesAndRemovesInPlace() {
    run(
        memberState,
        1440,
        "dashboard-edit",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.evaluate("() => { window.__dashboardMarker = 'still-here'; }");
          int version =
              Integer.parseInt(page.locator("#dashboard-board").getAttribute("data-version"));
          assertThat(page.getByTestId("dashboard-widget-information").getByTestId("reorder-up"))
              .hasCount(1);
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-widget-information").getByTestId("reorder-up").click();
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");

          page.getByTestId("dashboard-widget-favourites")
              .getByTestId("dashboard-move-column")
              .click();
          assertThat(page.locator(WIDE)).hasCount(3);
          assertThat(page.locator(NARROW)).hasCount(1);

          page.getByTestId("dashboard-widget-notifications")
              .getByTestId("dashboard-remove")
              .click();
          assertThat(page.locator(NARROW)).hasCount(0);
          assertThat(page.locator("#dashboard-board"))
              .hasAttribute("data-version", String.valueOf(version + 3));
          assertEquals("still-here", page.evaluate("() => window.__dashboardMarker"));

          page.reload();
          assertThat(page.locator(WIDE)).hasCount(3);
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");
          assertThat(page.getByTestId("dashboard-widget-notifications")).hasCount(0);
        });
  }

  /** The catalogue adds a widget a second time and „Standard wiederherstellen" removes it again. */
  @Test
  void theCatalogueAddsAndTheRestoreBringsBackTheDefault() {
    run(
        memberState,
        1440,
        "dashboard-catalogue",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-information").click();

          assertThat(page.getByTestId("dashboard-widget-information")).hasCount(2);
          assertThat(page.locator("[data-widget-content]")).hasCount(5);

          page.getByTestId("dashboard-reset").click();
          page.getByTestId("dashboard-reset-confirm").click();

          assertThat(page.getByTestId("dashboard-widget-information")).hasCount(1);
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "UPCOMING_DAYS");
        });
  }

  /** The options dialog changes the rows of an instance and the widget loads with them. */
  @Test
  void theOptionsDialogChangesTheRows() {
    run(
        memberState,
        1440,
        "dashboard-options",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-widget-upcoming-days")
              .getByTestId("dashboard-options")
              .click();
          page.locator("#dashboard-option-rows").selectOption("10");
          page.getByTestId("dashboard-options-save").click();

          assertThat(page.getByTestId("dashboard-widget-upcoming-days"))
              .hasAttribute("data-rows", "10");
          assertThat(page.getByTestId("dashboard-widget-upcoming-days"))
              .hasAttribute("data-widget-id", java.util.regex.Pattern.compile(".+"));

          page.reload();
          assertThat(page.getByTestId("dashboard-widget-upcoming-days"))
              .hasAttribute("data-rows", "10");
        });
  }

  /**
   * The catalogue adds the next-mission and hangar-readiness widgets; each loads its own body, the
   * next-mission widget offers the rows option and the hangar widget none.
   */
  @Test
  void theSummaryWidgetsLoadTheirBodiesAndOfferTheirOptions() {
    run(
        memberState,
        1440,
        "dashboard-summary-widgets",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.getByTestId("dashboard-customise").click();
          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-next-mission").click();
          assertThat(page.getByTestId("dashboard-widget-next-mission")).hasCount(1);
          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-hangar-readiness").click();

          assertThat(page.getByTestId("dashboard-widget-next-mission")).hasCount(1);
          assertThat(page.getByTestId("dashboard-widget-hangar-readiness")).hasCount(1);
          assertThat(
                  page.getByTestId("dashboard-widget-next-mission")
                      .locator(
                          "[data-testid='home-mission'],"
                              + " [data-testid='dashboard-next-mission-empty']"))
              .isVisible();
          assertThat(
                  page.getByTestId("dashboard-widget-hangar-readiness")
                      .locator(
                          "[data-testid='dashboard-hangar-summary'],"
                              + " [data-testid='dashboard-hangar-empty']"))
              .isVisible();
          assertThat(
                  page.getByTestId("dashboard-widget-hangar-readiness")
                      .getByTestId("dashboard-options"))
              .hasCount(0);

          page.getByTestId("dashboard-widget-next-mission")
              .getByTestId("dashboard-options")
              .click();
          page.locator("#dashboard-option-rows").selectOption("3");
          page.getByTestId("dashboard-options-save").click();

          assertThat(page.getByTestId("dashboard-widget-next-mission"))
              .hasAttribute("data-rows", "3");
          assertThat(
                  page.getByTestId("dashboard-widget-next-mission")
                      .locator(
                          "[data-testid='home-mission'],"
                              + " [data-testid='dashboard-next-mission-empty']")
                      .first())
              .isVisible();
          assertTrue(
              page.getByTestId("dashboard-widget-next-mission")
                      .locator("[data-testid='home-mission']")
                      .count()
                  <= 3,
              "the rows option bounds the entries");

          page.reload();
          assertThat(page.getByTestId("dashboard-widget-next-mission"))
              .hasAttribute("data-rows", "3");
          assertThat(page.getByTestId("dashboard-widget-next-mission")).hasCount(1);
          assertThat(page.getByTestId("dashboard-widget-hangar-readiness")).hasCount(1);
        });
  }

  /**
   * The catalogue adds the work widgets, which are not in the default; each loads its own body, and
   * the status option of "Ich bearbeite" offers only the order statuses.
   */
  @Test
  void theWorkWidgetsLoadTheirBodiesAndOfferTheirOptions() {
    run(
        memberState,
        1440,
        "dashboard-work-widgets",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.getByTestId("dashboard-widget-assigned-orders")).hasCount(0);
          assertThat(page.getByTestId("dashboard-widget-low-stock")).hasCount(0);
          assertThat(page.getByTestId("dashboard-widget-bank")).hasCount(0);

          page.getByTestId("dashboard-customise").click();
          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-assigned-orders").click();
          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-low-stock").click();
          page.getByTestId("dashboard-add").click();
          page.getByTestId("dashboard-add-bank").click();

          assertThat(
                  page.getByTestId("dashboard-widget-assigned-orders")
                      .locator(
                          "[data-testid='dashboard-assigned-order'],"
                              + " [data-testid='dashboard-assigned-orders-empty']"))
              .isVisible();
          assertThat(
                  page.getByTestId("dashboard-widget-low-stock")
                      .locator(
                          "[data-testid='dashboard-low-stock-row'],"
                              + " [data-testid='dashboard-low-stock-empty']"))
              .isVisible();
          assertThat(
                  page.getByTestId("dashboard-widget-bank")
                      .locator(
                          "[data-testid='dashboard-bank-row'],"
                              + " [data-testid='dashboard-bank-empty']"))
              .isVisible();
          assertThat(page.getByTestId("dashboard-widget-bank")).hasAttribute("data-rows", "3");

          page.getByTestId("dashboard-widget-assigned-orders")
              .getByTestId("dashboard-options")
              .click();
          assertThat(page.locator("#dashboard-option-status option:not([hidden])")).hasCount(3);
          page.locator("#dashboard-option-status").selectOption("IN_PROGRESS");
          page.getByTestId("dashboard-options-save").click();

          assertThat(page.getByTestId("dashboard-widget-assigned-orders"))
              .hasAttribute("data-status", "IN_PROGRESS");
          page.reload();
          assertThat(page.getByTestId("dashboard-widget-assigned-orders"))
              .hasAttribute("data-status", "IN_PROGRESS");
          assertThat(page.getByTestId("dashboard-widget-bank")).hasCount(1);
        });
  }

  /**
   * An option change after a reorder is saved without a conflict: the two versions are separate.
   */
  @Test
  void anOptionChangeAfterAReorderDoesNotConflict() {
    run(
        memberState,
        1440,
        "dashboard-options-after-reorder",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-widget-information").getByTestId("reorder-up").click();
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");
          page.getByTestId("dashboard-widget-upcoming-days")
              .getByTestId("dashboard-options")
              .click();
          page.locator("#dashboard-option-rows").selectOption("3");
          page.getByTestId("dashboard-options-save").click();

          assertThat(page.getByTestId("dashboard-widget-upcoming-days"))
              .hasAttribute("data-rows", "3");
          page.reload();
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");
          assertThat(page.getByTestId("dashboard-widget-upcoming-days"))
              .hasAttribute("data-rows", "3");
        });
  }

  /** At twelve instances the catalogue explains why nothing can be added. */
  @Test
  void theCatalogueIsClosedAtTheCap() {
    run(
        memberState,
        1440,
        "dashboard-cap",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.evaluate(FILL_TO_THE_CAP);
          page.reload();
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-add").click();

          assertThat(page.getByTestId("dashboard-catalogue-full")).isVisible();
          assertThat(page.getByTestId("dashboard-add-information")).isDisabled();
        });
  }

  /**
   * A second window holding a stale layout loses its change, says so and shows the stored layout.
   */
  @Test
  void aStaleWindowReloadsTheBoardAndSaysSo() {
    try (BrowserContext context = newContext(memberState, 1440)) {
      Page first = context.newPage();
      Page second = context.newPage();
      E2eSupport.navigate(first, STACK.baseUrl() + "/");
      E2eSupport.navigate(second, STACK.baseUrl() + "/");
      first.getByTestId("dashboard-customise").click();
      second.getByTestId("dashboard-customise").click();

      first.getByTestId("dashboard-widget-information").getByTestId("reorder-up").click();
      assertThat(first.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");

      second.getByTestId("dashboard-widget-notifications").getByTestId("dashboard-remove").click();

      assertThat(second.getByText("In einem anderen Fenster")).isVisible();
      assertThat(second.getByTestId("dashboard-widget-notifications")).hasCount(1);
      assertThat(second.locator(WIDE).first()).hasAttribute("data-widget-type", "INFORMATION");
    }
  }

  /** On a phone the wide column comes first and the narrow one follows below it. */
  @Test
  void aPhoneShowsOneColumn() {
    run(
        memberState,
        375,
        "dashboard-phone",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.locator("[data-widget-content]")).hasCount(4);

          BoundingBox wide = page.locator("[data-dashboard-column='WIDE']").boundingBox();
          BoundingBox narrow = page.locator("[data-dashboard-column='NARROW']").boundingBox();

          assertTrue(narrow.y >= wide.y + wide.height, "the narrow column sits below the wide one");
          assertEquals(wide.x, narrow.x, 1.0, "both columns share the one column's left edge");
        });
  }

  /**
   * Instances without a live channel reload every ten minutes while the tab is visible and not
   * while it is hidden; the live ones do not poll.
   */
  @Test
  void theInstancesWithoutALiveChannelPollEveryTenMinutesWhileVisible() {
    run(
        memberState,
        1440,
        "dashboard-poll",
        page -> {
          page.clock().install();
          AtomicInteger information = new AtomicInteger();
          AtomicInteger upcoming = new AtomicInteger();
          page.onRequest(request -> {
            if (request.url().contains("/dashboard/widgets/information")) {
              information.incrementAndGet();
            }
            if (request.url().contains("/dashboard/widgets/upcoming-days")) {
              upcoming.incrementAndGet();
            }
          });
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.locator("[data-widget-content]")).hasCount(4);
          int infoAtLoad = information.get();
          int upcomingAtLoad = upcoming.get();

          page.clock().runFor(11L * 60 * 1000);
          page.waitForTimeout(500);

          assertEquals(infoAtLoad + 1, information.get(), "information polled once");
          assertEquals(upcomingAtLoad, upcoming.get(), "the live widget does not poll");

          page.evaluate(
              "() => Object.defineProperty(document, 'visibilityState',"
                  + " { get: () => 'hidden', configurable: true })");
          page.clock().runFor(11L * 60 * 1000);
          page.waitForTimeout(500);

          assertEquals(infoAtLoad + 1, information.get(), "a hidden tab does not poll");
        });
  }

  /** The hint shows once, stays dismissed in the browser and goes once the dashboard changes. */
  @Test
  void theHintShowsOnceAndGoesWhenTheDashboardChanges() {
    run(
        memberState,
        1440,
        "dashboard-hint",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.getByTestId("dashboard-hint")).isVisible();

          page.getByTestId("dashboard-hint-dismiss").click();
          assertThat(page.getByTestId("dashboard-hint")).hasCount(0);
          page.reload();
          assertThat(page.getByTestId("dashboard-hint")).hasCount(1);
          assertThat(page.getByTestId("dashboard-hint")).isHidden();
        });
    run(
        memberState,
        1440,
        "dashboard-hint-change",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.getByTestId("dashboard-hint")).isVisible();
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-widget-information").getByTestId("reorder-up").click();

          assertThat(page.getByTestId("dashboard-hint")).hasCount(0);
        });
  }

  /** An administrator edits the information inline and the widget shows the new text. */
  @Test
  void anAdministratorEditsTheInformationInline() {
    run(
        adminState,
        1440,
        "dashboard-info-edit",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          String text = "Dashboard-Test " + System.nanoTime();

          page.getByTestId("dashboard-info-edit").click();
          page.getByTestId("dashboard-info-text").fill(text);
          page.getByTestId("dashboard-info-save").click();

          assertThat(page.getByTestId("home-info")).containsText(text);
          assertThat(page.getByTestId("dashboard-info-edit")).isVisible();
        });
  }

  private static BrowserContext newContext(Path state, int width) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(state)
            .setViewportSize(width, 900));
  }

  private static void run(Path state, int width, String label, Consumer<Page> scenario) {
    try (BrowserContext context = newContext(state, width)) {
      Page page = context.newPage();
      try {
        scenario.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
