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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
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
 * Asserts the bank authorization matrix (REQ-BANK-008, REQ-BANK-009, REQ-BANK-010): bank gates
 * consult only bank roles and per-account grants, never org-unit membership, and the audit log is
 * admin-only.
 *
 * <p>Uses the {@code realm-export.e2e.json} fixtures; the matrix is checked against the backend
 * endpoints per user, and the member-sees-nothing case also through the {@code /bank} UI.
 */
@Tag("e2e")
class BankPermissionsE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";
  private static final String BANK_MEMBER_USER = "test-bank-member";
  private static final String BANK_MEMBER_PASSWORD = "test-bank-member-pw";
  private static final String PLAIN_MEMBER_USER = "test-member";
  private static final String PLAIN_MEMBER_PASSWORD = "test-member-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String grantedAccountId;
  private static String ungrantedAccountId;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);

    grantedAccountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Perms Granted", "SPECIAL");
    ungrantedAccountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Perms Ungranted", "SPECIAL");

    seeder.createBankGrant(
        MGMT_USER, MGMT_PASSWORD, employeeId, grantedAccountId, true, true, true);
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

  /** Management sees every account in the paged list; the employee sees only the granted one. */
  @Test
  void accountVisibilityFollowsGrantsNotOrgMembership() {
    String mgmtList = seeder.getBody(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/accounts?size=500");
    assertTrue(mgmtList.contains(grantedAccountId), "management sees the granted account");
    assertTrue(mgmtList.contains(ungrantedAccountId), "management sees the ungranted account");

    String employeeList =
        seeder.getBody(EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/accounts?size=500");
    assertTrue(employeeList.contains(grantedAccountId), "employee sees the granted account");
    assertFalse(
        employeeList.contains(ungrantedAccountId), "employee must not see the ungranted account");
    assertEquals(
        200,
        seeder.attemptGetStatus(
            EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/accounts/" + grantedAccountId),
        "employee can open the granted account detail");
    assertEquals(
        403,
        seeder.attemptGetStatus(
            EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/accounts/" + ungrantedAccountId),
        "employee is forbidden on the ungranted account detail");
  }

  /** A bank employee who is also a squadron member reaches the bank surface unimpeded. */
  @Test
  void bankEmployeeWhoIsAlsoASquadronMemberWorksNormally() {
    assertEquals(
        200,
        seeder.attemptGetStatus(BANK_MEMBER_USER, BANK_MEMBER_PASSWORD, "/api/v1/bank/dashboard"),
        "an employee who is also a squadron member still reaches the dashboard");
  }

  /** A plain squadron member without any bank role sees nothing — the dashboard is forbidden. */
  @Test
  void plainSquadronMemberHasNoBankAccess() {
    assertEquals(
        403,
        seeder.attemptGetStatus(PLAIN_MEMBER_USER, PLAIN_MEMBER_PASSWORD, "/api/v1/bank/dashboard"),
        "a member without a bank role is forbidden on the bank dashboard");
    assertEquals(
        403,
        seeder.attemptGetStatus(
            PLAIN_MEMBER_USER, PLAIN_MEMBER_PASSWORD, "/api/v1/bank/accounts?size=500"),
        "a member without a bank role is forbidden on the account list");
  }

  /**
   * Management cannot create grants? No — but it cannot reach the admin audit log; only admin can.
   */
  @Test
  void auditLogIsAdminOnly() {
    assertEquals(
        403,
        seeder.attemptGetStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/admin/audit"),
        "bank management must not see the admin audit log");
    assertEquals(
        403,
        seeder.attemptGetStatus(EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/admin/audit"),
        "a bank employee must not see the admin audit log");
    assertEquals(
        200,
        seeder.attemptGetStatus(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/bank/admin/audit"),
        "an admin can read the audit log");
  }

  /** The grant matrix endpoint is management-only; an employee is forbidden. */
  @Test
  void grantsAreManagementOnly() {
    assertEquals(
        403,
        seeder.attemptGetStatus(EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/grants"),
        "a bank employee must not read the grants matrix");
    assertEquals(
        200,
        seeder.attemptGetStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/grants"),
        "bank management reads the grants matrix");
  }

  /**
   * UI boundary: a plain squadron member loading {@code /bank} never sees the dashboard content.
   */
  @Test
  void plainMemberUiHasNoBankDashboard() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, PLAIN_MEMBER_USER, PLAIN_MEMBER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='bank-account-card']")).hasCount(0);
        assertThat(page.locator("[data-testid='nav-bank']")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-permissions-plain-member");
        throw failure;
      }
    }
  }

  /** UI: bank management lands on the dashboard and sees the direct-booking + report actions. */
  @Test
  void managementUiSeesDashboardAndBookingCta() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MGMT_USER, MGMT_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='bank-movement-open']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-testid='bank-report-download']")).isVisible();
        assertTrue(
            page.locator("[data-testid='bank-account-card']").count() >= 2,
            "management dashboard shows the seeded account cards");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-permissions-management");
        throw failure;
      }
    }
  }

  /** Sanity: the granted account's detail JSON exposes the capability flags the K1 buttons use. */
  @Test
  void grantedAccountDetailExposesCapabilities() {
    String detail =
        seeder.getBody(
            EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/accounts/" + grantedAccountId);
    var caps = JsonParser.parseString(detail).getAsJsonObject().getAsJsonObject("capabilities");
    assertTrue(
        caps.get("canDeposit").getAsBoolean(), "employee may deposit on the granted account");
    assertTrue(caps.get("canWithdraw").getAsBoolean(), "employee may withdraw");
    assertTrue(caps.get("canTransfer").getAsBoolean(), "employee may transfer");
  }
}
