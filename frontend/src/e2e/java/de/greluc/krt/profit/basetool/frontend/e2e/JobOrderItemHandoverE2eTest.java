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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E flow for ITEM job orders: creates a two-unit order through the UI, delivers it in two item
 * handovers, and verifies the partial handover, the edit freeze once a handover exists, and
 * auto-completion when every line is delivered.
 *
 * <p>Needs the ephemeral stack, which seeds the orderable item; runs as {@code test-admin}.
 */
@Tag("e2e")
class JobOrderItemHandoverE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

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
   * Creates an ITEM order for two units of the seeded orderable item, records a handover of one
   * unit (partial — the log-handover button stays), asserts the edit route is frozen once a
   * handover exists, then records the final unit and asserts the order auto-completes (the button
   * is gone).
   */
  @Test
  void deliversAnItemOrderAcrossTwoHandoversToCompletion() {
    assumeTrue(STACK.managesStack(), "needs the bootstrap-seeded orderable item / item picker");
    String baseUrl = STACK.baseUrl();
    String handle = "E2E Item HO " + UUID.randomUUID();
    java.nio.file.Path storageState =
        E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        createItemOrderForTwoUnits(page, baseUrl, handle);

        BackendSeeder seeder = new BackendSeeder();
        JsonObject order = seeder.findOrderByHandle(USERNAME, PASSWORD, handle);
        assertNotNull(order, "the created ITEM order must be readable by the admin");
        String id = order.get("id").getAsString();

        String locationId =
            seeder.createLocation(USERNAME, PASSWORD, "E2E Item HO Loc " + UUID.randomUUID());
        seeder.manufactureItemOrderLineFully(USERNAME, PASSWORD, id, locationId);

        String gameItemId = orderedGameItemId(seeder, id);
        double stockAfterManufacture = itemStockTotal(seeder, gameItemId);

        String detailUrl = baseUrl + "/orders/" + id;
        String itemHandoverUrl = detailUrl + "?tab=item-handovers";

        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        recordItemHandover(page, "1", "E2E Item Recipient A");
        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(
                page.getByTestId("item-handover-row")
                    .filter(new Locator.FilterOptions().setHasText("E2E Item Recipient A")))
            .isVisible();
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        assertEquals(
            stockAfterManufacture - 1.0,
            itemStockTotal(seeder, gameItemId),
            1e-6,
            "the partial item handover must consume one earmarked unit (REQ-ORDERS-030)");

        E2eSupport.navigate(page, detailUrl + "/items/edit");
        assertThat(page.getByTestId("order-item-submit")).hasCount(0);

        E2eSupport.navigate(page, itemHandoverUrl);
        recordItemHandover(page, "1", "E2E Item Recipient B");
        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(page.getByTestId("item-handover-open")).hasCount(0);

        assertEquals(
            stockAfterManufacture - 2.0,
            itemStockTotal(seeder, gameItemId),
            1e-6,
            "the completing item handover must consume the last earmarked unit (REQ-ORDERS-030)");
        assertTrue(
            orderItemStockEmpty(seeder, id),
            "a fully delivered item order must retain no earmarked item stock (REQ-ORDERS-030)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-item-handover");
        throw failure;
      }
    }
  }

  /**
   * Drives the create form in item mode: picks the seeded orderable item from the searchable
   * combobox, waits for the blueprint + material derivation to render the first material's quality
   * control, raises the amount to two units, and submits.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param handle the unique contact handle the order is filed under (used for the admin read-back)
   */
  private static void createItemOrderForTwoUnits(Page page, String baseUrl, String handle) {
    E2eSupport.navigate(page, baseUrl + "/orders/create");
    page.getByTestId("order-mode-item").check();
    page.locator("#item-responsibleOrgUnitId").selectOption(IRIDIUM_ID);
    page.locator("#item-requestingOrgUnitId").selectOption(IRIDIUM_ID);
    page.locator("#item-handle").fill(handle);

    page.getByTestId("order-item-combobox").first().click();
    page.locator("li[role='option']").first().click();
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    page.locator("input[name='items[0].amount']").fill("2");
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    E2eSupport.clickSubmitClearingFooter(page.getByTestId("order-item-submit"));
    page.waitForLoadState();
  }

  /**
   * Opens the item-handover modal and records a delivery of the given whole-unit amount to the
   * named recipient, awaiting the post-submit redirect so the mutation is not dropped.
   *
   * @param page the page showing the item order's detail
   * @param amount the whole-unit amount to deliver (bound to {@code entries[0].amount})
   * @param recipient the recipient handle (bound to {@code #itemRecipientHandle})
   */
  private static void recordItemHandover(Page page, String amount, String recipient) {
    page.getByTestId("item-handover-open").click();
    page.locator("#item-handover-modal .date-part").fill(LocalDate.now().toString());
    page.locator("#item-handover-modal .time-part").fill("12:00");
    page.locator("#itemRecipientHandle").fill(recipient);
    page.locator("input[name='entries[0].amount']").fill(amount);
    page.waitForResponse(
        response ->
            response.url().contains("/item-handovers")
                && "POST".equals(response.request().method()),
        () -> page.getByTestId("item-handover-submit").click());
  }

  /**
   * Resolves the id of the game item the order's single line requests, read from the persisted
   * order so the item-stock assertions target exactly the widget the production booked in
   * (REQ-INV-032).
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order to inspect
   * @return the game-item id of {@code items[0].gameItem}
   */
  private static String orderedGameItemId(BackendSeeder seeder, String orderId) {
    return JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId))
        .getAsJsonObject()
        .getAsJsonArray("items")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("gameItem")
        .get("id")
        .getAsString();
  }

  /**
   * Sums the squadron-wide item-stock total of one game item through the same grouped {@code
   * catalog=ITEM} endpoint the {@code /inventory/all} item view renders from (REQ-INV-030/032), so
   * a before/after delta proves the delivery consumed the earmarked stock (REQ-ORDERS-030) without
   * racing the post-write render.
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

  /**
   * Reports whether the order carries no earmarked item stock, read through the order-scoped
   * item-stock endpoint (REQ-ORDERS-028). After a full delivery the consumed rows are depleted and
   * deleted, so the grouped result is empty — the order-scoped proof that the delivery drew its
   * earmark down to nothing (REQ-ORDERS-030).
   *
   * @param seeder the backend seeder used for the authenticated read-back
   * @param orderId the item order whose earmarked item stock to inspect
   * @return {@code true} when the order has no earmarked game-item rows left
   */
  private static boolean orderItemStockEmpty(BackendSeeder seeder, String orderId) {
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId + "/item-stock"))
        .getAsJsonArray()
        .isEmpty();
  }
}
