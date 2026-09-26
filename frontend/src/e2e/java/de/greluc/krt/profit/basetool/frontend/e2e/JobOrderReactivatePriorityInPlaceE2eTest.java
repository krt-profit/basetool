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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * E2E regression test: reactivating a terminal job order in place must refresh the order header, so
 * the server-assigned priority is shown without a reload.
 *
 * <p>Observes the header fragment request, reads the priority back from {@code GET
 * /api/v1/orders/{id}}, and asserts no reload via a window marker.
 */
@Tag("e2e")
class JobOrderReactivatePriorityInPlaceE2eTest {

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
   * material and a fresh {@code OPEN} MATERIAL order (which carries a priority) to complete and
   * then reactivate.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId =
          seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Reactivate Material");
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Reactivate Order", materialId, 650, 100.0);
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
   * Completes the order (terminal — nulls its priority through the warning modal), reloads to pick
   * up the refreshed {@code @Version}, then reactivates it to {@code IN_PROGRESS} and asserts the
   * header is re-pulled in place ({@code GET /orders/{id}?fragment=header}), no reload happened,
   * and the backend reassigned a non-null priority.
   */
  @Test
  void reactivatingATerminalOrderRefreshesThePriorityInPlace() {
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

        page.locator("#status-select").selectOption("COMPLETED");
        assertThat(page.locator("#status-warning-modal")).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/status") && "POST".equals(response.request().method()),
            () -> page.locator("[data-trigger='od-confirm-status']").click());
        assertEquals("COMPLETED", persistedStatus(), "status persists after completion");
        assertEquals(true, persistedPriorityNull(), "a terminal status nulls the priority");

        E2eSupport.navigate(page, detailUrl);
        page.evaluate("() => { window.__krtNoReload = true; }");

        page.waitForResponse(
            response ->
                response.url().contains("fragment=header")
                    && "GET".equals(response.request().method()),
            () -> page.locator("#status-select").selectOption("IN_PROGRESS"));

        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the reactivate must update in place — no page reload");
        assertEquals("IN_PROGRESS", persistedStatus(), "reactivation persists");
        assertEquals(false, persistedPriorityNull(), "reactivation reassigns a priority");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-reactivate-priority");
        throw failure;
      }
    }
  }

  /**
   * Reads the order's persisted status straight from the backend ({@code GET /api/v1/orders/{id}}),
   * so the assertion does not race the client's in-place change.
   *
   * @return the order's current {@code status} value
   */
  private static String persistedStatus() {
    return order().get("status").getAsString();
  }

  /**
   * Reads whether the order's persisted priority is currently {@code null} straight from the
   * backend ({@code GET /api/v1/orders/{id}}).
   *
   * @return {@code true} when the order has no priority (terminal), {@code false} when it carries
   *     one
   */
  private static boolean persistedPriorityNull() {
    JsonElement priority = order().get("priority");
    return priority == null || priority.isJsonNull();
  }

  /**
   * Fetches the seeded order's current backend projection.
   *
   * @return the order's JSON object from {@code GET /api/v1/orders/{id}}
   */
  private static JsonObject order() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId);
    return JsonParser.parseString(body).getAsJsonObject();
  }
}
