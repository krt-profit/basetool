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
import com.microsoft.playwright.Locator;
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
 * Drives the configurable phone tab bar of REQ-UI-032 at 375 × 812: the automatic fill from the
 * favourites that the first edit freezes, the swap when a taken entry is chosen, the neighbour
 * swap, the long-press picker, the reset, the touch floor and the administration bar.
 */
@Tag("e2e")
class NavigationTabBarE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MEMBER = "test-member";
  private static final String ADMIN = "test-admin";
  private static final int PHONE_WIDTH = 375;
  private static final int PHONE_HEIGHT = 812;
  private static final double TOUCH_FLOOR = 44;
  private static final double LONG_PRESS_WAIT_MS = 700;

  private static final String RESET_PREFERENCES =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/tab-bar/member',
          payload: { slots: [null, null, null], version: Number(state.dataset.tabbarMemberVersion) },
          toast: false,
        });
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/favourites',
          payload: { keys: [], version: Number(state.dataset.version) },
          toast: false,
        });
      }
      """;

  private static final String STAR_TWO_PAGES =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/favourites',
          payload: {
            keys: ['refinery.orders', 'materials.list'],
            version: Number(state.dataset.version),
          },
          toast: false,
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path memberState;
  private static Path adminState;

  /** Launches the browser and captures a member and an administrator session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    memberState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER + "-pw");
    adminState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN, ADMIN + "-pw");
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

  /** Returns the member's bar to automatic and drops the favourites. */
  @BeforeEach
  void resetPreferences() {
    run(
        memberState,
        "tabbar-reset",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(RESET_PREFERENCES);
        });
  }

  /**
   * The bar fills from the favourites while it is automatic; the first edit freezes all three
   * slots, so a later favourite no longer changes it, and the reset hands it back to the
   * favourites.
   */
  @Test
  void theFirstEditFreezesTheAutomaticFill() {
    run(
        memberState,
        "tabbar-freeze",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(STAR_TWO_PAGES);
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(slot(page, 0)).hasAttribute("data-entry-key", "refinery.orders");
          assertThat(slot(page, 1)).hasAttribute("data-entry-key", "materials.list");
          assertThat(slot(page, 2)).hasAttribute("data-entry-key", "missions.list");
          assertThat(editor(page).getByTestId("nav-tabbar-state")).containsText("Automatisch");

          openEditor(page);
          editor(page).getByTestId("nav-tabbar-slot-2").selectOption("hangar.overview");

          assertThat(slot(page, 2)).hasAttribute("data-entry-key", "hangar.overview");
          assertThat(slot(page, 0)).hasAttribute("data-entry-key", "refinery.orders");
          assertThat(editor(page).getByTestId("nav-tabbar-state")).containsText("Fest belegt");

          page.evaluate(
              """
              async () => {
                const state = document.querySelector('[data-favourites-state]');
                await window.krtFetch.write({
                  method: 'PUT',
                  url: '/navigation/favourites',
                  payload: { keys: ['materials.list'], version: Number(state.dataset.version) },
                  toast: false,
                });
              }
              """);
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(slot(page, 0)).hasAttribute("data-entry-key", "refinery.orders");

          openEditor(page);
          editor(page).getByTestId("nav-tabbar-reset").click();

          assertThat(slot(page, 0)).hasAttribute("data-entry-key", "materials.list");
          assertThat(editor(page).getByTestId("nav-tabbar-state")).containsText("Automatisch");
          assertThat(editor(page).getByTestId("nav-tabbar-reset")).isDisabled();
        });
  }

  /** Choosing an entry that already sits in another slot swaps the two slots. */
  @Test
  void choosingATakenEntrySwapsTheSlots() {
    run(
        memberState,
        "tabbar-swap-taken",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          openEditor(page);
          String first = slot(page, 0).getAttribute("data-entry-key");
          String second = slot(page, 1).getAttribute("data-entry-key");
          String third = slot(page, 2).getAttribute("data-entry-key");

          editor(page).getByTestId("nav-tabbar-slot-0").selectOption(second);

          assertThat(slot(page, 0)).hasAttribute("data-entry-key", second);
          assertThat(slot(page, 1)).hasAttribute("data-entry-key", first);
          assertThat(slot(page, 2)).hasAttribute("data-entry-key", third);
        });
  }

  /** The left and right buttons swap a slot with its neighbour and keep focus on the pair. */
  @Test
  void theNeighbourButtonsSwapTwoSlots() {
    run(
        memberState,
        "tabbar-swap-neighbour",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          openEditor(page);
          assertThat(editor(page).getByTestId("nav-tabbar-left-0")).isDisabled();
          assertThat(editor(page).getByTestId("nav-tabbar-right-2")).isDisabled();

          editor(page).getByTestId("nav-tabbar-right-0").click();

          assertThat(slot(page, 0)).hasAttribute("data-entry-key", "storage.overview");
          assertThat(slot(page, 1)).hasAttribute("data-entry-key", "missions.list");
          assertThat(page.getByTestId("nav-live")).containsText("Plätze getauscht");
        });
  }

  /**
   * A long press on a tab opens its picker without following the link; picking an entry puts it
   * into the slot.
   */
  @Test
  void aLongPressOpensThePickerAsAnExtraPath() {
    run(
        memberState,
        "tabbar-longpress",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          String url = page.url();
          BoundingBox box = slot(page, 1).boundingBox();
          page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
          page.mouse().down();
          page.waitForTimeout(LONG_PRESS_WAIT_MS);
          page.mouse().up();

          Locator picker = page.locator(".tabbar-picker[data-tabbar-mode='member']");
          assertThat(picker).isVisible();
          assertEquals(url, page.url());
          Locator item = picker.locator("[data-entry-key='hangar.overview']");
          assertThat(item).isVisible();
          item.click();

          assertThat(slot(page, 1)).hasAttribute("data-entry-key", "hangar.overview");
          assertThat(picker).isHidden();
          assertEquals(url, page.url());
        });
  }

  /** A short press still follows the tab's link. */
  @Test
  void aShortPressStillNavigates() {
    run(
        memberState,
        "tabbar-short-press",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");

          slot(page, 1).click();

          assertThat(page).hasURL(Pattern.compile(".*/inventory.*"));
        });
  }

  /** The tabs, the pickers and the edit controls meet the 44 px touch floor. */
  @Test
  void theTouchTargetsMeetTheFloor() {
    run(
        memberState,
        "tabbar-touch-floor",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          for (int index = 0; index < 3; index++) {
            assertFloor(slot(page, index).boundingBox(), "tab " + index);
          }
          openEditor(page);
          for (int index = 0; index < 3; index++) {
            assertFloor(
                editor(page).getByTestId("nav-tabbar-slot-" + index).boundingBox(), "select");
          }
          assertFloor(editor(page).getByTestId("nav-tabbar-right-0").boundingBox(), "swap");
          assertFloor(editor(page).getByTestId("nav-tabbar-reset").boundingBox(), "reset");
        });
  }

  /** An administrator's admin pages show the admin bar and the member pages the member bar. */
  @Test
  void anAdministratorHasOneBarPerMode() {
    run(
        adminState,
        "tabbar-admin",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");
          assertThat(page.getByTestId("mobile-tab-admin.dashboard")).isVisible();
          assertThat(page.getByTestId("mobile-tab-admin.members")).isVisible();
          assertThat(page.getByTestId("mobile-tab-admin.auditlog")).isVisible();
          assertThat(page.getByTestId("mobile-tab-missions.list")).isHidden();
          assertThat(page.getByTestId("mobile-tab-notifications")).isVisible();
          assertThat(page.getByTestId("mobile-tab-menu")).isVisible();

          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(page.getByTestId("mobile-tab-missions.list")).isVisible();
          assertThat(page.getByTestId("mobile-tab-admin.dashboard")).isHidden();
        });
  }

  private static void assertFloor(BoundingBox box, String what) {
    assertTrue(
        box.height >= TOUCH_FLOOR && box.width >= TOUCH_FLOOR,
        what + " is " + box.width + "x" + box.height + ", below the touch floor");
  }

  private static Locator slot(Page page, int index) {
    return page.locator(
        ".mobile-tab-slots[data-tabbar-mode='member'] .mobile-tab[data-slot-index='"
            + index
            + "']");
  }

  private static Locator editor(Page page) {
    return page.locator(".nav-tabbar-edit[data-tabbar-mode='member']");
  }

  private static void openEditor(Page page) {
    page.getByTestId("mobile-tab-menu").click();
    page.getByTestId("nav-edit-toggle").click();
    assertThat(editor(page)).isVisible();
  }

  private static void run(Path state, String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state)
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT))) {
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
