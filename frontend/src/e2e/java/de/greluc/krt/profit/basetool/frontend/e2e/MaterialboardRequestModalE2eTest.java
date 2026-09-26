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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E coverage for the Materialbörse Gesuche tabs (REQ-MARKET-015): the tabs relabel the CTAs, the
 * request modal opens visibly with its picker closed, the Material/Item radio switches the
 * combobox, and quality and quantity fields are present for both kinds.
 */
@Tag("e2e")
class MaterialboardRequestModalE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the actor's IRIDIUM membership. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    }
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
   * Switches to the Gesuche board and asserts the request create modal opens visibly with a working
   * kind toggle and the min-quality + quantity fields.
   */
  @Test
  void switchingToGesucheShowsRequestModal() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materialboerse");
        page.waitForLoadState();
        page.waitForFunction("() => typeof window.krtMaterialRequest === 'object'");

        assertThat(page.locator("#mg-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        page.locator("[data-mb-mode='requests'][data-mb-tab='alle']").first().click();

        assertThat(page.locator("[data-mg-open-request]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("[data-mb-open-release]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        page.locator("[data-mg-open-request]").first().click();
        assertThat(page.locator("#mg-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        assertThat(page.locator("#mg-modal [data-mg-material-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-item-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-picker-list]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        page.locator("#mg-modal [data-mg-picker-input]").click();
        assertThat(page.locator("#mg-modal [data-mg-picker-list]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        assertThat(page.locator("#mg-modal [data-mg-min-quality]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-qty]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        page.locator("#mg-modal [data-mg-kind-radio][value='ITEM']").check();
        assertThat(page.locator("#mg-modal [data-mg-item-block]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mg-modal [data-mg-material-block]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        page.locator("#mg-modal [data-mg-modal-close]").first().click();
        assertThat(page.locator("#mg-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-request-modal");
        throw failure;
      }
    }
  }
}
