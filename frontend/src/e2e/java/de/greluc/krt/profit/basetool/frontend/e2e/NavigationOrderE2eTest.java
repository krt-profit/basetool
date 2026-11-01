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
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the order of REQ-UI-032 in „Anpassen“ without a drag: the up/down buttons and Alt+Arrow
 * move groups and links, the focus stays on the moved item, a live region announces the move, the
 * order survives a reload, „Standardreihenfolge“ resets it, a stale window is told so and the
 * controls keep the touch floor.
 */
@Tag("e2e")
class NavigationOrderE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-member");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-member-pw");

  private static final String RESET_ORDER =
      """
      async () => {
        const state = document.querySelector('[data-favourites-state]');
        await window.krtFetch.write({
          method: 'POST',
          url: '/navigation/order/member/reset',
          payload: { version: Number(state.dataset.orderMemberVersion) },
          toast: false,
        });
      }
      """;

  private static final String LINK_KEYS =
      """
      () => Array.from(document.querySelectorAll(
          "#sidebar .nav-group[data-group-key='operations'] .nav-row[data-entry-key]"))
        .map((row) => row.dataset.entryKey)
      """;

  private static final String GROUP_KEYS =
      """
      () => Array.from(document.querySelectorAll(
          '#sidebar .nav-mode-main .nav-group[data-group-key]:not(.nav-more)'))
        .map((group) => group.dataset.groupKey)
      """;

  private static final String MISSIONS_ROW = "#sidebar .nav-row[data-entry-key='missions.list']";

  private static final String OPERATIONS_GROUP_DOWN =
      "#sidebar .nav-group[data-group-key='operations'] .nav-group-controls"
          + " [data-reorder-move='down']";

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

  /** Leaves the member with the default order, whatever the previous scenario did. */
  @BeforeEach
  void resetTheOrder() {
    run(
        "nav-order-clear",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(RESET_ORDER);
        });
  }

  /** Opens the drawer and turns on „Anpassen“. */
  private static void startEditing(Page page) {
    page.locator("#hamburger").click();
    page.getByTestId("nav-edit-toggle").click();
    assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-editing\\b"));
  }

  private static List<String> keys(Page page, String script) {
    return ((List<?>) page.evaluate(script)).stream().map(String::valueOf).toList();
  }

  private static int orderVersion(Page page) {
    return Integer.parseInt(
        page.locator("[data-favourites-state]").getAttribute("data-order-member-version"));
  }

  /**
   * The down button of a link moves it in place, keeps the focus on the same button, announces the
   * move and persists it across a reload; the page is not reloaded.
   */
  @Test
  void aLinkMovesWithItsButtonsAndStaysFocused() {
    run(
        "nav-order-link-button",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate("() => { window.__orderMarker = 'still-here'; }");
          startEditing(page);
          List<String> before = keys(page, LINK_KEYS);
          assertEquals("missions.list", before.getFirst());
          int version = orderVersion(page);

          Locator down = page.locator(MISSIONS_ROW + " [data-reorder-move='down']");
          down.click();

          List<String> after = keys(page, LINK_KEYS);
          assertEquals(before.get(1), after.getFirst());
          assertEquals("missions.list", after.get(1));
          assertThat(down).isFocused();
          assertThat(page.getByTestId("nav-live")).containsText("verschoben");
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-order-member-version", String.valueOf(version + 1));
          assertEquals("still-here", page.evaluate("() => window.__orderMarker"));

          page.reload();
          assertEquals(after, keys(page, LINK_KEYS));
        });
  }

  /** Alt+ArrowDown on a focused link moves it, and the focus stays on the link. */
  @Test
  void aLinkMovesWithAltArrowAndStaysFocused() {
    run(
        "nav-order-link-key",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/hangar");
          startEditing(page);
          List<String> before = keys(page, LINK_KEYS);
          Locator link = page.locator(MISSIONS_ROW + " a.nav-link");
          int version = orderVersion(page);
          link.focus();

          page.keyboard().press("Alt+ArrowDown");

          assertEquals("missions.list", keys(page, LINK_KEYS).get(1));
          assertEquals(before.get(1), keys(page, LINK_KEYS).getFirst());
          assertThat(link).isFocused();
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-order-member-version", String.valueOf(version + 1));

          page.keyboard().press("Alt+ArrowUp");

          assertEquals(before, keys(page, LINK_KEYS));
          assertThat(link).isFocused();
          assertThat(page.locator("[data-favourites-state]"))
              .hasAttribute("data-order-member-version", String.valueOf(version + 2));
        });
  }

  /** The up button of the first link and the down button of the last link are unavailable. */
  @Test
  void theEndsOfAGroupHaveNoFurtherMove() {
    run(
        "nav-order-ends",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          startEditing(page);
          List<String> keys = keys(page, LINK_KEYS);

          assertThat(
                  page.locator(
                      "#sidebar .nav-row[data-entry-key='"
                          + keys.getFirst()
                          + "'] [data-reorder-move='up']"))
              .hasAttribute("aria-disabled", "true");
          assertThat(
                  page.locator(
                      "#sidebar .nav-row[data-entry-key='"
                          + keys.getLast()
                          + "'] [data-reorder-move='down']"))
              .hasAttribute("aria-disabled", "true");
        });
  }

  /** A group moves below its neighbour with its own buttons and stays there after a reload. */
  @Test
  void aGroupMovesWithItsButtons() {
    run(
        "nav-order-group",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          startEditing(page);
          List<String> before = keys(page, GROUP_KEYS);
          int at = before.indexOf("operations");
          assertTrue(at >= 0 && at < before.size() - 1, "operations has a successor");

          Locator down = page.locator(OPERATIONS_GROUP_DOWN);
          down.click();

          List<String> after = keys(page, GROUP_KEYS);
          assertEquals("operations", after.get(at + 1));
          assertEquals(before.get(at + 1), after.get(at));
          assertThat(down).isFocused();
          assertThat(page.getByTestId("nav-live")).containsText("verschoben");

          page.reload();
          assertEquals(after, keys(page, GROUP_KEYS));
        });
  }

  /** „Standardreihenfolge“ restores the default order in place and disables itself. */
  @Test
  void theResetRestoresTheDefaultOrder() {
    run(
        "nav-order-reset",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          startEditing(page);
          List<String> links = keys(page, LINK_KEYS);
          List<String> groups = keys(page, GROUP_KEYS);
          Locator reset =
              page.locator("#sidebar [data-testid='nav-order-reset'][data-mode='member']");
          assertThat(reset).isDisabled();

          page.locator(MISSIONS_ROW + " [data-reorder-move='down']").click();
          page.locator(OPERATIONS_GROUP_DOWN).click();
          assertThat(reset).isEnabled();

          reset.click();

          assertThat(page.getByTestId("nav-live")).containsText("Standardreihenfolge");
          assertThat(reset).isDisabled();
          assertEquals(links, keys(page, LINK_KEYS));
          assertEquals(groups, keys(page, GROUP_KEYS));
        });
  }

  /**
   * A second window that holds a stale order version has its move dropped: the lists reload in the
   * other window's order and the hint says so.
   */
  @Test
  void aStaleWindowDropsItsMoveAndSaysSo() {
    try (BrowserContext context = newContext(1440, 900)) {
      Page first = context.newPage();
      Page second = context.newPage();
      E2eSupport.navigate(first, STACK.baseUrl() + "/missions");
      E2eSupport.navigate(second, STACK.baseUrl() + "/hangar");
      startEditing(first);
      startEditing(second);

      first.locator(MISSIONS_ROW + " [data-reorder-move='down']").click();
      List<String> moved = keys(first, LINK_KEYS);
      assertEquals("missions.list", moved.get(1));

      second.locator(OPERATIONS_GROUP_DOWN).click();

      assertThat(second.getByText("In einem anderen Fenster")).isVisible();
      assertThat(
              second.locator(
                  "#sidebar .nav-group[data-group-key='operations'] .nav-row[data-entry-key]"
                      + ":first-of-type"))
          .hasAttribute("data-entry-key", moved.getFirst());
      assertEquals(moved, keys(second, LINK_KEYS));
    }
  }

  /** The move controls are 44 px up to 1024 px and 32 px above. */
  @Test
  void theMoveControlsFollowTheTouchFloor() {
    for (int width : new int[] {1024, 1280}) {
      int floor = width <= 1024 ? 44 : 32;
      run(
          width,
          900,
          "nav-order-size-" + width,
          page -> {
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            startEditing(page);

            for (String selector :
                new String[] {
                  MISSIONS_ROW + " [data-reorder-move='down']", OPERATIONS_GROUP_DOWN
                }) {
              BoundingBox box = page.locator(selector).first().boundingBox();
              assertTrue(box.width >= floor && box.height >= floor, selector + " at " + width);
              assertTrue(box.width < 44 || width <= 1024, selector + " stays dense at " + width);
            }
          });
    }
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
