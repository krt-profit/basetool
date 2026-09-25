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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E flow that walks a MATERIAL job order from {@code OPEN} through {@code IN_PROGRESS} to {@code
 * COMPLETED} on the detail page and verifies each transition persists.
 *
 * <p>The terminal transition confirms the warning modal first. Status is read back from {@code GET
 * /api/v1/orders/{id}}, not from the reloaded dropdown. Runs as {@code test-admin}.
 */
@Tag("e2e")
class JobOrderStatusE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String jobOrderId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership, a job-order
   * material and a fresh {@code OPEN} MATERIAL order to transition.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Status Material");
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Status Order", materialId, 650, 100.0);
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
   * Selects {@code IN_PROGRESS} (immediate AJAX post), then {@code COMPLETED} (confirmed through
   * the terminal-transition warning modal), and asserts the persisted status after each via a
   * backend read-back.
   */
  @Test
  void walksAnOrderFromOpenToInProgressToCompleted() {
    String baseUrl = STACK.baseUrl();
    String detailUrl = baseUrl + "/orders/" + jobOrderId;
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, detailUrl);

        page.waitForResponse(
            response ->
                response.url().contains("/status") && "POST".equals(response.request().method()),
            () -> page.locator("#status-select").selectOption("IN_PROGRESS"));
        assertEquals(
            "IN_PROGRESS", persistedStatus(), "status persists after the in-progress change");

        E2eSupport.navigate(page, detailUrl);

        page.locator("#status-select").selectOption("COMPLETED");
        assertThat(page.locator("#status-warning-modal")).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/status") && "POST".equals(response.request().method()),
            () -> page.locator("[data-trigger='od-confirm-status']").click());
        assertEquals("COMPLETED", persistedStatus(), "status persists after the completing change");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-status");
        throw failure;
      }
    }
  }

  /**
   * Reads the order's persisted status straight from the backend ({@code GET /api/v1/orders/{id}}),
   * so the assertion does not race the client's post-change page reload.
   *
   * @return the order's current {@code status} value
   */
  private static String persistedStatus() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId);
    return JsonParser.parseString(body).getAsJsonObject().get("status").getAsString();
  }
}
