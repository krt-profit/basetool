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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E flow for production booking (Herstellung) on ITEM job orders (REQ-ORDERS-025).
 *
 * <p>Asserts that delivery is gated until production is booked, that booking through the modal
 * consumes the linked stock and books the produced unit into the Lager (REQ-INV-032), that the
 * earmarked stock shows on the order's "Bestellte Items" tab (REQ-ORDERS-028), and that marking it
 * delivered persists (REQ-ORDERS-030/031). Runs as {@code test-admin}.
 */
@Tag("e2e")
class JobOrderProductionE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The bootstrap-seeded orderable item whose blueprint derives the single recipe material. */
  private static final String ORDERABLE_ITEM_NAME = "E2E Orderable Widget";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the actor's IRIDIUM membership. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
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
   * Books production of a one-unit item order and asserts the gated-then-unlocked delivery control,
   * the persisted manufactured amount, the booked-in item stock, the earmarked stock on the order
   * detail, and the persisted delivered marker.
   */
  @Test
  void booksProductionConsumingLinkedStockAndUnlocksDelivery() {
    assumeTrue(STACK.managesStack(), "needs the bootstrap-seeded orderable item + item picker");
    String baseUrl = STACK.baseUrl();
    String handle = "E2E Production " + UUID.randomUUID();
    BackendSeeder seeder = new BackendSeeder();
    java.nio.file.Path storageState =
        E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        createOneUnitItemOrder(page, baseUrl, handle);
        JsonObject order = seeder.findOrderByHandle(USERNAME, PASSWORD, handle);
        String id = order.get("id").getAsString();

        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "?tab=item-handovers");
        assertThat(page.getByTestId("item-handover-open")).hasCount(0);
        assertThat(page.getByTestId("item-handover-none-manufactured")).isVisible();

        String materialId = firstRecipeMaterialId(seeder, id);
        String locationId =
            seeder.createLocation(USERNAME, PASSWORD, "E2E Production Loc " + UUID.randomUUID());
        seeder.createInventoryItemForJobOrder(
            USERNAME, PASSWORD, materialId, locationId, id, 1000, 5);

        String gameItemId = orderedGameItemId(seeder, id);
        double stockBefore = itemStockTotal(seeder, gameItemId);

        bookProductionOfOneUnit(page, baseUrl, id);

        assertEquals(
            1, manufacturedAmount(seeder, id), "one unit must be recorded as manufactured");
        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "?tab=item-handovers");
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        double stockAfter = itemStockTotal(seeder, gameItemId);
        assertEquals(
            stockBefore + 1.0,
            stockAfter,
            0.001,
            "the production booking must book the produced unit in as item stock");
        E2eSupport.navigate(page, baseUrl + "/inventory/all?view=items");
        Locator itemGroupRow =
            page.locator("div.tree-row--group[data-game-item-id='" + gameItemId + "']");
        assertThat(itemGroupRow)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(itemGroupRow).containsText(ORDERABLE_ITEM_NAME);
        assertThat(itemGroupRow.locator(".tree-amount"))
            .containsText(Pattern.compile("(?<!\\d)" + Math.round(stockAfter) + "(?!\\d)"));

        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "?tab=items");
        Locator expandToggle =
            page.locator("button.od-prod-toggle[data-trigger='od-toggle-demand']").first();
        assertThat(expandToggle)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        expandToggle.click();
        Locator inlineStock = page.locator(".od-item-stock-inline").first();
        assertThat(inlineStock).isVisible();
        assertThat(inlineStock.locator("tbody tr")).hasCount(1);

        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "/item-collection");
        Locator deliveredToggle =
            page.locator("input.delivered-checkbox[data-job-order-id='" + id + "']").first();
        assertThat(deliveredToggle)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(deliveredToggle).not().isChecked();
        assertFalse(earmarkDelivered(seeder, id), "the fresh earmark starts undelivered");
        page.waitForResponse(
            response ->
                response.url().contains("/delivered")
                    && "PATCH".equals(response.request().method()),
            () -> deliveredToggle.check());
        assertThat(deliveredToggle).isChecked();
        assertTrue(
            earmarkDelivered(seeder, id), "flipping the toggle must persist the delivered marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-production");
        throw failure;
      }
    }
  }

  /**
   * Drives the create form in item mode: selects the bootstrap-seeded orderable widget from the
   * searchable combobox, waits for the blueprint + material derivation to render the first
   * material's quality control, and submits at the default amount of one unit.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param handle the unique contact handle the order is filed under (for the admin read-back)
   */
  private static void createOneUnitItemOrder(Page page, String baseUrl, String handle) {
    E2eSupport.navigate(page, baseUrl + "/orders/create");
    page.getByTestId("order-mode-item").check();
    page.locator("#item-responsibleOrgUnitId").selectOption(IRIDIUM_ID);
    page.locator("#item-requestingOrgUnitId").selectOption(IRIDIUM_ID);
    page.locator("#item-handle").fill(handle);

    page.getByTestId("order-item-combobox").first().click();
    page.locator("li[role='option']")
        .filter(new Locator.FilterOptions().setHasText(ORDERABLE_ITEM_NAME))
        .first()
        .click();
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    page.locator("input[name='items[0].amount']").fill("1");
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    E2eSupport.clickSubmitClearingFooter(page.getByTestId("order-item-submit"));
    page.waitForLoadState();
  }

  /**
   * Opens the production modal from the "Bestellte Items" tab, allocates the required demand from
   * the single linked stock entry, picks the REQUIRED book-in location (REQ-INV-032 — the reconcile
   * gate keeps the book button disabled until one is chosen), and books the manufacture of one
   * unit, awaiting the production POST so the mutation is not dropped.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param orderId the item order to book production against
   */
  private static void bookProductionOfOneUnit(Page page, String baseUrl, String orderId) {
    E2eSupport.navigate(page, baseUrl + "/orders/" + orderId + "?tab=items");
    page.locator("[data-trigger='od-open-production']").first().click();

    Locator allocation = page.locator("#production-materials [data-prod-alloc]").first();
    allocation.waitFor();
    page.locator("#production-amount").fill("1");
    allocation.fill("1");

    assertThat(page.locator("#production-book-btn")).isDisabled();
    E2eSupport.selectComboboxFirstOption(
        page.locator(".krt-combobox:has(#production-location) .krt-combobox__input"));

    assertThat(page.locator("#production-book-btn")).isEnabled();
    page.waitForResponse(
        response ->
            response.url().contains("/production") && "POST".equals(response.request().method()),
        () -> page.locator("#production-book-btn").click());
  }

  /**
   * Resolves the id of the first derived recipe material of the order's single item line, read from
   * the persisted order so the linked stock matches exactly what the production modal fetches.
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order to inspect
   * @return the material id of {@code items[0].materials[0].material}
   */
  private static String firstRecipeMaterialId(BackendSeeder seeder, String orderId) {
    JsonObject order =
        JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId))
            .getAsJsonObject();
    return order
        .getAsJsonArray("items")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("materials")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("material")
        .get("id")
        .getAsString();
  }

  /**
   * Reads the persisted {@code manufacturedAmount} of the order's single item line straight from
   * the backend, so the assertion does not depend on any tab being rendered.
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order to inspect
   * @return the manufactured amount of {@code items[0]}
   */
  private static int manufacturedAmount(BackendSeeder seeder, String orderId) {
    JsonObject order =
        JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId))
            .getAsJsonObject();
    return order
        .getAsJsonArray("items")
        .get(0)
        .getAsJsonObject()
        .get("manufacturedAmount")
        .getAsInt();
  }

  /**
   * Resolves the id of the game item the order's single line requests, read from the persisted
   * order so the item-stock assertions target exactly the widget the production booked in.
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order to inspect
   * @return the game-item id of {@code items[0].gameItem}
   */
  private static String orderedGameItemId(BackendSeeder seeder, String orderId) {
    JsonObject order =
        JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId))
            .getAsJsonObject();
    return order
        .getAsJsonArray("items")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("gameItem")
        .get("id")
        .getAsString();
  }

  /**
   * Reads the persisted delivered marker of the order's first earmarked item-stock entry through
   * the item-stock endpoint (REQ-ORDERS-028), so the delivered-flip assertion proves persistence
   * rather than the optimistic checkbox state.
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order whose earmarked stock to inspect
   * @return the {@code delivered} flag of the first group's first entry ({@code [0].entries[0]})
   */
  private static boolean earmarkDelivered(BackendSeeder seeder, String orderId) {
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId + "/item-stock"))
        .getAsJsonArray()
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("entries")
        .get(0)
        .getAsJsonObject()
        .get("delivered")
        .getAsBoolean();
  }

  /**
   * Sums the squadron-wide item-stock total of one game item through the same grouped {@code
   * catalog=ITEM} endpoint the {@code /inventory/all} item view itself renders from
   * (REQ-INV-030/032), so the before/after delta proves the production book-in without racing the
   * post-write render.
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param gameItemId the game item whose stacks to sum
   * @return the summed {@code totalAmount} across all of the item's stacks (0 when none exist)
   */
  private static double itemStockTotal(BackendSeeder seeder, String gameItemId) {
    String body =
        seeder.getBody(
            USERNAME,
            PASSWORD,
            "/api/v1/inventory/all/grouped?catalog=ITEM&gameItemIds=" + gameItemId);
    double sum = 0;
    for (JsonElement groupElement : JsonParser.parseString(body).getAsJsonArray()) {
      for (JsonElement stackElement : groupElement.getAsJsonObject().getAsJsonArray("stacks")) {
        JsonObject stack = stackElement.getAsJsonObject();
        if (stack.has("totalAmount") && !stack.get("totalAmount").isJsonNull()) {
          sum += stack.get("totalAmount").getAsDouble();
        }
      }
    }
    return sum;
  }
}
