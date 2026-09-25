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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Storing ("einlagern") a refinery order's output into the Lager (UC-19): the store dialog turns
 * each output good into an {@code InventoryItem} (REQ-INV-001), completes the order and stamps the
 * row onto the assignee's org unit (REQ-ORG-004).
 *
 * <p>Driven via the UI and verified via the API through {@link BackendSeeder}; each test uses its
 * own fresh input material so the grouped-inventory probe reads exactly that order's output.
 */
@Tag("e2e")
class RefineryOrderStoreE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The catalog-seeded refinery-hosting location every order in this suite runs at. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String hubLocationId;

  /** Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership + hub id. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      hubLocationId = seeder.findLocationIdByName(USERNAME, PASSWORD, REFINERY_HUB);
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
   * Opens the pre-filled store modal on an OPEN order's detail page and submits it, then asserts
   * the order became {@code COMPLETED} and its output now exists in the Lager (absent beforehand).
   */
  @Test
  void storesRefineryOutputIntoLagerThroughTheUi() {
    String materialId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Store UI Material");
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);

    assertFalse(
        materialVisibleInLager(materialId), "the fresh output material is not in the Lager yet");

    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/" + orderId);
        page.waitForLoadState();
        page.locator("[data-trigger='rod-open-store']").click();
        assertThat(page.locator("#storeModal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        page.waitForResponse(
            response ->
                response.url().contains("/refinery-orders/" + orderId + "/store")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#storeForm button[type='submit']").click());
        assertThat(page.locator("#storeModal"))
            .not()
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-trigger='rod-open-store']"))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertTrue(
            page.url().contains("/refinery-orders/" + orderId),
            "an in-place store leaves the user on the order detail page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-store-ui");
        throw failure;
      }
    }

    assertEquals("COMPLETED", orderStatus(orderId), "the stored order is marked COMPLETED");
    assertTrue(
        materialVisibleInLager(materialId), "the refined output landed in the Lager after storing");
  }

  /**
   * A second store of an already-{@code COMPLETED} order is rejected with 400 — the status guard
   * prevents booking the same refinery run into the Lager twice.
   */
  @Test
  void reStoringAnAlreadyCompletedOrderIsRejected() {
    String materialId =
        seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Store ReStore Material");
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);

    seeder.storeRefineryOrder(
        USERNAME, PASSWORD, orderId, materialId, hubLocationId, 750, 1.0, null, null);
    assertEquals("COMPLETED", orderStatus(orderId), "the first store completes the order");

    assertEquals(
        400,
        seeder.attemptStoreRefineryOrderStatus(
            USERNAME, PASSWORD, orderId, materialId, hubLocationId, 750, 1.0, null, null),
        "re-storing a COMPLETED order must be rejected with 400");
  }

  /**
   * The note typed into the store dialog is propagated onto the resulting Lager row (REQ-INV-001),
   * so storage context travels with the inventory entry.
   */
  @Test
  void storedRowCarriesTheChosenNote() {
    String materialId =
        seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Store Note Material");
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
    String note = "E2E refinery store note " + orderId;

    seeder.storeRefineryOrder(
        USERNAME, PASSWORD, orderId, materialId, hubLocationId, 750, 1.0, null, note);

    String items = seeder.getBody(USERNAME, PASSWORD, "/api/v1/inventory/material/" + materialId);
    assertTrue(items.contains(note), "the stored Lager row carries the note from the store dialog");
  }

  /**
   * Reports whether the calling admin sees any shared stock of {@code materialId} in the global
   * Lager-View ({@code /inventory/all/grouped}) — i.e. the grouped result for that material is
   * non-empty.
   *
   * @param materialId the material to probe
   * @return {@code true} if the global view lists a group for that material
   */
  private static boolean materialVisibleInLager(String materialId) {
    return !JsonParser.parseString(
            seeder.getBody(
                USERNAME, PASSWORD, "/api/v1/inventory/all/grouped?materialIds=" + materialId))
        .getAsJsonArray()
        .isEmpty();
  }

  /**
   * Reads the current status of a refinery order via {@code GET /api/v1/refinery-orders/{id}}.
   *
   * @param orderId the refinery order id
   * @return the order's status string (e.g. {@code OPEN}, {@code COMPLETED})
   */
  private static String orderStatus(String orderId) {
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/refinery-orders/" + orderId))
        .getAsJsonObject()
        .get("status")
        .getAsString();
  }
}
