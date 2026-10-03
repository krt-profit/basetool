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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the navigation of REQ-UI-026 in a browser: the drawer's menu filter and its hand-over to
 * the quick access, the {@code Ctrl+K} palette, the administration mode on admin pages and the
 * phone tab bar with its menu sheet.
 */
@Tag("e2e")
class NavigationE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated administrator session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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
   * The menu filter narrows the drawer to matching links, says so when nothing matches, and hands
   * its text to the quick access, whose first hit opens on Enter.
   */
  @Test
  void drawerFilterNarrowsTheMenuAndHandsOverToTheQuickAccess() {
    run(
        1440,
        900,
        "nav-filter",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.locator("#hamburger").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("nav-filter")).isFocused();

          page.getByTestId("nav-filter").fill("zz-no-such-entry");
          assertThat(page.locator(".nav-filter-empty")).isVisible();

          String hangar = page.getByTestId("nav-hangar").textContent().trim();
          page.getByTestId("nav-filter").fill(hangar);
          assertThat(page.getByTestId("nav-hangar")).isVisible();
          assertThat(page.getByTestId("nav-missions")).isHidden();
          assertThat(page.locator(".nav-filter-empty")).isHidden();

          page.getByTestId("nav-filter-to-palette").click();
          assertThat(page.locator("#sidebar")).not().hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("palette-input")).isFocused();
          assertThat(page.getByTestId("palette-input")).hasValue(hangar);
          page.getByTestId("palette-input").press("Enter");
          page.waitForURL(Pattern.compile(".*/hangar$"));
        });
  }

  /**
   * {@code Ctrl+K} opens the quick access from anywhere, the arrow keys move the selection and
   * Escape closes it again.
   */
  @Test
  void shortcutOpensTheQuickAccessAndEscapeClosesIt() {
    run(
        1440,
        900,
        "nav-palette",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.keyboard().press("Control+k");
          assertThat(page.locator("#nav-palette")).isVisible();
          assertThat(page.getByTestId("palette-input")).isFocused();
          assertThat(page.locator("#nav-palette .palette-row").first())
              .hasClass(Pattern.compile("\\bis-selected\\b"));

          String orgChart = page.getByTestId("nav-org-chart").textContent().trim();
          page.getByTestId("palette-input").fill(orgChart);
          assertThat(page.locator("#nav-palette .palette-row[data-href='/org-chart']")).isVisible();
          page.keyboard().press("Escape");
          assertThat(page.locator("#nav-palette")).isHidden();
        });
  }

  /**
   * An administration page opens the drawer in administration mode, and the main-menu control
   * switches back to the main groups.
   */
  @Test
  void adminPageOpensTheDrawerInAdministrationMode() {
    run(
        1440,
        900,
        "nav-admin-mode",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/audit-log");
          page.locator("#hamburger").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-admin-mode\\b"));
          assertThat(page.getByTestId("nav-admin-audit-log")).isVisible();
          assertThat(page.getByTestId("nav-missions")).isHidden();

          page.getByTestId("nav-admin-back").click();
          assertThat(page.getByTestId("nav-missions")).isVisible();
          assertThat(page.getByTestId("nav-admin-audit-log")).isHidden();
          assertThat(page.getByTestId("nav-admin-toggle")).isVisible();
        });
  }

  /**
   * On a phone the tab bar marks the current area, the footer gives way to it, and the menu tab
   * opens the sheet with the user row.
   */
  @Test
  void phoneTabBarOpensTheMenuSheet() {
    run(
        390,
        844,
        "nav-phone",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(page.getByTestId("mobile-tab-missions"))
              .hasClass(Pattern.compile("\\bis-active\\b"));
          assertThat(page.locator(".krt-footer")).isHidden();
          assertThat(page.locator("#hamburger")).isHidden();

          page.getByTestId("mobile-tab-menu").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("mobile-tab-menu")).hasAttribute("aria-expanded", "true");
          assertThat(page.getByTestId("nav-user-toggle")).isVisible();

          page.getByTestId("mobile-tab-menu").click();
          assertThat(page.locator("#sidebar")).not().hasClass(Pattern.compile("\\bopen\\b"));
        });
  }

  /**
   * Runs one scenario in a fresh authenticated context of the given viewport, dumping the page when
   * it fails.
   *
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @param label the dump's file label
   * @param scenario the steps and assertions
   */
  private static void run(int width, int height, String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(width, height))) {
      Page page = context.newPage();
      try {
        scenario.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
