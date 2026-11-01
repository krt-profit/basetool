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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the start page (REQ-UI-034) and the full reset (REQ-UI-032) in the browser: the header
 * button sets and clears the start page in place, {@code /start} follows it while the dashboard
 * stays reachable, the profile select chooses it too, and the full reset clears every section, both
 * dashboards and both start pages in place.
 */
@Tag("e2e")
class NavigationStartPageE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-member");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-member-pw");

  private static final String RESET_ALL =
      """
      async () => {
        await window.krtFetch.write({ method: 'POST', url: '/navigation/reset', toast: false });
      }
      """;

  private static final String PERSONALISE_EVERY_SECTION =
      """
      async () => {
        const write = (method, url, payload) =>
          window.krtFetch.write({ method, url, payload, toast: false });
        const state = document.querySelector('[data-favourites-state]');
        const appearance = document.querySelector('[data-appearance-state]');
        await write('PUT', '/navigation/favourites',
          { keys: ['missions.list'], version: Number(state.dataset.version) });
        await write('PUT', '/navigation/hidden',
          { keys: ['orders.list'], version: Number(state.dataset.hiddenVersion) });
        await write('PUT', '/navigation/order/member/groups',
          { keys: ['logistics', 'operations'], version: Number(state.dataset.orderMemberVersion) });
        await write('PUT', '/navigation/tab-bar/member',
          { slots: ['hangar.overview', null, null], version: Number(state.dataset.tabbarMemberVersion) });
        await write('PUT', '/navigation/appearance',
          { desktopMode: 'RAIL', shortcutsEnabled: false, version: Number(appearance.dataset.version) });
        await write('PUT', '/navigation/appearance/start-page/member',
          { key: 'missions.list', version: Number(appearance.dataset.version) + 1 });
      }
      """;

  private static final String CUSTOMISE_DASHBOARD =
      """
      async () => {
        const board = document.getElementById('dashboard-board');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/dashboard/member/layout',
          payload: {
            widgets: [{ id: null, type: 'INFORMATION', column: 'WIDE' }],
            version: Number(board.dataset.version),
          },
          toast: false,
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated member session. */
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

  /** Leaves the member with the default preferences, whatever the previous scenario did. */
  @BeforeEach
  void resetEverything() {
    run(
        "nav-start-page-clear",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(RESET_ALL);
        });
  }

  /**
   * The header button sets the start page in place and {@code /start} follows it, while the
   * dashboard at {@code /} stays reachable; clearing sends {@code /start} back to the dashboard.
   */
  @Test
  void theHeaderButtonSetsAndClearsTheStartPageInPlace() {
    run(
        "nav-start-page-header",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate("() => { window.__startMarker = 'still-here'; }");
          assertThat(page.getByTestId("nav-start-page")).hasAttribute("aria-pressed", "false");

          page.getByTestId("nav-start-page").click();

          assertThat(page.getByTestId("nav-start-page")).hasAttribute("aria-pressed", "true");
          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-start-member", "missions.list");
          assertEquals("still-here", page.evaluate("() => window.__startMarker"));

          E2eSupport.navigate(page, STACK.baseUrl() + "/start");
          assertThat(page).hasURL(Pattern.compile("/missions$"));

          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page).hasURL(Pattern.compile("^[^?]*/$"));
          assertThat(page.locator("#dashboard-board")).isVisible();

          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.getByTestId("nav-start-page").click();
          assertThat(page.getByTestId("nav-start-page")).hasAttribute("aria-pressed", "false");

          E2eSupport.navigate(page, STACK.baseUrl() + "/start");
          assertThat(page.locator("#dashboard-board")).isVisible();
        });
  }

  /** The select of „Darstellung“ chooses the start page too, and the header button follows. */
  @Test
  void theProfileSelectChoosesTheStartPage() {
    run(
        "nav-start-page-profile",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");

          page.getByTestId("start-page-select-member").selectOption("hangar.overview");

          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-start-member", "hangar.overview");

          E2eSupport.navigate(page, STACK.baseUrl() + "/start");
          assertThat(page).hasURL(Pattern.compile("/hangar$"));
        });
  }

  /**
   * A second window that holds a stale appearance version has its start page dropped, says so and
   * shows the other window's choice.
   */
  @Test
  void aStaleWindowDropsItsStartPageAndSaysSo() {
    try (BrowserContext context = newContext()) {
      Page first = context.newPage();
      Page second = context.newPage();
      E2eSupport.navigate(first, STACK.baseUrl() + "/missions");
      E2eSupport.navigate(second, STACK.baseUrl() + "/hangar");

      first.getByTestId("nav-start-page").click();
      assertThat(first.getByTestId("nav-start-page")).hasAttribute("aria-pressed", "true");

      second.getByTestId("nav-start-page").click();

      assertThat(second.getByText("In einem anderen Fenster")).isVisible();
      assertThat(second.getByTestId("nav-start-page")).hasAttribute("aria-pressed", "false");
      assertThat(second.locator("[data-appearance-state]"))
          .hasAttribute("data-start-member", "missions.list");
    }
  }

  /**
   * The full reset clears favourites, hidden pages, order, tab bar, the display settings with both
   * start pages and the dashboard, in place and only after the confirmation.
   */
  @Test
  void theFullResetClearsEverySectionInPlace() {
    run(
        "nav-full-reset",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          page.evaluate(CUSTOMISE_DASHBOARD);
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          page.evaluate(PERSONALISE_EVERY_SECTION);
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          assertThat(page.locator("body")).hasAttribute("data-nav-mode", "rail");
          page.evaluate("() => { window.__resetMarker = 'still-here'; }");

          page.getByTestId("navigation-reset-open").click();
          page.locator(
                  "#navigation-reset-modal .krt-modal-foot [data-trigger='close-modal-display']")
              .click();
          assertThat(page.locator("body")).hasAttribute("data-nav-mode", "rail");

          page.getByTestId("navigation-reset-open").click();
          page.getByTestId("navigation-reset-confirm").click();

          assertThat(page.locator("body")).hasAttribute("data-nav-mode", "drawer");
          assertThat(page.locator("[data-appearance-state]")).hasAttribute("data-start-member", "");
          assertThat(page.locator("[data-favourites-state]")).hasAttribute("data-keys", "");
          assertThat(page.locator("[data-favourites-state]")).hasAttribute("data-hidden-keys", "");
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-tabbar-member-stored", "- - -");
          assertThat(page.getByTestId("start-page-select-member")).hasValue("");
          assertEquals("still-here", page.evaluate("() => window.__resetMarker"));

          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          assertThat(page.locator("#dashboard-board")).hasAttribute("data-customised", "false");
          E2eSupport.navigate(page, STACK.baseUrl() + "/start");
          assertThat(page.locator("#dashboard-board")).isVisible();
        });
  }

  private static BrowserContext newContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState)
            .setViewportSize(1440, 900));
  }

  private static void run(String label, Consumer<Page> scenario) {
    try (BrowserContext context = newContext()) {
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
