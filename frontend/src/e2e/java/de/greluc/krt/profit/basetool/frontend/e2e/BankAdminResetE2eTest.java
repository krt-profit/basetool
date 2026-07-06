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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Admin wipe-reset end to end (REQ-BANK-013, epic #556): an admin types the confirmation token in
 * the A1 danger modal and submits; every balance is zeroed and the action is audit-logged. The wipe
 * is global, but the e2e classes run sequentially so this never races another class's assertions.
 */
@Tag("e2e")
class BankAdminResetE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String accountId;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    accountId = seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Wipe Account", "SPECIAL");
    String holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, employeeId);
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, accountId, holderId, 5000);
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

  /** The type-to-confirm wipe modal zeroes the seeded balance and shows the success toast. */
  @Test
  void adminWipeResetZeroesBalancesAndIsAudited() {
    // Precondition: the seeded account holds money.
    assertEquals(
        0,
        balance(accountId).compareTo(new BigDecimal("5000")),
        "the account starts with the seeded balance");
    long auditBefore = auditTotal();

    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/admin/bank");
        page.waitForLoadState();
        // A full reload would wipe this marker; the #582 in-place wipe leaves it intact.
        page.evaluate("window.__krtNoReload = true;");
        page.locator("[data-testid='bank-wipe-open']")
            .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(20_000));
        // The submit button stays disabled until the exact token is typed.
        page.locator("[data-testid='bank-wipe-confirm']").fill("WIPE");
        // #582: the wipe posts via the AJAX twin and reports success as a toast — no PRG reload, so
        // wait on the write's XHR response rather than a post-submit document navigation.
        page.waitForResponse(
            r -> r.url().contains("/admin/bank/wipe-reset") && "POST".equals(r.request().method()),
            // 60 s (above the 30 s default): the wipe's proxied XHR round-trip can outrun 30 s on a
            // contended CI runner, timing out an otherwise-correct POST. The HTTP/2 stream-reset
            // flake that used to hang this wait (Firefox, then WebKit) is now fixed at the source —
            // the E2E stack serves HTTP/1.1 (SERVER_HTTP2_ENABLED=false, see docker-compose.e2e.yml
            // and E2eSupport#launchFirefox). The headroom stays as belt-and-suspenders without
            // masking a genuinely stuck request.
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> page.locator("[data-testid='bank-wipe-submit']").click());
        assertThat(page.locator(".notification-toast").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the wipe must save in place without reloading the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-admin-wipe");
        throw failure;
      }
    }

    assertEquals(
        0, balance(accountId).compareTo(BigDecimal.ZERO), "the balance is zero after the wipe");
    org.junit.jupiter.api.Assertions.assertTrue(
        auditTotal() > auditBefore, "the wipe wrote at least one audit event");
  }

  /**
   * Reads an account's compute-on-read balance from its detail JSON.
   *
   * @param account the account id
   * @return the current balance
   */
  private static BigDecimal balance(String account) {
    return JsonParser.parseString(
            seeder.getBody(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/accounts/" + account))
        .getAsJsonObject()
        .getAsJsonObject("account")
        .get("balance")
        .getAsBigDecimal();
  }

  /**
   * Reads the total number of audit events via the admin audit endpoint.
   *
   * @return the {@code totalElements} of the audit page
   */
  private static long auditTotal() {
    return JsonParser.parseString(
            seeder.getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/bank/admin/audit?size=1"))
        .getAsJsonObject()
        .get("totalElements")
        .getAsLong();
  }
}
