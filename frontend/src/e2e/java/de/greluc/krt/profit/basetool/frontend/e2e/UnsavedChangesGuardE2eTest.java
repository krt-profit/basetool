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
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the unsaved-changes guard (REQ-FE-024): a filter or search never arms it, an edit in a
 * data form still does.
 */
@Tag("e2e")
class UnsavedChangesGuardE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String GUARD_MODAL = "#unsaved-changes-modal";
  private static final String BRAND_LINK = "header a.brand";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser, ensures the admin's membership and captures one admin session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    }
    storageState =
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

  /**
   * Switches the terms-consent filter, which re-renders the list in place, and then leaves the page
   * through a link without the guard intervening.
   */
  @Test
  void changingTheTermsFilterDoesNotArmTheGuard() {
    withPage(
        "unsaved-guard-terms",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/terms");
          assertThat(page.locator("#admin-terms-filter-form button[type='submit']")).hasCount(0);

          page.waitForResponse(
              r -> r.url().contains("/admin/terms") && r.url().contains("filter=ACCEPTED"),
              () ->
                  page.locator("#admin-terms-filter-form [data-testid='segment-filter-accepted']")
                      .click());

          leaveThroughBrandLinkUnguarded(page, "/admin/terms");
        });
  }

  /** Types a hangar search, which filters in place, and then leaves the page unguarded. */
  @Test
  void typingAHangarSearchDoesNotArmTheGuard() {
    withPage(
        "unsaved-guard-hangar",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/hangar?search=unsaved-guard");

          page.waitForResponse(
              r -> r.url().contains("/hangar") && r.url().contains("fragment=results"),
              () -> page.locator("#hangar-ship-filter").fill("unsaved-guard-xyz"));

          leaveThroughBrandLinkUnguarded(page, "/hangar");
        });
  }

  /** Edits the announcement text without saving and asserts the guard still stops the link. */
  @Test
  void editingADataFormStillArmsTheGuard() {
    withPage(
        "unsaved-guard-data-form",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/announcement");

          page.locator("#info-edit-content").fill("unsaved guard probe");
          page.locator(BRAND_LINK).click();

          assertThat(page.locator(GUARD_MODAL)).isVisible();
          page.locator("#unsaved-stay-btn").click();
          assertThat(page.locator(GUARD_MODAL)).isHidden();
        });
  }

  /**
   * Clicks the header's brand link and asserts the guard stays closed and the page is left.
   *
   * @param page the page to leave
   * @param pathFragment a path segment only the page being left carries
   */
  private static void leaveThroughBrandLinkUnguarded(Page page, String pathFragment) {
    page.locator(BRAND_LINK).click();
    assertThat(page.locator(GUARD_MODAL)).isHidden();
    page.waitForURL(url -> !url.contains(pathFragment));
  }

  /**
   * Runs the given steps on a fresh authenticated page and dumps it when they fail.
   *
   * @param label the dump label
   * @param steps the steps to run
   */
  private static void withPage(String label, Consumer<Page> steps) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        steps.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
