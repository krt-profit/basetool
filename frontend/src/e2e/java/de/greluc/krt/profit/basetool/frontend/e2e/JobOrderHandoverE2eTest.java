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
 * Functional flow: record a (MATERIAL) Job Order handover through the UI and verify both the
 * handover record and the inventory book-out it triggers.
 *
 * <p>This is the concurrency-sensitive flow — the handover decrements the linked inventory item and
 * the job-order material's open amount inside one transaction. It needs an order with a
 * handover-eligible inventory item linked to it, which the admin REST API can build end to end:
 * {@link BackendSeeder} seeds the IRIDIUM membership, a job-order material, a location, a job order
 * requesting that material, and an inventory item linked to the order. The handover modal lazily
 * fetches that linked inventory per material and snapshots it into the item dropdown when a row is
 * added, so the test waits for the cache before adding a row.
 *
 * <p>Three cases are covered:
 *
 * <ul>
 *   <li>The base flow records a handover and asserts it appears in the order's handover table.
 *   <li>The single-entry book-out asserts that handing over part of one linked inventory entry
 *       reduces exactly that entry by the handed-over amount.
 *   <li>The multi-entry book-out asserts that a single handover drawing from two linked entries
 *       reduces each entry by its own amount.
 * </ul>
 *
 * <p>Note on scope: only MATERIAL handovers book materials out of inventory. ITEM handovers (see
 * {@link JobOrderItemHandoverE2eTest}) merely increment each ordered line's {@code deliveredAmount}
 * and never touch inventory, so the book-out assertions belong here, on the material flow. The
 * book-out is verified through the order-context inventory endpoint ({@code GET
 * /api/v1/orders/{id}/materials/{matId}/inventory}, the same {@code findByJobOrderIdOrdered} source
 * the modal dropdown uses); the deduction orders request more than is handed over, so the order
 * never fully completes and the entries stay linked (a completed order unlinks its remaining
 * inventory).
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

      // Single-entry book-out: order requests 200 so a 40-unit handover never completes it.
      singleEntryOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Handover Single", materialId, 650, 200.0);
      singleEntryItemId =
          seeder.createInventoryItemForJobOrder(
              USERNAME, PASSWORD, materialId, locationId, singleEntryOrderId, 750, 100.0);

      // Multi-entry book-out: two linked items (100 + 60) on an order requesting 200.
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

        // Opening the modal lazily fetches the order's linked inventory per material; the "add row"
        // button snapshots that cache at click time, so the cache must be populated first. Gate on
        // the network response rather than a page-side waitForFunction, which would trip the strict
        // CSP (script-src has no 'unsafe-eval').
        page.waitForResponse(
            response ->
                response.url().contains("/materials/") && response.url().contains("/inventory"),
            () -> page.getByTestId("order-handover-open").click());

        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(inventoryItemId);
        page.locator("input[name='items[0].amount']").fill("50");

        // The split date/time inputs sync into the hidden #handoverTime (UTC ISO); past times are
        // allowed here (data-validate-not-past='false'), so today's date is fine.
        page.locator("#handover-modal .date-part").fill(LocalDate.now().toString());
        page.locator("#handover-modal .time-part").fill("12:00");
        page.locator("#recipientHandle").fill("E2E Recipient");

        // Submit in place (#575): the material handover swaps the materials/handover/header
        // sections via AJAX and closes the modal — there is no Post/Redirect/Get navigation to
        // await. Mark the window to prove no full reload happened, submit, then web-first-wait for
        // the recorded handover row to appear in the re-rendered history (which also proves the
        // entry persisted), and assert the marker survived.
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

        // Submit in place (#575): the handover books out inventory in one transaction and swaps
        // sections via AJAX — await the handover XHR POST (the commit) rather than a document
        // navigation that never comes; the backend read-back below then sees the booked-out amount.
        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + singleEntryOrderId + "/handovers")
                    && "POST".equals(response.request().method()),
            () -> page.getByTestId("order-handover-submit").click());

        // The linked entry must now hold the original 100 minus the 40 handed over.
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

        // First entry: hand over 40 of 100.
        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(multiEntryItemAId);
        page.locator("input[name='items[0].amount']").fill("40");
        // Second entry: hand over 30 of 60, in the same handover.
        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[1].inventoryItemId']").selectOption(multiEntryItemBId);
        page.locator("input[name='items[1].amount']").fill("30");
        fillRecipientAndTime(page, "E2E Multi Book-out");

        // Submit in place (#575): await the handover XHR POST (the book-out commit) rather than a
        // document navigation that never comes; the per-entry read-backs below then see the result.
        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + multiEntryOrderId + "/handovers")
                    && "POST".equals(response.request().method()),
            () -> page.getByTestId("order-handover-submit").click());

        // Each entry is reduced by its own handed-over amount, not the pooled total.
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
