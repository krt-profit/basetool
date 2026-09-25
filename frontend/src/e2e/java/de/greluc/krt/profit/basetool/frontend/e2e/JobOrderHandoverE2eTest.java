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
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E flow for MATERIAL job-order handovers: records a handover through the UI and verifies the
 * handover record and the inventory book-out it triggers.
 *
 * <p>Covers the base handover, a partial book-out from one linked entry, and a single handover
 * drawing from two linked entries. Orders request more than is handed over so they never complete
 * and the entries stay linked for the read-back.
 */
@Tag("e2e")
class JobOrderHandoverE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;
  private static String locationId;
  private static String jobOrderId;
  private static String inventoryItemId;
  private static String singleEntryOrderId;
  private static String singleEntryItemId;
  private static String multiEntryOrderId;
  private static String multiEntryItemAId;
  private static String multiEntryItemBId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the full handover precondition chain:
   * IRIDIUM membership, a job-order material, a location, and three independent orders — the base
   * handover order (one linked inventory item), the single-entry book-out order (requests 200, one
   * 100-amount item), and the multi-entry book-out order (requests 200, two items of 100 and 60).
   * Both book-out orders request more than the tests hand over, so the order never completes and
   * the entries stay linked for the post-handover inventory read-back.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Handover Material");
      locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Handover Location");

      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Handover Order", materialId, 650, 100.0);
      inventoryItemId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, jobOrderId, 750, 100.0);

      singleEntryOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Handover Single", materialId, 650, 200.0);
      singleEntryItemId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, singleEntryOrderId, 750, 100.0);

      multiEntryOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Handover Multi", materialId, 650, 200.0);
      multiEntryItemAId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, multiEntryOrderId, 750, 100.0);
      multiEntryItemBId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, multiEntryOrderId, 750, 60.0);
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
   * Opens the handover modal, picks the seeded linked inventory item plus an amount, fills the
   * recipient and handover time, submits, and asserts the handover then appears in the order's
   * handover table.
   */
  @Test
  void recordsAHandoverThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId + "?tab=handovers");

        page.waitForResponse(
            response ->
                response.url().contains("/materials/") && response.url().contains("/inventory"),
            () -> page.getByTestId("order-handover-open").click());

        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(inventoryItemId);
        page.locator("input[name='items[0].amount']").fill("50");

        page.locator("#handover-modal .date-part").fill(LocalDate.now().toString());
        page.locator("#handover-modal .time-part").fill("12:00");
        page.locator("#recipientHandle").fill("E2E Recipient");

        page.evaluate("window.__krtNoReload = true;");
        page.getByTestId("order-handover-submit").click();
        assertThat(
                page.getByTestId("order-handover-row")
                    .filter(new Locator.FilterOptions().setHasText("E2E Recipient")))
            .isVisible();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "handover submit must update in place — no full-page reload cleared the window marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-handover");
        throw failure;
      }
    }
  }

  /**
   * Hands over part of a single linked inventory entry through the UI and asserts the entry is
   * booked out by exactly the handed-over amount: a 100-unit entry handed over for 40 must read 60
   * afterwards in the order's linked-inventory view.
   */
  @Test
  void booksOutTheHandedOverAmountFromASingleInventoryEntry() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + singleEntryOrderId + "?tab=handovers");
        openHandoverModal(page);

        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(singleEntryItemId);
        page.locator("input[name='items[0].amount']").fill("40");
        fillRecipientAndTime(page, "E2E Single Book-out");

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + singleEntryOrderId + "/handovers")
                    && "POST".equals(response.request().method()),
            () -> page.getByTestId("order-handover-submit").click());

        assertEquals(
            60.0,
            linkedInventoryAmount(singleEntryOrderId, singleEntryItemId),
            0.001,
            "the single linked entry must be booked out by the handed-over amount");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-handover-single-bookout");
        throw failure;
      }
    }
  }

  /**
   * Hands over from two linked inventory entries in one handover and asserts each is booked out by
   * its own amount: a 100-unit entry handed over for 40 must read 60, and a 60-unit entry handed
   * over for 30 must read 30 — proving the deduction is applied per entry, not pooled.
   */
  @Test
  void booksOutCorrespondingAmountsFromMultipleInventoryEntries() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + multiEntryOrderId + "?tab=handovers");
        openHandoverModal(page);

        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(multiEntryItemAId);
        page.locator("input[name='items[0].amount']").fill("40");
        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[1].inventoryItemId']").selectOption(multiEntryItemBId);
        page.locator("input[name='items[1].amount']").fill("30");
        fillRecipientAndTime(page, "E2E Multi Book-out");

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + multiEntryOrderId + "/handovers")
                    && "POST".equals(response.request().method()),
            () -> page.getByTestId("order-handover-submit").click());

        assertEquals(
            60.0,
            linkedInventoryAmount(multiEntryOrderId, multiEntryItemAId),
            0.001,
            "the first linked entry (100) must be booked out by 40");
        assertEquals(
            30.0,
            linkedInventoryAmount(multiEntryOrderId, multiEntryItemBId),
            0.001,
            "the second linked entry (60) must be booked out by 30");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-handover-multi-bookout");
        throw failure;
      }
    }
  }

  /**
   * Opens the material handover modal and waits for the order's linked inventory to load, so a row
   * added afterwards snapshots a populated dropdown. Mirrors the gating in {@link
   * #recordsAHandoverThroughTheUi}.
   *
   * @param page the page showing the order's detail
   */
  private static void openHandoverModal(Page page) {
    page.waitForResponse(
        response -> response.url().contains("/materials/") && response.url().contains("/inventory"),
        () -> page.getByTestId("order-handover-open").click());
  }

  /**
   * Fills the handover modal's recipient handle and the split date/time inputs (today / noon, which
   * the not-in-the-past validation permits for handovers).
   *
   * @param page the page with the open handover modal
   * @param recipient the recipient handle to record
   */
  private static void fillRecipientAndTime(Page page, String recipient) {
    page.locator("#handover-modal .date-part").fill(LocalDate.now().toString());
    page.locator("#handover-modal .time-part").fill("12:00");
    page.locator("#recipientHandle").fill(recipient);
  }

  /**
   * Reads the current amount of one inventory entry as seen in an order's linked-inventory view
   * ({@code GET /api/v1/orders/{orderId}/materials/{materialId}/inventory} — the ungated {@code
   * findByJobOrderIdOrdered} source). Returns {@code -1.0} when the entry is absent (e.g. fully
   * consumed and deleted), which the callers' scenarios never expect.
   *
   * @param orderId the order whose linked inventory to read
   * @param itemId the inventory entry id to find
   * @return the entry's amount, or {@code -1.0} if it is no longer listed
   */
  private static double linkedInventoryAmount(String orderId, String itemId) {
    String body =
        new BackendSeeder()
            .getBody(
                USERNAME,
                PASSWORD,
                "/api/v1/orders/" + orderId + "/materials/" + materialId + "/inventory");
    for (JsonElement element : JsonParser.parseString(body).getAsJsonArray()) {
      JsonObject item = element.getAsJsonObject();
      if (itemId.equals(item.get("id").getAsString())) {
        return item.get("amount").getAsDouble();
      }
    }
    return -1.0;
  }
}
