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
import com.microsoft.playwright.Locator;
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
 * E2E flow for unlinking a linked inventory item from a job order in place on the order-detail
 * page.
 *
 * <p>Drives the drill-down unlink button, asserts no reload via a window marker, and verifies
 * through the order's linked-inventory endpoint that the item is gone. The order never
 * auto-completes, so the manual unlink is the only cause.
 */
@Tag("e2e")
class JobOrderInventoryUnlinkInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;
  private static String jobOrderId;
  private static String inventoryItemId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership, a job-order
   * material, a location, an order requesting 200 of that material (so a single 100-unit linked
   * item never completes it), and one inventory item linked to the order.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Unlink Material");
      String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Unlink Location");
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Unlink Order", materialId, 650, 200.0);
      inventoryItemId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, jobOrderId, 750, 100.0);
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
   * Opens the material row's inventory drill-down, asserts the seeded item is linked, clicks its
   * unlink button, and asserts the in-place unlink removed the item from the order's linked
   * inventory (read back from the backend) without a reload or an error toast.
   */
  @Test
  void unlinksALinkedInventoryItemInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId);
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");

        assertEquals(
            100.0,
            linkedInventoryAmount(inventoryItemId),
            0.001,
            "the seeded item must start linked to the order");

        page.waitForResponse(
            response ->
                response.url().contains("/materials/" + materialId + "/inventory")
                    && "GET".equals(response.request().method()),
            () -> page.locator("tr.material-row[data-material-id='" + materialId + "']").click());

        Locator unlinkButton =
            page.locator(
                "button[data-trigger='od-unlink-inventory'][data-inventory-item-id='"
                    + inventoryItemId
                    + "']");
        assertThat(unlinkButton)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + jobOrderId + "/inventory/" + inventoryItemId)
                    && "DELETE".equals(response.request().method()),
            unlinkButton::click);

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the unlink must update in place — no page reload");
        assertEquals(
            -1.0,
            linkedInventoryAmount(inventoryItemId),
            0.001,
            "the unlinked item must no longer appear in the order's linked inventory");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-inventory-unlink");
        throw failure;
      }
    }
  }

  /**
   * Reads the current amount of one inventory entry as seen in the order's linked-inventory view
   * ({@code GET /api/v1/orders/{id}/materials/{materialId}/inventory} — the {@code
   * findByJobOrderIdOrdered} source the drill-down uses). Returns {@code -1.0} when the entry is
   * absent (e.g. after its job-order link is cleared by an unlink).
   *
   * @param itemId the inventory entry id to find
   * @return the entry's amount, or {@code -1.0} if it is no longer linked to the order
   */
  private static double linkedInventoryAmount(String itemId) {
    String body =
        new BackendSeeder()
            .getBody(
                USERNAME,
                PASSWORD,
                "/api/v1/orders/" + jobOrderId + "/materials/" + materialId + "/inventory");
    for (JsonElement element : JsonParser.parseString(body).getAsJsonArray()) {
      JsonObject item = element.getAsJsonObject();
      if (itemId.equals(item.get("id").getAsString())) {
        return item.get("amount").getAsDouble();
      }
    }
    return -1.0;
  }
}
