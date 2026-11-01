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
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the favourites, the hidden pages and „Anpassen“ of REQ-UI-032 in a browser: the header
 * star, the Favoriten group and „Mehr“ that follow in place, the cap of twelve, the resets, the
 * touch floor of the edit controls and the version conflict of a second window.
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
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/hidden',
          payload: { keys: [], version: Number(state.dataset.hiddenVersion) },
          toast: false,
        });
      }
      """;

  private static final String FILL_TO_THE_CAP =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        const keys = [
          'hangar.overview', 'storage.overview', 'refinery.orders', 'operations.list',
          'hangar.catalog', 'hangar.unit', 'storage.levels', 'storage.my', 'storage.all',
          'personal.inventory', 'personal.blueprints', 'personal.profile',
        ];
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

  /** Leaves the member without favourites and hidden pages, whatever the previous scenario did. */
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

  private static final String HIDE_MISSIONS =
      "#sidebar [data-trigger='nav-hide'][data-entry-key='missions.list']";

  private static final String UNHIDE_MISSIONS =
      "#sidebar [data-nav-action='unhide'][data-entry-key='missions.list']";

  /** Opens the drawer and turns on „Anpassen“. */
  private static void startEditing(Page page) {
    page.locator("#hamburger").click();
    page.getByTestId("nav-edit-toggle").click();
    assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-editing\\b"));
  }

  /** Types a page name into the quick access and checks whether a row opens the path. */
  private static void assertQuickAccessOffers(
      Page page, String term, String href, boolean offered) {
    page.keyboard().press("Control+k");
    page.getByTestId("palette-input").fill(term);
    assertThat(page.locator("#nav-palette .palette-row[data-href='" + href + "']"))
        .hasCount(offered ? 1 : 0);
    page.keyboard().press("Escape");
    assertThat(page.locator("#nav-palette")).isHidden();
  }

  /**
   * Hiding in „Anpassen“ moves the page into „Mehr“ in place with the focus on its show control,
   * takes the page and its tabs out of the quick access, survives a reload and is undone from
   * „Mehr“.
   */
  @Test
  void hidingMovesAPageIntoMoreAndOutOfTheQuickAccess() {
    run(
        "nav-hide",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate("() => { window.__hideMarker = 'still-here'; }");
          String operations =
              page.locator(".palette-source-pages a[href='/operations']").textContent().trim();
          assertQuickAccessOffers(page, operations, "/operations", true);
          assertThat(page.getByTestId("nav-more")).hasCount(0);
          startEditing(page);

          page.locator(HIDE_MISSIONS).click();

          assertThat(page.getByTestId("nav-missions")).hasCount(0);
          assertThat(page.getByTestId("nav-more")).hasCount(1);
          assertThat(page.getByTestId("nav-more-missions.list")).hasCount(1);
          assertThat(page.locator(UNHIDE_MISSIONS)).isFocused();
          assertThat(page.getByTestId("nav-live")).containsText("Mehr");
          assertEquals("still-here", page.evaluate("() => window.__hideMarker"));
          assertQuickAccessOffers(page, operations, "/operations", false);

          page.reload();
          assertThat(page.getByTestId("nav-more-missions.list")).hasCount(1);
          assertThat(page.getByTestId("nav-missions")).hasCount(0);

          page.locator("#hamburger").click();
          assertThat(page.locator(UNHIDE_MISSIONS)).isVisible();
          page.locator(UNHIDE_MISSIONS).click();

          assertThat(page.getByTestId("nav-missions")).hasCount(1);
          assertThat(page.getByTestId("nav-more")).hasCount(0);
          assertQuickAccessOffers(page, operations, "/operations", true);
        });
  }

  /**
   * Starring a hidden page shows it again and hiding a favourite unstars it, each in one write that
   * checks both versions.
   */
  @Test
  void favouriteAndHiddenExcludeEachOtherInOneWrite() {
    run(
        "nav-hide-favourite",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.getByTestId("nav-star").click();
          assertThat(page.getByTestId("nav-favourite-missions.list")).hasCount(1);
          int favouritesVersion =
              Integer.parseInt(
                  page.locator("[data-favourites-state]").getAttribute("data-version"));
          int hiddenVersion =
              Integer.parseInt(
                  page.locator("[data-favourites-state]").getAttribute("data-hidden-version"));
          startEditing(page);

          page.locator(HIDE_MISSIONS).click();

          assertThat(page.getByTestId("nav-favourites")).hasCount(0);
          assertThat(page.getByTestId("nav-more-missions.list")).hasCount(1);
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-version", String.valueOf(favouritesVersion + 1));
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-hidden-version", String.valueOf(hiddenVersion + 1));

          page.keyboard().press("Escape");
          page.getByTestId("nav-star").click();

          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "true");
          assertThat(page.getByTestId("nav-favourite-missions.list")).hasCount(1);
          assertThat(page.getByTestId("nav-more")).hasCount(0);
          assertThat(page.getByTestId("nav-missions")).hasCount(1);
        });
  }

  /** The two resets empty their section in place and leave the other one alone. */
  @Test
  void theResetsClearHiddenPagesAndFavourites() {
    run(
        "nav-resets",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/hangar");
          page.getByTestId("nav-star").click();
          assertThat(page.getByTestId("nav-favourite-hangar.overview")).hasCount(1);
          startEditing(page);
          page.locator(HIDE_MISSIONS).click();
          assertThat(page.getByTestId("nav-more-missions.list")).hasCount(1);

          page.getByTestId("nav-unhide-all").click();

          assertThat(page.getByTestId("nav-more")).hasCount(0);
          assertThat(page.getByTestId("nav-missions")).hasCount(1);
          assertThat(page.getByTestId("nav-favourite-hangar.overview")).hasCount(1);

          page.getByTestId("nav-clear-favourites").click();

          assertThat(page.getByTestId("nav-favourites")).hasCount(0);
          assertThat(page.getByTestId("nav-star")).hasAttribute("aria-pressed", "false");
        });
  }

  /** Personal pages can be starred in „Anpassen“ but never hidden. */
  @Test
  void personalPagesHaveNoHideControl() {
    run(
        "nav-hide-personal",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          startEditing(page);
          page.locator("#nav-personal").evaluate("el => { el.hidden = false; }");

          assertThat(page.locator("#nav-personal [data-trigger='nav-hide']")).hasCount(0);
          assertThat(page.locator("#nav-personal [data-favourite-toggle]")).not().hasCount(0);
        });
  }

  /** The edit controls are 44 px up to 1024 px and 32 px above. */
  @Test
  void theEditControlsFollowTheTouchFloor() {
    for (int width : new int[] {1024, 1280}) {
      int floor = width <= 1024 ? 44 : 32;
      run(
          width,
          900,
          "nav-edit-size-" + width,
          page -> {
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            startEditing(page);

            for (String selector :
                new String[] {HIDE_MISSIONS, "#sidebar [data-testid='nav-edit-star']"}) {
              BoundingBox box = page.locator(selector).first().boundingBox();
              assertTrue(box.width >= floor && box.height >= floor, selector + " at " + width);
              assertTrue(box.width < 44 || width <= 1024, selector + " stays dense at " + width);
            }
          });
    }
  }

  /**
   * A second window with a stale hidden version has its change dropped: the lists reload with the
   * other window's page under „Mehr“ and the hint says so.
   */
  @Test
  void aStaleWindowDropsItsHideAndSaysSo() {
    try (BrowserContext context = newContext()) {
      Page first = context.newPage();
      Page second = context.newPage();
      E2eSupport.navigate(first, STACK.baseUrl() + "/missions");
      E2eSupport.navigate(second, STACK.baseUrl() + "/hangar");
      startEditing(first);
      startEditing(second);

      first.locator(HIDE_MISSIONS).click();
      assertThat(first.getByTestId("nav-more-missions.list")).hasCount(1);

      second
          .locator("#sidebar [data-trigger='nav-hide'][data-entry-key='hangar.overview']")
          .click();

      assertThat(second.getByText("In einem anderen Fenster")).isVisible();
      assertThat(second.getByTestId("nav-more-missions.list")).hasCount(1);
      assertThat(second.getByTestId("nav-more-hangar.overview")).hasCount(0);
    }
  }

  private static BrowserContext newContext() {
    return newContext(1440, 900);
  }

  private static BrowserContext newContext(int width, int height) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState)
            .setViewportSize(width, height));
  }

  private static void run(String label, Consumer<Page> scenario) {
    run(1440, 900, label, scenario);
  }

  private static void run(int width, int height, String label, Consumer<Page> scenario) {
    try (BrowserContext context = newContext(width, height)) {
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
