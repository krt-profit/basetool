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
 * REQ-SEC-053 / decision D5: an account that holds <b>no role</b> is told so, and reaches nothing.
 *
 * <p>Before ADR-0159 this state was invisible. A token whose realm roles matched nothing the
 * application knows was mapped onto the seeded {@code GUEST} role, whose authority set was empty —
 * and the URL matrix's anonymous families then let it through, so "no role" quietly meant "the
 * anonymous read surface". With that surface gone, an empty authority set would mean something
 * worse: a principal that passes every {@code isAuthenticated()} gate and fails only the ones that
 * name a role, which is a per-endpoint accident rather than a decision.
 *
 * <p>The realm fixture {@code test-norole} is the shape that produces it: an enabled account in
 * {@code default-roles-iri} with no application role at all. What must happen is a page that says
 * what is wrong and who fixes it — not a 403 body, not the dashboard with everything missing, and
 * not the "Freigabe ausstehend" copy, which would send the member to wait for an approval they
 * already have.
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
   * Signing in with no role lands on the no-role page — not on the dashboard, and not on the
   * pending-approval copy.
   *
   * <p>The two blocks live in the same template and are chosen by one flag, so the case asserts
   * both directions: the no-role block present and the waiting block absent. Getting that pair
   * wrong is the defect the Android app hit first — a member with an approved account being told to
   * wait for an approval.
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
   * And it reaches no page of the tool, however it is asked for.
   *
   * <p>The gate is a filter, so a deep link is refused the same way the dashboard is. Asserted on a
   * page that carries data — the Lager — because "does not render" is the whole point: the failure
   * mode this guards against is a role-less account passing an {@code isAuthenticated()} gate and
   * being served a page whose own checks happen to be about something else.
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
