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
 * Functional flow for the Herstellung (production booking) feature of ITEM job orders
 * (REQ-ORDERS-025): a logistician records how many ordered units were manufactured, and the booking
 * consumes the exact linked inventory the recipe needs. Delivery is gated by manufacture — a fresh
 * item order (manufactured = 0) offers no item-handover control until production is booked.
 *
 * <p>The fixture creates a one-unit order of the bootstrap-seeded orderable widget (its blueprint
 * derives a single RESOURCE material at 1.0 SCU/unit), then links one inventory entry of that exact
 * recipe material to the order over the API. The flow then drives the real production modal from
 * the "Bestellte Items" tab (the production surface folds into it): it enters an amount, allocates
 * the required demand from the linked stock entry, and books once the reconcile chip reports full
 * coverage.
 *
 * <p>Since REQ-INV-032 the production modal additionally carries a REQUIRED book-in section: the
 * produced units land as game-item Lager stock at a chosen location, so the flow picks the location
 * combobox (the reconcile gate holds the book button until one is chosen) and afterwards proves the
 * booked-in stock end-to-end — the item-stock total of the produced game item grows by exactly the
 * manufactured unit (read through the same grouped {@code catalog=ITEM} API the item view uses),
 * and the produced widget is visible on {@code /inventory/all?view=items} with its name and
 * whole-unit amount.
 *
 * <p>Four things are asserted end-to-end: before production the item-handover control is absent
 * (delivery gated); the booking succeeds through the UI; afterwards the persisted {@code
 * manufacturedAmount} is 1 (read back through the backend) and the item-handover control has
 * appeared (delivery unlocked); and the produced stock is visible in the shared Lager's item view
 * (REQ-INV-032). The actor is {@code test-admin}, which satisfies the production role gate through
 * the role hierarchy and is an IRIDIUM member (the order's responsible unit).
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
   * Creates a one-unit item order, verifies delivery is gated pre-production, books the manufacture
   * of the single unit through the production modal (consuming the linked stock and booking the
   * produced unit in, REQ-INV-032), and asserts the persisted manufactured amount, the now-unlocked
   * item-handover control, and the produced item stock — grown by exactly one unit and visible on
   * the shared Lager's item view.
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

        // Delivery is gated by manufacture: with manufactured = 0 the item-handover control is not
        // rendered yet, and the tab explains why instead of showing a misleading "all delivered"
        // note (REQ-ORDERS-025).
        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "?tab=item-handovers");
        assertThat(page.getByTestId("item-handover-open")).hasCount(0);
        assertThat(page.getByTestId("item-handover-none-manufactured")).isVisible();

        // Link one inventory entry of the order's exact derived recipe material, so the production
        // modal has stock to consume. The recipe material is resolved from the persisted order
        // rather
        // than assumed, so the linked stock always matches what the modal fetches.
        String materialId = firstRecipeMaterialId(seeder, id);
        String locationId =
            seeder.createLocation(USERNAME, PASSWORD, "E2E Production Loc " + UUID.randomUUID());
        seeder.createInventoryItemForJobOrder(
            USERNAME, PASSWORD, materialId, locationId, id, 1000, 5);

        // REQ-INV-032 baseline: the produced widget's squadron-wide item-stock total before the
        // booking. The bootstrap widget is shared across the suite's item-order classes, so the
        // proof is a delta, never an absolute total.
        String gameItemId = orderedGameItemId(seeder, id);
        double stockBefore = itemStockTotal(seeder, gameItemId);

        bookProductionOfOneUnit(page, baseUrl, id);

        // The manufactured amount is now persisted, and delivery is unlocked: the item-handover
        // control appears (outstanding = manufactured - delivered = 1).
        assertEquals(
            1, manufacturedAmount(seeder, id), "one unit must be recorded as manufactured");
        E2eSupport.navigate(page, baseUrl + "/orders/" + id + "?tab=item-handovers");
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        // REQ-INV-032 end-to-end: the booking also booked the produced unit in as game-item Lager
        // stock — the item-stock total grew by exactly the manufactured unit ...
        double stockAfter = itemStockTotal(seeder, gameItemId);
        assertEquals(
            stockBefore + 1.0,
            stockAfter,
            0.001,
            "the production booking must book the produced unit in as item stock");
        // ... and the produced stock is visible on the shared Lager's item view: the widget's
        // group row renders the gameItem name and the whole-unit total (no quality columns).
        E2eSupport.navigate(page, baseUrl + "/inventory/all?view=items");
        Locator itemGroupRow =
            page.locator("div.tree-row--group[data-game-item-id='" + gameItemId + "']");
        assertThat(itemGroupRow)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(itemGroupRow).containsText(ORDERABLE_ITEM_NAME);
        assertThat(itemGroupRow.locator(".tree-amount"))
            .containsText(Pattern.compile("(?<!\\d)" + Math.round(stockAfter) + "(?!\\d)"));
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

    // Pick the seeded widget explicitly (by name) so the derived recipe is the known single 1.0-SCU
    // material, then wait for the derivation to render the first material's quality control.
    page.getByTestId("order-item-combobox").first().click();
    page.locator("li[role='option']")
        .filter(new Locator.FilterOptions().setHasText(ORDERABLE_ITEM_NAME))
        .first()
        .click();
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    // Order exactly one unit (re-deriving the recipe), so a single produced unit completes the line
    // and its 1.0-SCU/unit ingredient needs exactly 1.0 SCU.
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
    // The production surface folds into the "Bestellte Items" tab (#1317 follow-up): the
    // "Herstellung
    // erfassen" button is the last column there, and there is no separate Herstellung tab.
    E2eSupport.navigate(page, baseUrl + "/orders/" + orderId + "?tab=items");
    page.locator("[data-trigger='od-open-production']").first().click();

    // The modal lazily fetches the material's linked inventory; wait for the per-entry allocation
    // input to render, then cover the full demand (1.0 SCU for one unit at 1.0 SCU/unit).
    Locator allocation = page.locator("#production-materials [data-prod-alloc]").first();
    allocation.waitFor();
    page.locator("#production-amount").fill("1");
    allocation.fill("1");

    // Book-in gate (REQ-INV-032): with the demand covered but no location picked yet the book
    // button stays disabled; picking the first offered location from the server-searched
    // remote-locations combobox (browse-mode fetch on open, REQ-FE-016) re-enables it. Which
    // location receives the stock is irrelevant to this flow — the stock assertion sums across
    // locations.
    assertThat(page.locator("#production-book-btn")).isDisabled();
    E2eSupport.selectComboboxFirstOption(
        page.locator(".krt-combobox:has(#production-location) .krt-combobox__input"));

    // Reconcile enables the book button only when every material's demand is exactly covered and
    // the book-in location is chosen.
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
