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
 * Verifies per-browser filter persistence across reloads (REQ-UI-017, ADR-0120) on {@code
 * /refinery-orders} (status checkboxes), {@code /missions} ({@code showPast}) and {@code
 * /materialboerse} (offer sort).
 *
 * <p>Needs no seeded data and mutates no server state; runs as {@code test-admin}.
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
   * Logs in once and returns the saved storage state, reused by every test method in its own
   * context.
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
   * Refinery queue: COMPLETED added to the default status subset and "only mine" both stay checked
   * after a reload.
   */
  @Test
  void refineryStatusFilterSurvivesReload() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/refinery-orders");
        E2eSupport.openFilterPanel(page);
        page.locator("#refinery-filter-form input[name='status'][value='COMPLETED']").check();
        page.locator("#refinery-filter-form input[name='onlyMine']").check();

        E2eSupport.navigate(page, STACK.baseUrl() + "/refinery-orders");
        E2eSupport.openFilterPanel(page);
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
        E2eSupport.openFilterPanel(page);
        page.locator("input[name='showPast']").check();

        E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
        E2eSupport.openFilterPanel(page);
        assertThat(page.locator("input[name='showPast']")).isChecked();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "filter-persistence-missions");
        throw failure;
      }
    }
  }

  /**
   * Materialbörse: the offers-board sort selection survives a reload, although the board's fragment
   * swaps run {@code history:false}.
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
