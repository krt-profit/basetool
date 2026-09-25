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

import com.google.gson.JsonArray;
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
 * Regression for the stale optimistic-lock {@code @Version} bug class on in-place writes (epic
 * #571, #577): the material-collection "delivered" checkbox ({@code PATCH
 * /inventory/{id}/delivered} → {@code InventoryItemService.updateDelivered}) toggles through {@code
 * window.krtFetch.write} without a page reload and must survive a SECOND consecutive toggle of the
 * SAME row.
 *
 * <p>The proven bug: a pre-fix backend returned a stale item version after the first toggle, so the
 * second toggle — replaying that version — 409ed with {@code OPTIMISTIC_LOCK}. The fix flushes
 * (save → saveAndFlush) so the response carries the fresh version, which the page syncs back onto
 * the row's {@code data-version} via {@code containerSelector}.
 *
 * <p>Each toggle flips the boolean (off → on, then on → off), so both are genuine changes that bump
 * the {@code @Version}. A window marker proves no reload happened between the two toggles; the
 * persisted {@code delivered} flag is read back from the backend to confirm the second write
 * landed; and the absence of an error toast is the discriminator that turns the second toggle red
 * on the pre-fix backend.
 *
 * <p><b>Drive via UI, verify via API.</b> The job order + a job-order-linked inventory item are
 * seeded through {@link BackendSeeder}; the outcome is read back from {@code GET
 * /api/v1/orders/{jobOrderId}/material-collection} rather than re-inspecting the toggled checkbox,
 * so the assertion never races the post-write render.
 */
@Tag("e2e")
class MaterialCollectionDeliveredInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Quality stamped on the seeded item; clears the job order's {@code minQuality} of 650. */
  private static final int SEED_QUALITY = 750;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static Path storageState;

  private static String jobOrderId;
  private static String deliveredItemId;

  /**
   * Launches the browser, performs the single shared login, and (ephemeral stack only) seeds the
   * IRIDIUM membership plus the job order + its job-order-linked inventory item the
   * material-collection page lists.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);

    String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Collection Delivered Hub");
    String materialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Collection Delivered Mat");
    jobOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Collection Delivered Order", materialId, 650, 100);
    deliveredItemId =
        seeder.createInventoryItemForJobOrder(
            USERNAME, PASSWORD, materialId, locationId, jobOrderId, SEED_QUALITY, 100);
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
   * Opens the job order's material-collection page, toggles the seeded row's delivered checkbox
   * once (asserting a success toast and no reload), then — without reloading — toggles it back,
   * asserting the second consecutive toggle succeeds (success toast, no error toast, marker
   * survives) and that the second value is the one the backend persisted. The second toggle only
   * succeeds if the write synced the fresh {@code @Version} onto the row's {@code data-version} — a
   * stale version 409s.
   */
  @Test
  void togglesDeliveredInPlaceThenSecondToggleDoesNotConflict() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId + "/material-collection");
        page.waitForLoadState();

        page.evaluate("() => { window.__krtNoReload = true; }");

        Locator checkbox =
            page.locator(".delivered-checkbox[data-inventory-id='" + deliveredItemId + "']");
        assertThat(checkbox).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        boolean initial = persistedDelivered();

        toggleInPlace(page, checkbox);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the delivered toggle must update in place — no page reload on success");
        assertEquals(!initial, persistedDelivered(), "the first in-place toggle must persist");

        toggleInPlace(page, checkbox);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the second consecutive toggle must also stay in place — no reload cleared the marker");
        assertEquals(
            initial,
            persistedDelivered(),
            "the second consecutive toggle must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-collection-delivered-in-place");
        throw failure;
      }
    }
  }

  /**
   * Clears any prior toast, clicks the delivered checkbox, waits for the in-place {@code PATCH
   * /inventory/{id}/delivered} to settle, and asserts the success UX: a non-error success toast
   * appeared and NO error toast surfaced — the latter being what a stale second toggle would
   * trigger on the pre-fix backend.
   *
   * @param page the authenticated material-collection page
   * @param checkbox the seeded row's delivered checkbox
   */
  private static void toggleInPlace(Page page, Locator checkbox) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().contains("/inventory/" + deliveredItemId + "/delivered")
                && "PATCH".equals(response.request().method()),
        checkbox::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
  }

  /**
   * Reads the seeded item's currently persisted {@code delivered} flag straight from the backend
   * ({@code GET /api/v1/orders/{jobOrderId}/material-collection}), matching the row by its
   * inventory entry id, so the persistence assertion does not race the client's in-place write.
   *
   * @return the persisted {@code delivered} flag of the seeded inventory entry
   */
  private static boolean persistedDelivered() {
    String body =
        seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId + "/material-collection");
    JsonArray entries = JsonParser.parseString(body).getAsJsonArray();
    for (JsonElement element : entries) {
      JsonObject entry = element.getAsJsonObject();
      if (deliveredItemId.equals(entry.get("inventoryEntryId").getAsString())) {
        return entry.get("delivered").getAsBoolean();
      }
    }
    throw new IllegalStateException(
        "Seeded inventory entry " + deliveredItemId + " not found in the material-collection list");
  }
}
