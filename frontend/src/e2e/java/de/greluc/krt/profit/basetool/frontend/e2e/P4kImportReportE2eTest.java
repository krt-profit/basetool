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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The P4K admin page on the isolated stack (REQ-P4K-015, REQ-P4K-016): a seeded run is listed, its
 * report is filtered by kind and change type and paged, and two review cases are kept and hidden;
 * every list swaps in place without a page reload.
 */
@Tag("e2e")
class P4kImportReportE2eTest {

  /** Provisions the isolated stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The seeded run. */
  private static final String RUN_ID = "0e2e0000-0000-4000-8000-000000009401";

  /** The seeded review cases. */
  private static final String KEEP_CASE = "0e2e0000-0000-4000-8000-000000009431";

  private static final String HIDE_CASE = "0e2e0000-0000-4000-8000-000000009432";

  private static Playwright playwright;
  private static Browser browser;
  private static Path adminSession;

  /** Seeds the run, its report and the review cases, and signs the admin in. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "The seeded run requires the isolated E2E stack");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    seeder.seedSql("/p4k-report-e2e-seed.sql");
    adminSession =
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

  /** Lists the run, filters and pages its report, and resolves both cases in place. */
  @Test
  void theRunsReportFiltersAndPages_andTheReviewCasesResolveInPlace() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setLocale("en-GB")
                .setStorageStatePath(adminSession))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/admin/p4k-import?lang=en");
        page.evaluate("() => { window.__krtNoReload = true; }");

        Locator runButton = page.locator("button[data-run-id='" + RUN_ID + "']");
        assertThat(runButton).isVisible();
        runButton.click();

        Locator rows = page.getByTestId("p4k-change-row");
        assertThat(rows).hasCount(25);
        page.locator("#krt-p4k-report-kind").selectOption("ITEM");
        page.locator("#krt-p4k-report-type").selectOption("UNMATCHED");
        assertThat(rows).hasCount(25);
        assertThat(rows.first()).containsText("E2E Unmatched 0");
        page.locator("#krt-p4k-report-pager button[data-page='1']").click();
        assertThat(rows).hasCount(1);
        assertThat(rows.first()).containsText("E2E Unmatched 25");
        page.locator("#krt-p4k-report-type").selectOption("CHANGED");
        assertThat(rows).hasCount(2);
        assertThat(rows.first()).containsText("item_E2E_Old");

        Locator keep = page.locator("tr[data-case-id='" + KEEP_CASE + "']");
        Locator hide = page.locator("tr[data-case-id='" + HIDE_CASE + "']");
        assertThat(keep).containsText("E2E Review Keep Ship");
        keep.locator("button[data-resolution='KEEP']").click();
        assertThat(keep).hasCount(0);
        hide.locator("button[data-resolution='HIDE']").click();
        assertThat(hide).hasCount(0);

        assertEquals(Boolean.TRUE, page.evaluate("() => window.__krtNoReload === true"));
        E2eSupport.navigate(page, STACK.baseUrl() + "/admin/p4k-import?lang=en");
        assertThat(page.locator("button[data-run-id='" + RUN_ID + "']")).isVisible();
        assertThat(page.locator("tr[data-case-id='" + KEEP_CASE + "']")).hasCount(0);
        assertThat(page.locator("tr[data-case-id='" + HIDE_CASE + "']")).hasCount(0);
      } finally {
        page.close();
      }
    }
  }
}
