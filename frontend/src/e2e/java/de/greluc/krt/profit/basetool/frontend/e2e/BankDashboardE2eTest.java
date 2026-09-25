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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Bank dashboard + statement exports end to end (REQ-BANK-014/-015/-016, epic #556): the D1
 * management dashboard renders the totals strip and per-account cards with their inline sparkline,
 * the account statement PDF downloads for a granted viewer, and the management three-month report
 * is management-gated (employee → 403).
 */
@Tag("e2e")
class BankDashboardE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

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
    accountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Dashboard Account", "SPECIAL");
    seeder.createBankGrant(MGMT_USER, MGMT_PASSWORD, employeeId, accountId, true, true, true);
    String holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, employeeId);
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, accountId, holderId, 1500);
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
   * The management dashboard renders the totals strip and the seeded account card with a sparkline.
   */
  @Test
  void managementDashboardRendersTotalsAndCards() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MGMT_USER, MGMT_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='bank-totals']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-account-no][data-testid='bank-account-card']").first())
            .isVisible();
        assertTrue(
            page.locator("[data-testid='bank-account-card'] svg polyline").count() >= 1,
            "at least one account card shows a sparkline polyline");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-dashboard-management");
        throw failure;
      }
    }
  }

  /** A granted viewer can download the account statement PDF for a period (backend returns 200). */
  @Test
  void statementPdfDownloadsForGrantedViewer() {
    String from = Instant.now().minus(7, ChronoUnit.DAYS).toString();
    String to = Instant.now().plus(1, ChronoUnit.DAYS).toString();
    String path = "/api/v1/bank/accounts/" + accountId + "/statement?from=" + from + "&to=" + to;
    assertEquals(
        200,
        seeder.attemptGetStatus(EMPLOYEE_USER, EMPLOYEE_PASSWORD, path),
        "the granted employee downloads the statement");
  }

  /** The three-month report is management-only: employee 403, management 200. */
  @Test
  void threeMonthReportIsManagementOnly() {
    assertEquals(
        403,
        seeder.attemptGetStatus(
            EMPLOYEE_USER, EMPLOYEE_PASSWORD, "/api/v1/bank/export/three-month-report"),
        "an employee cannot export the three-month report");
    assertEquals(
        200,
        seeder.attemptGetStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/export/three-month-report"),
        "management can export the three-month report");
  }
}
