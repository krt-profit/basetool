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
 * Browser flow for the collapsible Lager filter panels (REQ-INV-037) — "Mein Lager" and the shared
 * "Globales Lager", which run the same panel off two page-local scripts and two separate storage
 * keys.
 *
 * <p>The collapse exists entirely in the browser: the server always renders the panel expanded so a
 * client without JavaScript keeps its filters, and {@code inventory-my.js} / {@code
 * inventory-admin.js} apply the stored preference on load. Everything worth guarding is therefore
 * invisible to a MockMvc test — the rendering contract is pinned by {@code
 * InventoryPageControllerMvcTest}, the behaviour by this test.
 *
 * <p>Two failure modes motivate it. A broken {@code data-trigger} wiring turns the toggle into a
 * dead button that still looks right in the rendered HTML. And a collapse that forgets to surface
 * the active-filter count produces the one state the requirement forbids: a narrowed table whose
 * reason is folded out of sight.
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
   * Walks the whole preference lifecycle in one browser context, because each step's meaning
   * depends on the one before it: the unfiltered first visit starts collapsed (no preference
   * stored, nothing filtered), an explicit expand survives a reload, and an explicit collapse
   * survives one too — the last step being the one that would regress if the toggle wrote its state
   * only into the DOM.
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

        // Fresh context => no stored preference, and an untouched Lager has no active filter, so
        // the default applies: collapsed. The toggle itself must of course be visible, or the
        // filters would be unreachable rather than merely tidied away.
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#myFilterPanel")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#myFilterPanel")).isVisible();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .hasAttribute("aria-expanded", "true");

        // The server always ships the panel expanded, so an "expanded after reload" assertion
        // would pass even with persistence entirely broken. It is the collapsed leg below that
        // carries the weight; this one only proves the reload did not throw the preference away.
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

        // Nothing filtered yet: the chip must be absent rather than showing a zero.
        assertThat(page.locator("[data-testid='lager-filter-count']")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#myFilterPanel")).isVisible();
        page.locator("#personalOnly").check();

        assertThat(page.locator("[data-testid='lager-filter-count']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#myFilterCountValue")).hasText("1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-filter-count-chip");
        throw failure;
      }
    }
  }

  /**
   * The same lifecycle on the shared "Globales Lager". It is a separate page module with its own
   * storage key, so nothing about a working "Mein Lager" panel proves this one is wired: an
   * unregistered {@code data-trigger} or a preference written under the wrong key both leave the
   * markup asserted by the MockMvc test perfectly intact.
   *
   * <p>The chip is checked in the same context at the end, after the collapse legs, so the
   * "unfiltered first visit starts collapsed" leg still sees an untouched Lager. The
   * minimum-quality select drives it because it needs no seeded stock — it renders on every Lager,
   * empty or not.
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

        // Fresh context => no stored preference, and an untouched Lager has no active filter, so
        // the default applies: collapsed, with no chip rather than a zero.
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#globalFilterPanel")).isHidden();
        assertThat(page.locator("[data-testid='lager-filter-count']")).isHidden();

        page.locator("[data-testid='lager-filter-toggle']").click();
        assertThat(page.locator("#globalFilterPanel")).isVisible();
        assertThat(page.locator("[data-testid='lager-filter-toggle']"))
            .hasAttribute("aria-expanded", "true");

        // The collapsed leg is the one that carries the weight: the server always ships the panel
        // expanded, so only a collapse surviving a reload proves the preference was persisted.
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

        assertThat(page.locator("[data-testid='lager-filter-count']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        assertThat(page.locator("#globalFilterCountValue")).hasText("1");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-global-filter-panel-collapse");
        throw failure;
      }
    }
  }
}
