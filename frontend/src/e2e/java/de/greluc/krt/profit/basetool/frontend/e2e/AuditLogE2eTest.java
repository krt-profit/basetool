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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unified admin audit-log viewer end to end (REQ-AUDIT-001/-002, ADR-0037): a bank mutation appears
 * under the Bank tab, filters work in place, tabs switch, and {@code /admin/bank-audit} redirects
 * to the viewer.
 */
@Tag("e2e")
class AuditLogE2eTest {

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

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    String accountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Audit Account", "SPECIAL");
    String holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, employeeId);
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, accountId, holderId, 1234);
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
   * Lists the bank tab's rows, filters in place, switches tabs, and follows the {@code
   * /admin/bank-audit} redirect.
   */
  @Test
  void unifiedAuditViewerListsFiltersAndSwitchesTabs() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);

        E2eSupport.navigate(page, baseUrl + "/admin/bank-audit");
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*/admin/audit-log\\?domain=BANK.*"));

        assertThat(page.locator("[data-testid='audit-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-testid='audit-row']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertTrue(
            page.locator("[data-testid='audit-row']").count() >= 1,
            "the Bank tab lists at least one event");

        assertThat(page.locator("[data-testid='audit-purge-open']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        page.locator("[data-testid='audit-purge-open']").click();
        assertThat(page.locator("#audit-purge-modal .audit-purge-warning"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        page.locator("#audit-purge-modal [data-trigger='close-modal-display']").first().click();

        page.evaluate("() => { window.__krtNoReload = true; }");

        page.locator("[data-testid='audit-filter-event']").selectOption("DEPOSIT_BOOKED");
        page.locator("[data-testid='audit-filter-apply']").click();
        assertThat(page.locator("[data-testid='audit-row']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertTrue(
            page.locator("[data-testid='audit-row']").count() >= 1,
            "filtering by DEPOSIT_BOOKED still lists the seeded deposit");

        assertThat(page).hasURL(Pattern.compile(".*[?&]eventType=DEPOSIT_BOOKED.*"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Filtering the audit log must update in place — no page reload.");

        assertThat(page.locator("[data-testid='audit-filter-client']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));

        page.locator("[data-testid='audit-tab-INVENTORY']").click();
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*[?&]domain=INVENTORY.*"));
        assertThat(page.locator("[data-testid='audit-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));

        assertThat(page.locator("[data-testid='audit-filter-client']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.locator("[data-testid='audit-filter-client']").selectOption("basetool-frontend");
        page.locator("[data-testid='audit-filter-apply']").click();
        assertThat(page).hasURL(Pattern.compile(".*[?&]clientId=basetool-frontend.*"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Filtering by client must update in place — no page reload.");

        page.locator("[data-testid='audit-tab-HANGAR']").click();
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*[?&]domain=HANGAR.*"));
        assertThat(page.locator("[data-testid='audit-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(
                page.locator(
                    "[data-testid='audit-filter-event'] option[value='HANGAR_SHIP_CREATED']"))
            .hasCount(1);

        page.locator("[data-testid='audit-tab-BLUEPRINT']").click();
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*[?&]domain=BLUEPRINT.*"));
        assertThat(
                page.locator("[data-testid='audit-filter-event'] option[value='BLUEPRINT_ADDED']"))
            .hasCount(1);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "audit-log-viewer");
        throw failure;
      }
    }
  }
}
