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
import com.microsoft.playwright.Request;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E check of REQ-UI-016: the price-overview matrix filters persist in {@code localStorage} and
 * are restored on reload, before the first {@code /materials/overview/data} request.
 *
 * <p>Read-only, so it is safe against a shared deployment.
 */
@Tag("e2e")
class MaterialsOverviewFilterPersistenceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser shared across the (single) persistence check. */
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
   * Sets the boolean filters (plus a material subset where the catalogue has one), reloads, and
   * asserts the selection is restored into the widgets and carried by the first data request.
   */
  @Test
  void filterSelectionSurvivesReload() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/overview");
        E2eSupport.openFilterPanel(page);
        assertThat(page.locator("#tableContainer"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));

        boolean narrowMaterials = page.locator("input.matCheck").count() > 1;

        page.waitForResponse(
            response -> isFilteredDataUrl(response.url()),
            () -> {
              page.locator("#filterLoadingDock").check();
              page.locator("#filterAutoLoad").check();
              if (narrowMaterials) {
                page.locator("#materialHeader").click();
                page.locator("input.matCheck").first().uncheck();
              }
            });

        page.waitForTimeout(500);

        Request initialFetch =
            page.waitForRequest(
                request -> isFilteredDataUrl(request.url()),
                () -> E2eSupport.navigate(page, baseUrl + "/materials/overview"));
        E2eSupport.openFilterPanel(page);
        if (narrowMaterials && !initialFetch.url().contains("materials=")) {
          throw new AssertionError(
              "initial post-reload data request must carry the restored material subset, but"
                  + " was: "
                  + initialFetch.url());
        }

        assertThat(page.locator("#filterLoadingDock")).isChecked();
        assertThat(page.locator("#filterAutoLoad")).isChecked();
        if (narrowMaterials) {
          Locator firstMaterial = page.locator("input.matCheck").first();
          assertThat(firstMaterial).not().isChecked();
          assertThat(page.locator("#matAll")).not().isChecked();
        }
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-overview-filter-persistence");
        throw failure;
      }
    }
  }

  /**
   * Matches a {@code /materials/overview/data} request that carries both boolean filters — the
   * signature of the selection this test makes, distinguishing it from the unfiltered initial load.
   *
   * @param url the request or response URL to inspect
   * @return whether the URL is a data fetch carrying the test's boolean filter selection
   */
  private static boolean isFilteredDataUrl(String url) {
    return url.contains("/materials/overview/data")
        && url.contains("loadingDock=true")
        && url.contains("autoLoad=true");
  }
}
