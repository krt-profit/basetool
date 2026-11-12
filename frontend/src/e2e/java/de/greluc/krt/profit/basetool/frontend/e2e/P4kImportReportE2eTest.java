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
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The P4K admin page on the isolated stack (REQ-P4K-015, REQ-P4K-016): a seeded run is listed, its
 * report is filtered and paged, and the review workspace supports single, grouped and link actions.
 * Lists and previews update in place.
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

  private static final String BATCH_CASE = "0e2e0000-0000-4000-8000-000000009433";
  private static final String OTHER_CASE = "0e2e0000-0000-4000-8000-000000009434";
  private static final String LINK_CASE = "0e2e0000-0000-4000-8000-000000009435";

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

  /** Exercises report paging, review grouping and confirmed actions in place. */
  @Test
  void theReportAndReviewWorkspaceUpdateInPlace() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setLocale("en-GB")
                .setStorageStatePath(adminSession))) {
      Page page = context.newPage();
      try {
        navigateWithRunList(page);
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

        reviewGroupAndCandidate(page);

        assertEquals(Boolean.TRUE, page.evaluate("() => window.__krtNoReload === true"));
        navigateWithRunList(page);
        assertThat(page.locator("#krt-p4k-review-kind")).hasValue("SHIP");
        assertThat(page.locator("#krt-p4k-review-reason")).hasValue("GUID_CONFLICT");
        assertThat(page.locator("button[data-run-id='" + RUN_ID + "']")).isVisible();
        assertThat(page.locator("tr[data-case-id='" + KEEP_CASE + "']")).hasCount(0);
        assertThat(page.locator("tr[data-case-id='" + HIDE_CASE + "']")).hasCount(0);
      } finally {
        page.close();
      }
    }
  }

  private static void navigateWithRunList(Page page) {
    Response response =
        page.waitForResponse(
            candidate ->
                candidate.request().method().equals("GET")
                    && candidate.url().endsWith("/admin/p4k-import/runs?page=0"),
            () -> E2eSupport.navigate(page, STACK.baseUrl() + "/admin/p4k-import?lang=en"));
    assertEquals(200, response.status());
  }

  private static void reviewGroupAndCandidate(Page page) {
    Locator reason = page.locator("#krt-p4k-review-reason");
    Locator kind = page.locator("#krt-p4k-review-kind");
    reason.selectOption("GUID_CONFLICT");
    kind.selectOption("ITEM");
    Locator group =
        page.getByTestId("p4k-review-group").filter(new Locator.FilterOptions().setHasText("Door"));
    assertThat(group).containsText("2 open cases");
    group.locator("button[data-review-group]").click();

    Locator selected = page.locator("tr[data-case-id='" + BATCH_CASE + "']");
    Locator unselected = page.locator("tr[data-case-id='" + OTHER_CASE + "']");
    selected.locator("input[data-review-select]").check();
    page.locator("button[data-review-batch-action='KEEP']").click();
    Locator preview = page.getByTestId("p4k-review-batch-preview");
    assertThat(preview).isVisible();
    assertThat(selected).isVisible();
    assertThat(unselected).isVisible();
    preview.locator("button[data-review-batch-confirm]").click();
    assertThat(selected).hasCount(0);
    assertThat(unselected).isVisible();

    kind.selectOption("SHIP");
    page.setViewportSize(390, 844);
    Locator link = page.locator("tr[data-case-id='" + LINK_CASE + "']");
    link.locator("button[data-review-detail]").click();
    Locator candidate = page.getByTestId("p4k-review-candidate");
    assertThat(candidate).containsText("Same SC Wiki UUID");
    assertThat(candidate).containsText("E2E_WIKI_LINK_SHIP");
    assertEquals(
        Boolean.TRUE,
        page.locator("#krt-p4k-review-detail")
            .evaluate("(section) => section.scrollWidth <= section.clientWidth"));
    candidate.locator("button[data-review-preview]").click();
    Locator comparison = page.getByTestId("p4k-review-preview");
    assertThat(comparison).containsText("Link P4K record");
    assertThat(link).isVisible();
    comparison.locator("button[data-review-confirm]").click();
    assertThat(link).hasCount(0);
  }
}
