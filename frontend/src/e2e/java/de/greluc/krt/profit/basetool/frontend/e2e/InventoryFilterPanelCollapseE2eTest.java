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
 * Verifies the collapsible Lager filter panels of "Mein Lager" and "Globales Lager" in a browser
 * (REQ-INV-037): the toggle works, the choice persists, and a collapsed panel shows the
 * active-filter count.
 */
@Tag("e2e")
class InventoryFilterPanelCollapseE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * The grouped Lager render is slow on WebKit under CI load, so give it more than the 5 s default.
   */
  private static final double RENDER_TIMEOUT_MS = 20_000;

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, gives the actor its IRIDIUM membership. */
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
   * On "Mein Lager", an unfiltered first visit starts collapsed, and both an explicit expand and an
   * explicit collapse survive a reload.
   */
  @Test
  void filterPanelCollapsesAndRemembersTheChoiceAcrossReloads() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/inventory/my");
        page.waitForLoadState();

        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#myFilterPanel")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#myFilterPanel")).isVisible();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .hasAttribute("aria-expanded", "true");

        page.reload();
        page.waitForLoadState();
        assertThat(page.locator("#myFilterPanel"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#myFilterPanel")).isHidden();
        page.reload();
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#myFilterPanel")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-filter-panel-collapse");
        throw failure;
      }
    }
  }

  /**
   * Ticking a filter must light up the count chip on the toggle. This is the guarantee that keeps a
   * collapsed panel honest — without it the page can reach a state where the table is narrowed and
   * nothing on screen says so. The personal-entries checkbox is used because it needs no seeded
   * stock: it renders on every Lager, empty or not.
   */
  @Test
  void activeFilterCountAppearsOnTheToggle() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/inventory/my");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));

        assertThat(page.locator(".filter-toggle [data-filter-count]")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#myFilterPanel")).isVisible();
        page.locator("#personalOnly").check();

        assertThat(page.locator(".filter-toggle [data-filter-count]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-filter-count-chip");
        throw failure;
      }
    }
  }

  /**
   * On "Globales Lager", the same collapse lifecycle holds under its own storage key, and a
   * collapsed panel shows the active-filter count chip.
   */
  @Test
  void globalFilterPanelCollapsesRemembersTheChoiceAndCountsActiveFilters() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/inventory/all");
        page.waitForLoadState();

        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#globalFilterPanel")).isHidden();
        assertThat(page.locator(".filter-toggle [data-filter-count]")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#globalFilterPanel")).isVisible();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .hasAttribute("aria-expanded", "true");

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#globalFilterPanel")).isHidden();
        page.reload();
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#globalFilterPanel")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#globalFilterPanel")).isVisible();
        page.locator("#minQuality").selectOption("500");

        assertThat(page.locator(".filter-toggle [data-filter-count]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator(".filter-toggle [data-filter-count-value]")).hasText("1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-global-filter-panel-collapse");
        throw failure;
      }
    }
  }
}
