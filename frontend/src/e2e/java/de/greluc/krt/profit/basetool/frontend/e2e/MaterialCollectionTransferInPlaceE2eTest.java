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
 * E2E regression test: after an in-place full-amount transfer on the material-collection page, the
 * row must be re-keyed to the newly created target item so a follow-up action hits the live item.
 *
 * <p>Asserts no reload via a window marker and reads the outcome back from the backend.
 */
@Tag("e2e")
class MaterialCollectionTransferInPlaceE2eTest {

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
  private static String sourceItemId;

  /**
   * Launches the browser, performs the single shared login, and (ephemeral stack only) seeds the
   * IRIDIUM membership plus the job order and the job-order-linked inventory item (anchored at the
   * cached bootstrap location) that the material-collection page lists.
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

    String sourceLocationId = seeder.findLocationIdByName(USERNAME, PASSWORD, "E2E Refinery Hub");
    seeder.createLocation(USERNAME, PASSWORD, "E2E Collection Transfer Alt Hub");
    String materialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Collection Transfer Mat");
    jobOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Collection Transfer Order", materialId, 650, 100);
    sourceItemId =
        seeder.createInventoryItemForJobOrder(
            USERNAME, PASSWORD, materialId, sourceLocationId, jobOrderId, SEED_QUALITY, 100);
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
   * Changes the seeded row's location in place (a full-amount transfer), then — without reloading —
   * toggles the delivered checkbox on the SAME row. The follow-up toggle only lands without a
   * {@code 404} error toast if the transfer re-keyed the row to the freshly created target item; a
   * stale source id would target the deleted item. The persisted delivered flag is read back from
   * the backend to confirm the second write hit the live row.
   */
  @Test
  void reKeysTheRowAfterATransferSoAFollowUpDeliveredToggleDoesNotConflict() {
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

        Locator locationSelect =
            page.locator(".location-select[data-inventory-id='" + sourceItemId + "']");
        assertThat(locationSelect)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

        page.waitForResponse(
            response ->
                response.url().contains("/inventory/" + sourceItemId + "/transfer")
                    && "POST".equals(response.request().method()),
            () -> selectDifferentLocation(locationSelect));

        assertThat(page.locator(".notification-toast:not(.error-toast)"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the transfer must update in place — no page reload");

        assertEquals(1, entryCount(), "a full-amount transfer leaves exactly one entry");
        boolean deliveredBeforeToggle = firstEntryDelivered();

        page.evaluate(
            "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove());"
                + " }");
        Locator checkbox = page.locator(".delivered-checkbox");
        page.waitForResponse(
            response ->
                response.url().contains("/delivered")
                    && "PATCH".equals(response.request().method()),
            checkbox::click);

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the follow-up delivered toggle must also stay in place");
        assertEquals(
            !deliveredBeforeToggle,
            firstEntryDelivered(),
            "the delivered toggle must persist on the re-keyed target item — a stale source id"
                + " 404s");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-collection-transfer-in-place");
        throw failure;
      }
    }
  }

  /**
   * Selects, in the given row's location dropdown, the first option whose value differs from the
   * currently selected one and returns that destination id. Picking from the dropdown's actual
   * options (rather than a freshly seeded id that the frontend's long-lived location cache may not
   * list) makes the change event fire reliably; the bootstrap source hub plus the locations other
   * suites seed guarantee at least one distinct option.
   *
   * @param locationSelect the row's {@code .location-select} element
   * @return the chosen destination location id
   * @throws IllegalStateException when the dropdown offers no option distinct from the selection
   */
  private static String selectDifferentLocation(Locator locationSelect) {
    String current = locationSelect.inputValue();
    Locator options = locationSelect.locator("option");
    int count = options.count();
    for (int i = 0; i < count; i++) {
      String value = options.nth(i).getAttribute("value");
      if (value != null && !value.isBlank() && !value.equals(current)) {
        locationSelect.selectOption(value);
        return value;
      }
    }
    throw new IllegalStateException(
        "material-collection location dropdown offered no option distinct from the current"
            + " selection");
  }

  /**
   * Reads the number of entries the job order's material collection currently holds straight from
   * the backend ({@code GET /api/v1/orders/{jobOrderId}/material-collection}).
   *
   * @return the count of inventory entries linked to the seeded job order
   */
  private static int entryCount() {
    String body =
        seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId + "/material-collection");
    return JsonParser.parseString(body).getAsJsonArray().size();
  }

  /**
   * Reads the {@code delivered} flag of the job order's first (after a full-amount transfer: only)
   * material-collection entry straight from the backend, so the persistence assertion does not race
   * the client's in-place write.
   *
   * @return the persisted {@code delivered} flag of the first collection entry
   * @throws IllegalStateException when the collection is unexpectedly empty
   */
  private static boolean firstEntryDelivered() {
    String body =
        seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId + "/material-collection");
    JsonArray entries = JsonParser.parseString(body).getAsJsonArray();
    if (entries.isEmpty()) {
      throw new IllegalStateException(
          "material-collection for job order " + jobOrderId + " is unexpectedly empty");
    }
    return entries.get(0).getAsJsonObject().get("delivered").getAsBoolean();
  }
}
