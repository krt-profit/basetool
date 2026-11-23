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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves that a fragment swap never removes the control under a press (REQ-FE-038), on the money
 * section of an aUEC order.
 */
@Tag("e2e")
class FragmentSwapPressE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM = "00000000-0000-0000-0000-000000000001";
  private static final String IS_CONNECTED = "control => control.isConnected";

  /**
   * Holds two swaps that receive their fragment while the provide button is pressed, opens the
   * provision dialog on release and writes only the newer swap afterwards.
   */
  @Test
  void holdsTheSwapUntilThePressedControlReceivedItsClick() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        String orderPath = openOrder(page);
        Locator provide = page.getByTestId("order-money-provide");
        ElementHandle pressed = provide.elementHandle();
        provide.hover();
        page.mouse().down();

        swapMoney(page, orderPath, "superseded");
        assertEquals(true, pressed.evaluate(IS_CONNECTED));
        swapMoney(page, orderPath, "current");
        assertEquals(true, pressed.evaluate(IS_CONNECTED));

        page.mouse().up();

        assertThat(page.locator("#order-money-provision-modal")).isVisible();
        assertEquals(false, swapResult(page, "superseded"));
        assertEquals(true, swapResult(page, "current"));
        assertEquals(false, pressed.evaluate(IS_CONNECTED));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "fragment-swap-press-inside");
        throw failure;
      }
    }
  }

  /** Writes a swap at once while the press is on the page heading, outside the swapped section. */
  @Test
  void writesTheSwapAtOnceWhileThePressIsOutsideTheContainer() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        String orderPath = openOrder(page);
        ElementHandle control = page.getByTestId("order-money-provide").elementHandle();
        page.locator("h1").first().hover();
        page.mouse().down();

        swapMoney(page, orderPath, "outside");
        assertEquals(true, swapResult(page, "outside"));
        assertEquals(false, control.evaluate(IS_CONNECTED));

        page.mouse().up();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "fragment-swap-press-outside");
        throw failure;
      }
    }
  }

  /**
   * Signs in and creates an aUEC order whose money section offers the provide button.
   *
   * @param page the page to sign in and create the order on
   * @return the order's path
   */
  private static String openOrder(@NotNull Page page) {
    String baseUrl = STACK.baseUrl();
    E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
    E2eSupport.navigate(page, baseUrl + "/orders/create?lang=de");
    return JobOrderMoneyE2eTest.createOrder(page, baseUrl, IRIDIUM, IRIDIUM, "6000000");
  }

  /**
   * Starts a swap of the money section, keeps its promise under {@code name} and returns once the
   * page has received the fragment and acted on it.
   *
   * @param page the order page
   * @param orderPath the order's path
   * @param name the key of the swap's promise in {@code window.e2eSwaps}
   */
  private static void swapMoney(@NotNull Page page, @NotNull String orderPath, String name) {
    Response fragment =
        page.waitForResponse(
            response -> response.url().endsWith(orderPath + "?fragment=money"),
            () ->
                page.evaluate(
                    """
                    ([url, name]) => {
                      window.e2eSwaps ||= {};
                      window.e2eSwaps[name] = window.krtFetch.swap({
                        url,
                        container: '#order-money-results',
                        fragmentValue: 'money',
                        history: false
                      });
                    }
                    """,
                    List.of(orderPath, name)));
    assertNull(fragment.finished());
    E2eSupport.settle(page);
  }

  /**
   * Waits for a swap started by {@link #swapMoney} to settle.
   *
   * @param page the order page
   * @param name the key of the swap's promise
   * @return whether the swap wrote its fragment
   */
  private static Object swapResult(@NotNull Page page, String name) {
    return page.evaluate("name => window.e2eSwaps[name]", name);
  }
}
