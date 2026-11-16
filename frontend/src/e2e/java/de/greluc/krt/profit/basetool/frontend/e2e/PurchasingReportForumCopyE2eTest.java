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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The purchasing report's forum text reaches the clipboard (REQ-PUR-023, REQ-SEC-064): the copy
 * button's Clipboard API write succeeds, because the frontend's {@code Permissions-Policy} allows
 * {@code clipboard-write} for its own origin.
 */
@Tag("e2e")
class PurchasingReportForumCopyE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String CHROMIUM = "chromium";

  private static final double UI_TIMEOUT_MS = 10_000;

  private static final String CLEAR_TOASTS =
      "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }";

  private static final Pattern ERROR_TOAST = Pattern.compile("\\berror-toast\\b");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and switches purchasing on, so the report and its forum text exist. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    }
    seeder.preparePurchasing(USERNAME, PASSWORD, UUID.randomUUID().toString().substring(0, 8));
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
   * Opens the forum-text dialog of the report and copies the text: the one toast that follows is
   * the success toast, not the clipboard error.
   */
  @Test
  void theForumTextIsCopiedToTheClipboard() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    Browser.NewContextOptions options =
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState);
    if (CHROMIUM.equals(browser.browserType().name())) {
      // The headless Chromium shell denies clipboard-write by default; desktop Chrome grants it to
      // a
      // focused page. Firefox and WebKit know no such permission and rely on the click's
      // activation.
      options.setPermissions(List.of("clipboard-write"));
    }
    try (BrowserContext context = browser.newContext(options)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/purchasing/report");
        page.waitForLoadState();

        page.locator("[data-testid='purchasing-report-forum']").click();
        assertThat(page.locator("[data-testid='purchasing-forum-text']"))
            .hasValue(
                Pattern.compile("\\S"),
                new LocatorAssertions.HasValueOptions().setTimeout(UI_TIMEOUT_MS));

        page.evaluate(CLEAR_TOASTS);
        page.locator("[data-testid='purchasing-forum-copy']").click();

        Locator toast = page.locator(".notification-toast");
        assertThat(toast)
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(UI_TIMEOUT_MS));
        assertThat(toast).not().hasClass(ERROR_TOAST);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "purchasing-report-forum-copy");
        throw failure;
      }
    }
  }
}
