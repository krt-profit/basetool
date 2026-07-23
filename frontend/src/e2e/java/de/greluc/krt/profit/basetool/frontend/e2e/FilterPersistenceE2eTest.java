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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the app-wide per-browser filter-selection persistence convention (REQ-UI-017, ADR-0120)
 * on three representative, data-independent surfaces — the sweep that closed the audit gap where
 * most filter pages reset their selection on every reload:
 *
 * <ul>
 *   <li><b>/refinery-orders</b> — a status-checkbox queue whose server default is the
 *       OPEN+IN_PROGRESS subset; adding COMPLETED must survive a reload (the pre-existing
 *       inconsistency: the sibling job-orders queue already persisted, the refinery queue did not).
 *   <li><b>/missions</b> — the {@code showPast} boolean toggle (search and date range stay
 *       deliberately unpersisted).
 *   <li><b>/materialboerse</b> — the offers-board sort selection, whose fragment swaps run {@code
 *       history:false}, so before the fix the choice was lost even on a plain F5.
 * </ul>
 *
 * <p>All three widgets are server-rendered unconditionally, so the test runs on an ephemeral stack
 * with no seeded domain data. Read-only and target-agnostic: it toggles client-side filter widgets
 * and asserts, mutating no server state. The actor is {@code test-admin}.
 */
@Tag("e2e")
class FilterPersistenceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser shared across the three page checks. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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

  /**
   * Logs in once and reuses the saved storage state across the test methods (each method still gets
   * its own context, so the per-page localStorage writes stay isolated per test).
   *
   * @return the path of the authenticated Playwright storage-state file
   */
  private static Path authenticated() {
    if (storageState == null) {
      storageState =
          E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    }
    return storageState;
  }

  /**
   * Opens a fresh authenticated context. The caller closes it via try-with-resources.
   *
   * @return a new browser context carrying the authenticated session
   */
  private static BrowserContext newContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(authenticated()));
  }

  /**
   * Refinery queue: adding COMPLETED to the default OPEN+IN_PROGRESS status subset and enabling
   * "only mine" must both come back checked after a reload (status lists whose server default is a
   * subset are stored verbatim per REQ-UI-017).
   */
  @Test
  void refineryStatusFilterSurvivesReload() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/refinery-orders");
        page.locator("#refinery-filter-form input[name='status'][value='COMPLETED']").check();
        page.locator("#refinery-filter-form input[name='onlyMine']").check();

        E2eSupport.navigate(page, STACK.baseUrl() + "/refinery-orders");
        assertThat(page.locator("#refinery-filter-form input[name='status'][value='COMPLETED']"))
            .isChecked();
        assertThat(page.locator("#refinery-filter-form input[name='status'][value='OPEN']"))
            .isChecked();
        assertThat(page.locator("#refinery-filter-form input[name='onlyMine']")).isChecked();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "filter-persistence-refinery");
        throw failure;
      }
    }
  }

  /**
   * Missions list: the {@code showPast} toggle survives a reload; the checkbox is restored and the
   * restored state drives the existing results re-fetch.
   */
  @Test
  void missionsShowPastSurvivesReload() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
        page.locator("input[name='showPast']").check();

        E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
        assertThat(page.locator("input[name='showPast']")).isChecked();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "filter-persistence-missions");
        throw failure;
      }
    }
  }

  /**
   * Materialbörse: the offers-board sort selection survives a reload even though the board's
   * fragment swaps run {@code history:false} (the pre-fix state was lost on a plain F5).
   */
  @Test
  void materialboerseSortSurvivesReload() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/materialboerse");
        page.locator("select[data-mb-sort]").selectOption("mat");

        E2eSupport.navigate(page, STACK.baseUrl() + "/materialboerse");
        assertThat(page.locator("select[data-mb-sort]")).hasValue("mat");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "filter-persistence-materialboerse");
        throw failure;
      }
    }
  }
}
