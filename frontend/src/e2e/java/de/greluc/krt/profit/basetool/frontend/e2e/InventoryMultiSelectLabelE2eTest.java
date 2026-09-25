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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The Lager multi-select filters summarise their selection the same way on both pages (owner
 * decision 2026-09-23, REQ-INV-037): nothing or every option ticked reads the "all" label, one
 * ticked option reads its own name, anything in between reads "N ausgewählt". Before, "Mein Lager"
 * said "N ausgewählt" with every option ticked while "Globales Lager" said "Alle".
 *
 * <p>The helper now lives once in {@code inventory-common.js}. There is no JavaScript unit harness
 * in this repository, so the behaviour is guarded here, in a browser, on both pages — a page that
 * stopped delegating to the shared helper would keep its markup intact and only fail here. The
 * labels are read from the header's own {@code data-all} / {@code data-selected} attributes so the
 * assertions hold in either locale.
 */
@Tag("e2e")
class InventoryMultiSelectLabelE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The grouped Lager render is slow on WebKit under CI load. */
  private static final double RENDER_TIMEOUT_MS = 20_000;

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, gives the actor its IRIDIUM membership and
   * three catalogue materials, so the material filter has enough options for every state.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      for (String name : new String[] {"E2E Multi A", "E2E Multi B", "E2E Multi C"}) {
        seeder.ensureRefineryMaterial(USERNAME, PASSWORD, name);
      }
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

  /** "Mein Lager" material filter. */
  @Test
  void myLagerMaterialFilterSummarisesTheSelection() {
    walk("/inventory/my", "#myFilterPanel", "my");
  }

  /** "Globales Lager" material filter: the same states, the same words. */
  @Test
  void globalLagerMaterialFilterSummarisesTheSelection() {
    walk("/inventory/all", "#globalFilterPanel", "global");
  }

  /**
   * Walks the material filter through every summary state on one page.
   *
   * @param path the Lager page
   * @param panel the page's filter panel selector
   * @param dumpName the failure-dump name suffix
   */
  private static void walk(String path, String panel, String dumpName) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + path);
        page.waitForLoadState();
        Locator toggle = page.locator("[data-testid='lager-filter-toggle']");
        assertThat(toggle)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(RENDER_TIMEOUT_MS));
        if (!page.locator(panel).isVisible()) {
          toggle.click();
        }
        Locator header = page.locator("#materialHeader");
        Locator text = page.locator("#materialSelectedText");
        Locator options = page.locator("#materialOptions input.matCheck");
        String all = header.getAttribute("data-all");
        String selected = header.getAttribute("data-selected");
        int total = options.count();
        assertTrue(total >= 3, "the material filter needs three options, found " + total);

        header.click();
        assertThat(page.locator("#materialOptions")).hasClass(Pattern.compile("open"));

        page.locator("#matAll").check();
        assertThat(text).hasText(all);

        options.nth(0).uncheck();
        assertThat(text).hasText((total - 1) + " " + selected);
        assertThat(page.locator("#matAll")).not().isChecked();

        options.nth(0).check();
        assertThat(text).hasText(all);
        assertThat(page.locator("#matAll")).isChecked();

        page.locator("#matAll").uncheck();
        assertThat(text).hasText(all);

        options.nth(1).check();
        String name = options.nth(1).locator("xpath=preceding-sibling::span[1]").innerText();
        assertThat(text).hasText(name);

        options.nth(2).check();
        assertThat(text).hasText("2 " + selected);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-multi-select-label-" + dumpName);
        throw failure;
      }
    }
  }
}
