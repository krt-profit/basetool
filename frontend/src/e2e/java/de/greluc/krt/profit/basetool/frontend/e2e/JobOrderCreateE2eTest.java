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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
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
 * Creates a job order through the UI, enters its returned detail and finds it in the shared list.
 * IRIDIUM is made profit-eligible in {@link E2eStackExtension}; {@link BackendSeeder} seeds the
 * membership and a job-order material in {@link #setUp()}.
 */
@Tag("e2e")
class JobOrderCreateE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and seeds the membership and at least one {@code isJobOrder} material.
   * Because the material list is cached, the test uses whatever material the dropdown offers.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Job Material");
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
   * Fills and submits the job-order create form (responsible org-unit, requesting org-unit, contact
   * handle and one material row), enters its returned identity and finds that exact order in the
   * list.
   */
  @Test
  void createsAJobOrderThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        page.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#requestingOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#handle").fill("E2E Contact");
        E2eSupport.selectComboboxFirstOption(page.getByTestId("order-material-select"));
        page.getByTestId("order-material-amount").fill("100");
        var created =
            page.waitForResponse(
                response ->
                    response.url().endsWith("/orders/create")
                        && "POST".equals(response.request().method()),
                () -> page.getByTestId("order-submit").click());
        assertEquals(200, created.status());
        String target =
            JsonParser.parseString(created.text()).getAsJsonObject().get("targetUrl").getAsString();
        assertTrue(target.matches("/orders/[0-9a-f-]{36}"));
        page.waitForURL(baseUrl + target);
        assertThat(page.locator("#order-header-results")).containsText("E2E Contact");

        E2eSupport.navigate(page, baseUrl + "/orders");
        String createdId = target.substring("/orders/".length());
        assertThat(page.locator("[data-testid='order-row'][data-id='" + createdId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-create");
        throw failure;
      }
    }
  }
}
