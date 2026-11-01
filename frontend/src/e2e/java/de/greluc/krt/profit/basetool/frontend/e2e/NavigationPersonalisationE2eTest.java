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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the favourites of REQ-UI-032 in a browser: the header star, the Favoriten group that
 * follows it in place, the cap of twelve and the version conflict of a second window.
 */
@Tag("e2e")
class NavigationPersonalisationE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-member");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-member-pw");

  private static final String CLEAR_FAVOURITES =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/favourites',
          payload: { keys: [], version: Number(state.dataset.version) },
          toast: false,
        });
      }
      """;

  private static final String FILL_TO_THE_CAP =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        const keys = Array.from({ length: 12 }, (_, i) => 'filler.page' + i);
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/favourites',
          payload: { keys, version: Number(state.dataset.version) },
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

  /** Leaves the member without favourites, whatever the previous scenario did. */
  @BeforeEach
  void clearFavourites() {
    run(
        "nav-favourites-clear",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(CLEAR_FAVOURITES);
        });
  }

  /**
   * The star adds the page to a Favoriten group in the open menu without reloading the page, the
   * group survives a reload, and the star removes it again.
   */
  @Test
  void theStarFillsAndEmptiesTheFavouritesInPlace() {
    run(
        "nav-favourites-star",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate("() => { window.__favouritesMarker = 'still-here'; }");
          assertThat(page.getByTestId("nav-favourites")).hasCount(0);
          int version =
              Integer.parseInt(
                  page.locator("[data-favourites-state]").getAttribute("data-version"));

          page.getByTestId("nav-star").click();

          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "true");
          assertThat(page.getByTestId("nav-favourite-missions.list")).hasCount(1);
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-version", String.valueOf(version + 1));
          assertEquals("still-here", page.evaluate("() => window.__favouritesMarker"));

          page.reload();
          assertThat(page.getByTestId("nav-favourite-missions.list")).hasCount(1);
          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "true");

          page.getByTestId("nav-star").click();

          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "false");
          assertThat(page.getByTestId("nav-favourites")).hasCount(0);
        });
  }

  /** At the cap of twelve the star of a page that is no favourite is disabled. */
  @Test
  void theStarIsDisabledAtTheCap() {
    run(
        "nav-favourites-cap",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(FILL_TO_THE_CAP);

          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");

          assertThat(page.getByTestId("nav-star")).isDisabled();
          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "false");
        });
  }

  /**
   * A second window that holds a stale version has its change dropped: only the favourites reload,
   * with the other window's favourite, and the hint says so.
   */
  @Test
  void aStaleWindowReloadsOnlyTheFavouritesAndSaysSo() {
    try (BrowserContext context = newContext()) {
      Page first = context.newPage();
      Page second = context.newPage();
      E2eSupport.navigate(first, STACK.baseUrl() + "/missions");
      E2eSupport.navigate(second, STACK.baseUrl() + "/hangar");

      first.getByTestId("nav-star").click();
      assertThat(first.getByTestId("nav-favourite-missions.list")).hasCount(1);

      second.getByTestId("nav-star").click();

      assertThat(second.getByText("In einem anderen Fenster")).isVisible();
      assertThat(second.getByTestId("nav-favourite-missions.list")).hasCount(1);
      assertThat(second.getByTestId("nav-favourite-hangar.overview")).hasCount(0);
      assertThat(second.getByTestId("nav-star")).hasAttribute("aria-pressed", "false");
    }
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
