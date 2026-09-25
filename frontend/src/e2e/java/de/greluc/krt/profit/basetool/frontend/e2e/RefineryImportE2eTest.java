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
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow (UC-24): upload a {@code RefineryExtract} JSON on the refinery create page, get
 * the form pre-filled from the backend draft (#434/#435), resolve the one intentionally unmatched
 * row via its suggestion chip, save, and verify the order appears in the list.
 *
 * <p>This is the repo's first file-upload e2e: {@code setInputFiles} targets the hidden file input
 * behind the styled import button; the change handler submits the multipart form to {@code
 * /refinery-orders/import}, which relays to the Phase 1 backend endpoint and — via the #591 AJAX
 * twin — swaps the pre-filled create-form fragment back in place with no full reload. The fixture's
 * second row ("E2E IMPRT MATERAIL") is misspelled on purpose (3 edits over 19 characters ≈ 0.84
 * similarity) so it stays below the fuzzy accept threshold and surfaces only as a ranked
 * suggestion.
 */
@Tag("e2e")
class RefineryImportE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static com.microsoft.playwright.Playwright playwright;
  private static Browser browser;
  private static String materialId;

  /** Launches the browser and, for the ephemeral stack, seeds the membership + input material. */
  @BeforeAll
  static void setUp() {
    playwright = com.microsoft.playwright.Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      materialId =
          seeder.ensureRefineryMaterial(
              USERNAME, PASSWORD, E2eStackExtension.PICKER_MATERIAL_IMPORT);
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
   * Uploads the extract fixture, verifies the pre-fill + review flags, completes the unmatched row
   * via its suggestion chip and saves the order.
   */
  @Test
  void importsAnExtractAndSavesTheReviewedOrder() throws URISyntaxException {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    Path fixture =
        Path.of(RefineryImportE2eTest.class.getResource("/refinery-extract-e2e.json").toURI());
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create");

        page.evaluate("() => { window.__krtNoReload = true; }");
        page.getByTestId("refinery-import-file").setInputFiles(fixture);

        assertThat(page.getByTestId("refinery-import-banner"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the screenshot-import must swap in place — no full-page reload cleared the window"
                + " marker");
        assertThat(
                page.locator("#refineryImportFormContainer .datetime-split-group .date-part")
                    .first())
            .not()
            .hasValue("");
        assertThat(page.locator("#inputMaterialId_0")).hasValue(materialId);
        assertThat(page.locator("#inputQuantity_0")).hasValue("250");
        assertThat(page.locator("#outputQuantity_0")).hasValue("120");
        assertThat(page.locator("#quality_0")).hasValue("618");
        assertThat(page.locator("#startedAt")).hasValue("2026-06-01T19:39:01Z");
        assertThat(page.getByTestId("refinery-import-row-flags-1")).isVisible();
        assertThat(page.locator("#inputMaterialId_1")).hasValue("");

        page.getByTestId("refinery-import-suggestion-1").first().click();
        assertThat(page.locator("#inputMaterialId_1")).hasValue(materialId);

        E2eSupport.awaitFormPost(page, () -> page.getByTestId("refinery-submit").click());
        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        assertThat(page.getByTestId("refinery-order-row").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-import");
        throw failure;
      }
    }
  }
}
