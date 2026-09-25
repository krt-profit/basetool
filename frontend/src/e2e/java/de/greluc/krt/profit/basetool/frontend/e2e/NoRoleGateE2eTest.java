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
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that an account holding no role is told so and reaches nothing (REQ-SEC-053).
 *
 * <p>Uses the realm fixture {@code test-norole}, an enabled account with no realm role at all.
 */
@Tag("e2e")
class NoRoleGateE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String NO_ROLE_USER = "test-norole";
  private static final String NO_ROLE_PASSWORD = "test-norole-pw";

  private static Playwright playwright;
  private static Browser browser;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
  }

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
   * Asserts that signing in with no role lands on the no-role page, with the no-role block present
   * and the pending-approval block absent.
   */
  @Test
  void aRoleLessMemberLandsOnTheNoRolePage() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, NO_ROLE_USER, NO_ROLE_PASSWORD);

        assertThat(page.locator("#pending-approval-no-role"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertEquals(
            0,
            page.locator("#pending-approval-waiting").count(),
            "a role-less account is approved; telling it to wait for approval is the wrong page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "no-role-gate-landing");
        throw failure;
      } finally {
        page.close();
      }
    }
  }

  /**
   * Asserts that a role-less account reaches no page either, checked with a deep link to the Lager.
   */
  @Test
  void aRoleLessMemberReachesNoPage() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, NO_ROLE_USER, NO_ROLE_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/inventory/all");
        page.waitForLoadState();

        assertEquals(0, page.locator("#inventoryTable").count(), "no page of the tool may render");
        assertThat(page.locator("#pending-approval-no-role"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "no-role-gate-deep-link");
        throw failure;
      } finally {
        page.close();
      }
    }
  }
}
