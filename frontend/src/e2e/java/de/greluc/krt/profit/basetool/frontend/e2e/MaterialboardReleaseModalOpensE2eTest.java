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
 * E2E regression test: the Materialbörse release modal must become visible when opened and hidden
 * when dismissed (REQ-MARKET-002/007).
 *
 * <p>Needs a real browser because visibility depends on the {@code .krt-modal-overlay} CSS.
 */
@Tag("e2e")
class MaterialboardReleaseModalOpensE2eTest {

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
   * Asserts the release modal is hidden on load, visible after clicking "Material anbieten", and
   * hidden again after closing.
   *
   * <p>Also asserts the picker dropdown stays closed on open and opens only on a click into the
   * picker input.
   */
  @Test
  void clickingOfferCtaShowsReleaseModal() {
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
        page.waitForFunction("() => typeof window.krtMaterialRelease === 'object'");

        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));

        page.locator("[data-mb-open-release]").first().click();

        assertThat(page.locator("#mb-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("#mb-modal [data-mb-picker]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        assertThat(page.locator("#mb-modal [data-mb-picker-list]"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
        page.locator("#mb-modal [data-mb-picker-input]").click();
        assertThat(page.locator("#mb-modal [data-mb-picker-list]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        page.locator("#mb-modal [data-mb-modal-close]").first().click();
        assertThat(page.locator("#mb-modal"))
            .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materialboard-release-modal");
        throw failure;
      }
    }
  }
}
