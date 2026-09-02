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
 * Unified admin audit-log viewer end to end (REQ-AUDIT-001/-002, ADR-0037): a bank mutation writes
 * a bank audit row, and the unified {@code /admin/audit-log} viewer lists it under the Bank tab,
 * filters by event type in place, switches between the eight area tabs, and the legacy {@code
 * /admin/bank-audit} URL redirects in. The viewer is admin-only — the permission carve-out itself
 * is covered by {@link BankPermissionsE2eTest}.
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
    // Generate a deposit so a DEPOSIT_BOOKED bank audit row is guaranteed for the Bank tab.
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
   * Lists the bank tab's rows, filters in place, switches tabs, and follows the legacy redirect.
   */
  @Test
  void unifiedAuditViewerListsFiltersAndSwitchesTabs() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);

        // The legacy bank-audit URL redirects into the unified page on the Bank tab.
        E2eSupport.navigate(page, baseUrl + "/admin/bank-audit");
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*/admin/audit-log\\?domain=BANK.*"));

        assertThat(page.locator("[data-testid='audit-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        // Web-first wait before the one-shot count(): rows paint shortly after the panel, and on
        // slower engines (webkit) a bare count() races that paint and reads 0. isVisible retries.
        assertThat(page.locator("[data-testid='audit-row']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertTrue(
            page.locator("[data-testid='audit-row']").count() >= 1,
            "the Bank tab lists at least one event");

        // The admin retention-purge control is present (REQ-AUDIT-004); opening it surfaces the
        // backup-recommended warning. Non-mutating on purpose — actually purging here would delete
        // the seeded deposit row the rest of this run relies on.
        assertThat(page.locator("[data-testid='audit-purge-open']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        page.locator("[data-testid='audit-purge-open']").click();
        assertThat(page.locator("#audit-purge-modal .audit-purge-warning"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        page.locator("#audit-purge-modal [data-trigger='close-modal-display']").first().click();

        // Mark the live document: a full navigation/reload wipes it. The filter swaps the results
        // in
        // place (REQ-FE-002), so there is no form-post navigation to await.
        page.evaluate("() => { window.__krtNoReload = true; }");

        // Filter to DEPOSIT_BOOKED and apply — the seeded deposit keeps the table non-empty.
        page.locator("[data-testid='audit-filter-event']").selectOption("DEPOSIT_BOOKED");
        page.locator("[data-testid='audit-filter-apply']").click();
        assertThat(page.locator("[data-testid='audit-row']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertTrue(
            page.locator("[data-testid='audit-row']").count() >= 1,
            "filtering by DEPOSIT_BOOKED still lists the seeded deposit");

        // The filter ran in place — no page reload — and the URL carries the filter (history sync).
        assertThat(page).hasURL(Pattern.compile(".*[?&]eventType=DEPOSIT_BOOKED.*"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "Filtering the audit log must update in place — no page reload.");

        // Since V238 the bank trail records the originating client too, so the filter is offered
        // on this tab as well -- it was the one exception when the column shipped for audit_event
        // alone (REQ-AUDIT-005).
        assertThat(page.locator("[data-testid='audit-filter-client']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));

        // Switch to the Lager (INVENTORY) tab — a tab is a plain link (full navigation).
        page.locator("[data-testid='audit-tab-INVENTORY']").click();
        page.waitForLoadState();
        assertThat(page).hasURL(Pattern.compile(".*[?&]domain=INVENTORY.*"));
        assertThat(page.locator("[data-testid='audit-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));

        // ... and on a generic tab as well. Filtering to the app narrows to rows the app wrote;
        // the seeded stack was written by the web frontend, so the assertion is on the round trip
        // carrying the filter, not on a row count.
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
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "audit-log-viewer");
        throw failure;
      }
    }
  }
}
