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
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The job-order priority queue on {@code /orders} reorders without dragging (REQ-ORDERS-020,
 * REQ-UI-031): the up control and Alt+ArrowDown move an order one place in place, the focus stays
 * on the moved order, the live region announces the move, and on a phone the controls keep the 44px
 * touch target.
 */
@Tag("e2e")
class OrdersQueueReorderWithoutDragE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String INDEX_OF =
      "id => Array.from(document.querySelectorAll('#orders-results tr.draggable-row'))"
          + ".findIndex(r => r.dataset.id === id)";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String firstOrderId;
  private static String secondOrderId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds two open orders so the queue has a row
   * that can move up.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Reorder Material");
      firstOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Reorder A", materialId, 650, 10.0);
      secondOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Reorder B", materialId, 650, 10.0);
    }
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
   * Moves the lower of the two seeded orders up with its button, then back down with Alt+ArrowDown,
   * and asserts the queue position, the focus, the announcement and that no reload happened.
   */
  @Test
  void movesAnOrderUpAndDownWithoutDragging() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.evaluate("() => { window.__krtNoReload = true; }");

        String movedId = lowerOf(page);
        int before = indexOf(page, movedId);
        assertTrue(before > 0, "the moved order must not already head the page's queue");

        Locator up = rowOf(page, movedId).getByTestId("reorder-up");
        assertThat(up).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + movedId + "/priority/ajax")
                    && "PUT".equals(response.request().method()),
            up::click);
        waitForIndex(page, movedId, before - 1);
        assertFocusOn(page, movedId, "up");
        page.waitForFunction(
            "() => (document.querySelector('[data-reorder-live]')?.textContent || '').length > 0");

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + movedId + "/priority/ajax")
                    && "PUT".equals(response.request().method()),
            () -> page.keyboard().press("Alt+ArrowDown"));
        waitForIndex(page, movedId, before);
        assertFocusOn(page, movedId, "up");

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the reorder must update in place — no page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-reorder-without-drag");
        throw failure;
      }
    }
  }

  /** On a phone the up and down controls are at least 44px in both directions (REQ-UI-009). */
  @Test
  void theControlsKeepTheTouchTargetOnAPhone() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(375, 812)
                .setIsMobile(true)
                .setHasTouch(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        String movedId = lowerOf(page);
        for (String testId : new String[] {"reorder-up", "reorder-down"}) {
          Locator control = rowOf(page, movedId).getByTestId(testId);
          assertThat(control).isVisible();
          BoundingBox box = control.boundingBox();
          assertTrue(
              box.width >= 44 && box.height >= 44,
              testId + " must keep the 44px touch target, was " + box.width + "x" + box.height);
        }
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "orders-reorder-touch-target");
        throw failure;
      }
    }
  }

  /**
   * Picks the seeded order that sits lower in the queue, so it can move up.
   *
   * @param page the order list
   * @return the id of the lower seeded order
   */
  private static String lowerOf(Page page) {
    assertThat(rowOf(page, firstOrderId)).isVisible();
    assertThat(rowOf(page, secondOrderId)).isVisible();
    return indexOf(page, firstOrderId) > indexOf(page, secondOrderId)
        ? firstOrderId
        : secondOrderId;
  }

  /**
   * Locates the queue row of one order.
   *
   * @param page the order list
   * @param id the order id
   * @return the row locator
   */
  private static Locator rowOf(Page page, String id) {
    return page.locator("#orders-results tr.draggable-row[data-id='" + id + "']");
  }

  /**
   * Reads the zero-based position of an order among the queue rows of the page.
   *
   * @param page the order list
   * @param id the order id
   * @return its position, or -1 when it is not in the queue
   */
  private static int indexOf(Page page, String id) {
    return ((Number) page.evaluate(INDEX_OF, id)).intValue();
  }

  /**
   * Waits until the re-rendered queue shows an order at the given position.
   *
   * @param page the order list
   * @param id the order id
   * @param index the expected zero-based position
   */
  private static void waitForIndex(Page page, String id, int index) {
    page.waitForFunction(
        "([id, index]) => (" + INDEX_OF + ")(id) === index", new Object[] {id, index});
  }

  /**
   * Asserts that the focus sits on the given move control of the given order's row.
   *
   * @param page the order list
   * @param id the order id
   * @param direction the {@code data-reorder-move} value of the focused control
   */
  private static void assertFocusOn(Page page, String id, String direction) {
    page.waitForFunction(
        "([id, dir]) => { const a = document.activeElement;"
            + " return !!a && a.getAttribute('data-reorder-move') === dir"
            + " && a.closest('tr')?.dataset.id === id; }",
        new Object[] {id, direction});
  }
}
