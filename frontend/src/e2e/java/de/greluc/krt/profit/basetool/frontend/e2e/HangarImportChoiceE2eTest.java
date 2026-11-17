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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.SelectOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Imports a ship list whose name fits two ship types once their manufacturer word is dropped,
 * chooses one in the result dialog and imports the choice in place (REQ-HANGAR-019).
 */
@Tag("e2e")
class HangarImportChoiceE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** A member whose hangar no other hangar test counts. */
  private static final String USERNAME = "test-order-reader";

  /** The member's throwaway password. */
  private static final String PASSWORD = "test-order-reader-pw";

  /** The option the member chooses, seeded by {@code hangar-import-choice-seed.sql}. */
  private static final String CHOSEN = "E2E Choice Hornet Mk II";

  /** A CCU Game Fleetview export with two entries of the ambiguous name. */
  private static final String FLEETVIEW =
      "[{\"name\":\"Choice Hornet\",\"shipname\":\"\",\"type\":\"ship\"},"
          + "{\"name\":\"Choice Hornet\",\"shipname\":\"\",\"type\":\"ship\"}]";

  private static Playwright playwright;
  private static Browser browser;

  /** Seeds the two ship types and launches the browser. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "The choice fixture needs the isolated E2E stack");
    new BackendSeeder().seedSql("/hangar-import-choice-seed.sql");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
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
   * Offers the ambiguous name with its two ships, imports both entries as the chosen one and shows
   * them in the list after closing the dialog, without a page reload (REQ-FE-001).
   */
  @Test
  void anAmbiguousNameIsChosenInTheResultDialogAndImportedInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar");
        page.evaluate("window.__krtNoReload = true;");

        page.waitForResponse(
            r -> r.url().contains("/hangar/import/ships") && "POST".equals(r.request().method()),
            () ->
                page.getByTestId("hangar-import-file")
                    .setInputFiles(
                        new FilePayload(
                            "fleetview.json",
                            "application/json",
                            FLEETVIEW.getBytes(StandardCharsets.UTF_8))));

        Locator ambiguous = page.getByTestId("hangar-import-ambiguous");
        assertThat(ambiguous).isVisible();
        assertThat(ambiguous).containsText("Choice Hornet");
        assertThat(page.locator("#import-res-imported")).hasText("0");
        assertThat(page.locator("#import-res-ambiguous")).hasText("1");
        assertThat(ambiguous.locator("select option")).hasCount(3);
        Locator apply = page.getByTestId("hangar-import-apply");
        assertThat(apply).isDisabled();

        ambiguous.locator("select").selectOption(new SelectOption().setLabel(CHOSEN));
        assertThat(apply).isEnabled();
        page.waitForResponse(
            r -> r.url().contains("/hangar/import/ships") && "POST".equals(r.request().method()),
            apply::click);

        assertThat(page.locator("#import-res-imported")).hasText("2");
        assertThat(page.locator("#import-res-ambiguous")).hasText("0");
        assertThat(ambiguous).isHidden();
        assertThat(apply).isHidden();

        page.locator("#import-result-close").click();
        assertThat(
                page.getByTestId("hangar-ship-row")
                    .filter(new Locator.FilterOptions().setHasText(CHOSEN)))
            .hasCount(2);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "importing the choice must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "hangar-import-choice");
        throw failure;
      }
    }
  }
}
