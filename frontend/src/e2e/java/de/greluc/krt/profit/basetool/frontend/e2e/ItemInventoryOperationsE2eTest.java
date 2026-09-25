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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flows for game-item stock in the Lager (REQ-INV-029/030/031): einbuchen in item mode,
 * umbuchen and ausbuchen of an item row, the ITEM-order allocation gate, and live peer sync on
 * {@code /inventory/all?view=items}.
 *
 * <p>Each scenario seeds its own game item via {@link BackendSeeder#seedOrderableItem}; mutations
 * are driven through the UI and verified via the grouped {@code catalog=ITEM} endpoint.
 */
@Tag("e2e")
class ItemInventoryOperationsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * Tolerance for amount equality assertions (item amounts are whole units, doubles on the DTO).
   */
  private static final double AMOUNT_DELTA = 0.001;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /**
   * One authenticated session reused across every test in this class (the OIDC login is the suite's
   * documented flakiness hot-spot); each test opens its own {@link BrowserContext} from this
   * storage state, so the flows stay isolated.
   */
  private static Path storageState;

  private static String opsHubLocId;

  private static String einbuchenGameItemId;
  private static String opsGameItemId;
  private static String opsItemRowId;
  private static String syncGameItemId;
  private static String syncItemRowId;
  private static String gateGameItemId;
  private static String gateItemOrderId;
  private static String gateMaterialOrderId;

  private static String needGameItemId;
  private static String needItemOrderId;

  /**
   * Launches the browser, performs the single shared login, and (ephemeral stack only) seeds the
   * IRIDIUM membership, a shared source location, one bookable game item per scenario, the item
   * rows the modal and live-sync flows operate on, and the ITEM + MATERIAL order pair the
   * allocation-gate scenario filters between.
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

    opsHubLocId = seeder.createLocation(USERNAME, PASSWORD, "E2E Item Ops Hub");
    String ingredientMatId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Item Ingredient Mat");

    einbuchenGameItemId = seeder.seedOrderableItem("E2E Item Einbuchen Widget", ingredientMatId);

    opsGameItemId = seeder.seedOrderableItem("E2E Item Ops Widget", ingredientMatId);
    opsItemRowId =
        seeder.createItemInventoryEntry(USERNAME, PASSWORD, opsGameItemId, opsHubLocId, 50);

    syncGameItemId = seeder.seedOrderableItem("E2E Item Sync Widget", ingredientMatId);
    syncItemRowId =
        seeder.createItemInventoryEntry(USERNAME, PASSWORD, syncGameItemId, opsHubLocId, 100);

    gateGameItemId = seeder.seedOrderableItem("E2E Item Gate Widget", ingredientMatId);
    gateItemOrderId =
        seeder.createItemJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Item Gate Order", gateGameItemId, 1);
    needGameItemId = seeder.seedOrderableItem("E2E Item Need Widget", ingredientMatId);
    needItemOrderId =
        seeder.createItemJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Item Need Order", needGameItemId, 7);

    String gateMatId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Item Gate Mat");
    gateMaterialOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Item Gate Material Order", gateMatId, 650, 10);
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
   * Einbuchen in item mode: books in the seeded widget and asserts the stock grew by the entered
   * amount and the row is reachable in the {@code /inventory/my?view=items} tree.
   */
  @Test
  void einbuchenCreatesItemStock() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-einbuchen",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          page.getByTestId("inventory-mode-item").check();
          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#gameItemId) .krt-combobox__input"),
              einbuchenGameItemId);
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#amount").fill("3");

          double before = totalAmount(myItemStacks(einbuchenGameItemId));
          page.evaluate(
              "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                  + " 'none'; } }");
          page.waitForResponse(
              r -> r.url().contains("/inventory/input") && "POST".equals(r.request().method()),
              () -> page.locator("form[action$='/inventory/input'] button[type='submit']").click());
          assertEquals(
              3.0,
              totalAmount(myItemStacks(einbuchenGameItemId)) - before,
              AMOUNT_DELTA,
              "the booked-in whole amount must add to the widget's item stock");

          page.waitForURL(url -> url.contains("/inventory/my"));

          JsonObject row = findMyItemRow(einbuchenGameItemId);
          openMyItemViewToEntry(
              page,
              einbuchenGameItemId,
              row.getAsJsonObject("location").get("id").getAsString(),
              row.get("id").getAsString());
        });
  }

  /**
   * Umbuchen and Ausbuchen of an item row: no quality column or mission split is rendered
   * (REQ-INV-029/031); 20 of 50 units move to another location, then discarding the remaining 30
   * removes the source stack.
   */
  @Test
  void umbuchenAndBookOutItemRow() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-umbuchen-ausbuchen",
        page -> {
          openMyItemViewToEntry(page, opsGameItemId, opsHubLocId, opsItemRowId);

          assertThat(page.locator("#inventoryTable .tree-gauge")).hasCount(0);
          assertThat(
                  page.locator(
                      "div.assoc-split[data-entry-id='"
                          + opsItemRowId
                          + "'][data-assoc-field='MISSION']"))
              .hasCount(0);
          assertThat(
                  page.locator(
                      "div.assoc-split[data-entry-id='"
                          + opsItemRowId
                          + "'][data-assoc-field='JOB_ORDER']"))
              .isVisible();

          page.locator("button[data-trigger='inv-my-umbuchen'][data-id='" + opsItemRowId + "']")
              .click();
          assertThat(page.locator("#umbuchenModal")).isVisible();
          String destinationLocationId = selectDifferentUmbuchenLocation(page, opsHubLocId);
          page.locator("#umbuchenAmount").fill("20");
          submitTransferInPlace(page, "#umbuchenSubmitBtn");

          JsonArray afterTransfer = myItemStacks(opsGameItemId);
          assertEquals(
              2, afterTransfer.size(), "the transfer splits the row into source + destination");
          assertEquals(
              30.0, amountAtLocation(afterTransfer, opsHubLocId), AMOUNT_DELTA, "source keeps 30");
          assertEquals(
              20.0,
              amountAtLocation(afterTransfer, destinationLocationId),
              AMOUNT_DELTA,
              "destination receives 20");

          openMyItemViewToEntry(page, opsGameItemId, opsHubLocId, opsItemRowId);
          page.locator("button[data-trigger='inv-my-bookout'][data-id='" + opsItemRowId + "']")
              .click();
          assertThat(page.locator("#bookOutModal")).isVisible();
          page.locator("input[name='type'][value='DISCARD']").check();
          page.locator("#amount").fill("30");
          submitTransferInPlace(page, "#bookOutSubmitBtn");

          JsonArray afterDiscard = myItemStacks(opsGameItemId);
          assertEquals(1, afterDiscard.size(), "the fully discarded source stack is removed");
          assertEquals(
              0.0,
              amountAtLocation(afterDiscard, opsHubLocId),
              AMOUNT_DELTA,
              "no stock remains at the source");
          assertEquals(
              20.0,
              amountAtLocation(afterDiscard, destinationLocationId),
              AMOUNT_DELTA,
              "the destination stack is untouched by the discard");
        });
  }

  /**
   * Einbuchen allocation gate (REQ-INV-031): in item mode the "+ Auftrag" row offers only ITEM
   * orders requesting that game item, and the mission allocation section is hidden.
   */
  @Test
  void einbuchenAllocationGateFiltersOrdersByCatalog() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-allocation-gate",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          page.getByTestId("inventory-mode-item").check();
          assertThat(page.locator("#missionAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));

          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#gameItemId) .krt-combobox__input"), gateGameItemId);

          page.locator("[data-trigger='inv-input-add-order']").click();
          Locator orderSelect = page.locator("#jobOrderAllocRows [data-alloc-target]").first();
          assertThat(orderSelect).isVisible();
          assertThat(orderSelect.locator("option[value='" + gateItemOrderId + "']")).isEnabled();
          assertThat(orderSelect.locator("option[value='" + gateMaterialOrderId + "']"))
              .isDisabled();
        });
  }

  /**
   * The item check-in order option shows the outstanding piece count (REQ-INV-039) and never the
   * quality marker, since item rows have no quality.
   */
  @Test
  void checkInItemOrderOptionStatesTheOutstandingNeed() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-order-need-label",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          page.getByTestId("inventory-mode-item").check();
          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#gameItemId) .krt-combobox__input"), needGameItemId);
          page.locator("[data-trigger='inv-input-add-order']").click();

          Locator option =
              page.locator(
                  "#jobOrderAllocRows [data-alloc-target] option[value='" + needItemOrderId + "']");
          assertThat(option).hasCount(1);
          assertThat(option)
              .hasText(
                  Pattern.compile(".*·\\s*noch\\s*7\\b.*"),
                  new LocatorAssertions.HasTextOptions().setTimeout(10_000));
          assertThat(option).not().hasText(Pattern.compile(".*benötigt.*"));
        });
  }

  /**
   * Live peer sync of an item write (REQ-FE-010/015): a 40-unit book-out in one context drops the
   * widget's group total to 60 in a second, never-reloaded context.
   */
  @Test
  void liveSyncItemWrite() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    String groupAmount =
        "div.tree-row--group[data-game-item-id='" + syncGameItemId + "'] .tree-amount";
    try (BrowserContext contextA =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState));
        BrowserContext contextB =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState))) {
      Page pageA = contextA.newPage();
      Page pageB = contextB.newPage();
      try {
        expandAllItemViewToLeaf(pageA);
        expandAllItemViewToLeaf(pageB);

        pageA.evaluate("window.__krtNoReload = true;");

        pageA.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageA.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics &&"
                            + " window.krtLiveSync.subscribedTopics().indexOf('inventory') >="
                            + " 0)")));

        pageB
            .locator("button[data-trigger='inv-admin-bookout'][data-id='" + syncItemRowId + "']")
            .click();
        assertThat(pageB.locator("#bookOutModal")).isVisible();
        pageB.locator("input[name='type'][value='DISCARD']").check();
        pageB.locator("#amount").fill("40");
        submitTransferInPlace(pageB, "#bookOutSubmitBtn");

        assertThat(pageA.locator(groupAmount))
            .containsText(
                Pattern.compile("(?<!\\d)60(?!\\d)"),
                new LocatorAssertions.ContainsTextOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            pageA.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "item-inventory-livesync-a");
        E2eSupport.dump(pageB, "item-inventory-livesync-b");
        throw failure;
      }
    }
  }

  /** A single UI flow body, run inside a fresh authenticated context with failure diagnostics. */
  @FunctionalInterface
  private interface Flow {
    /**
     * Runs the flow against the given page.
     *
     * @param page the page of a fresh, authenticated browser context
     */
    void run(Page page);
  }

  /**
   * Opens a fresh authenticated context + page from the shared storage state, runs {@code flow},
   * and on any failure dumps a screenshot + HTML under {@code build/e2e/<label>-failure.*} before
   * rethrowing.
   *
   * @param label artifact-filename prefix used when a flow fails
   * @param flow the UI flow body to execute
   */
  private void runFlow(String label, Flow flow) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        flow.run(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }

  /**
   * Navigates to {@code /inventory/my?view=items} and expands the game item's group and the stack
   * at the given location until the entry leaf row appears (REQ-INV-030). Already-open containers
   * are left open.
   *
   * @param page the authenticated page
   * @param gameItemId the scenario-unique game item whose group to expand
   * @param locationId the storage location identifying the stack to expand
   * @param rowId the inventory row id whose leaf row signals the entries loaded
   */
  private static void openMyItemViewToEntry(
      Page page, String gameItemId, String locationId, String rowId) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/my?view=items");
    page.waitForLoadState();
    Locator groupRow = page.locator("div.tree-row--group[data-game-item-id='" + gameItemId + "']");
    assertThat(groupRow).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
    if (isCollapsed(
        page,
        "div.tree-row--group[data-game-item-id='" + gameItemId + "'] + div.tree-group-items")) {
      groupRow.click();
    }
    String stackSelector =
        "div.stack-header[data-game-item-id='"
            + gameItemId
            + "'][data-location-id='"
            + locationId
            + "']";
    Locator stackHeader = page.locator(stackSelector);
    assertThat(stackHeader).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
    if (isCollapsed(page, stackSelector + " + div.tree-stack-entries")) {
      stackHeader.click();
    }
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + rowId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Navigates to {@code /inventory/all?view=items} and expands the sync widget's group and stack
   * until the entry leaf row loads.
   *
   * @param page the authenticated page
   */
  private static void expandAllItemViewToLeaf(Page page) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/all?view=items");
    page.waitForLoadState();
    page.locator("div.tree-row--group[data-game-item-id='" + syncGameItemId + "']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    page.locator("div.stack-header[data-game-item-id='" + syncGameItemId + "']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + syncItemRowId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Reports whether the Lager tree container matched by {@code selector} is collapsed, judged by
   * its computed {@code display}, not Playwright visibility.
   *
   * @param page the authenticated page
   * @param selector the CSS selector of the group-items / stack-entries container
   * @return {@code true} when the container is absent or {@code display: none}
   */
  private static boolean isCollapsed(Page page, String selector) {
    Object display =
        page.evaluate(
            "sel => { const el = document.querySelector(sel);"
                + " return el ? getComputedStyle(el).display : 'none'; }",
            selector);
    return "none".equals(display);
  }

  /**
   * Submits an open book-out or Umbuchen modal, waits for its {@code POST /inventory/{id}/transfer}
   * to answer, and asserts the page was not reloaded.
   *
   * @param page the authenticated page with the modal open and filled
   * @param submitSelector the modal's submit button selector ({@code #bookOutSubmitBtn} or {@code
   *     #umbuchenSubmitBtn})
   */
  private static void submitTransferInPlace(Page page, String submitSelector) {
    page.evaluate("window.__krtNoReload = true;");
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/transfer") && "POST".equals(r.request().method()),
        () -> page.locator(submitSelector).click());
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the in-place write must not reload the page");
  }

  /**
   * Selects the first Umbuchen target location that differs from the source and returns its id.
   *
   * @param page the authenticated page with the Umbuchen modal's LOCATION fields visible
   * @param sourceLocationId the row's current (source) location id to avoid
   * @return the chosen destination location id
   */
  private static String selectDifferentUmbuchenLocation(Page page, String sourceLocationId) {
    Locator combo = page.locator(".krt-combobox:has(#umbuchenTargetLocationId)");
    combo.locator(".krt-combobox__input").click();
    Locator options = combo.locator("li[role='option']");
    options.first().waitFor();
    int count = options.count();
    for (int i = 0; i < count; i++) {
      String value = options.nth(i).getAttribute("data-value");
      if (value != null && !value.isBlank() && !value.equals(sourceLocationId)) {
        options.nth(i).click();
        return value;
      }
    }
    throw new IllegalStateException(
        "No transfer-target location distinct from the source was offered in the dropdown");
  }

  /**
   * Fetches the owned ("my") grouped item Lager for one game item and returns that item's stacks as
   * a JSON array (empty when the item holds no owned stock).
   *
   * @param gameItemId the game item to query
   * @return the item's stack array, or an empty array
   */
  private static JsonArray myItemStacks(String gameItemId) {
    String body =
        seeder.getBody(
            USERNAME,
            PASSWORD,
            "/api/v1/inventory/my-inventory/grouped?catalog=ITEM&gameItemIds=" + gameItemId);
    JsonArray groups = JsonParser.parseString(body).getAsJsonArray();
    return groups.isEmpty()
        ? new JsonArray()
        : groups.get(0).getAsJsonObject().getAsJsonArray("stacks");
  }

  /**
   * Finds the caller's inventory row holding the given game item in the flat {@code catalog=ITEM}
   * list.
   *
   * @param gameItemId the scenario-unique game item whose row to find
   * @return the matching inventory row as JSON ({@code id}, {@code location}, {@code amount}, …)
   * @throws IllegalStateException if the caller holds no row of that game item
   */
  private static JsonObject findMyItemRow(String gameItemId) {
    String body =
        seeder.getBody(USERNAME, PASSWORD, "/api/v1/inventory/my-inventory?catalog=ITEM&size=500");
    JsonObject pageResponse = JsonParser.parseString(body).getAsJsonObject();
    for (JsonElement element : pageResponse.getAsJsonArray("content")) {
      JsonObject row = element.getAsJsonObject();
      if (row.has("gameItem")
          && row.get("gameItem").isJsonObject()
          && gameItemId.equals(row.getAsJsonObject("gameItem").get("id").getAsString())) {
        return row;
      }
    }
    throw new IllegalStateException("No owned item row found for game item " + gameItemId);
  }

  /**
   * Sums the {@code totalAmount} across all given stacks.
   *
   * @param stacks the stacks of one game item
   * @return the summed amount (0 when empty)
   */
  private static double totalAmount(JsonArray stacks) {
    double sum = 0;
    for (JsonElement element : stacks) {
      JsonObject stack = element.getAsJsonObject();
      if (stack.has("totalAmount") && !stack.get("totalAmount").isJsonNull()) {
        sum += stack.get("totalAmount").getAsDouble();
      }
    }
    return sum;
  }

  /**
   * Sums the {@code totalAmount} of the stacks stored at the given location.
   *
   * @param stacks the stacks of one game item
   * @param locationId the location id to filter on
   * @return the summed amount at that location (0 when none match)
   */
  private static double amountAtLocation(JsonArray stacks, String locationId) {
    double sum = 0;
    for (JsonElement element : stacks) {
      JsonObject stack = element.getAsJsonObject();
      JsonObject location = stack.getAsJsonObject("location");
      if (location != null
          && locationId.equals(location.get("id").getAsString())
          && stack.has("totalAmount")
          && !stack.get("totalAmount").isJsonNull()) {
        sum += stack.get("totalAmount").getAsDouble();
      }
    }
    return sum;
  }
}
