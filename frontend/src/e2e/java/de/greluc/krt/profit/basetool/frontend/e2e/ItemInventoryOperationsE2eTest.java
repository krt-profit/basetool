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
 * Functional flows for game-item stock rows in the Lager (UC-30, REQ-INV-029/030/031) — the item
 * sibling of {@link InventoryOperationsE2eTest}: einbuchen in the create form's item mode, umbuchen
 * and ausbuchen on an item entry through the shared modals, the ITEM-order-only allocation gate on
 * the einbuchen allocation rows, and the live peer-sync of an item write on {@code
 * /inventory/all?view=items} (REQ-FE-010/015, mirroring {@link InventorySharedLagerLiveSyncE2eTest}
 * — the single {@code inventory}/{@code stock} seam with {@code view=} riding the fragment URL).
 *
 * <p><b>Seeding.</b> Bookable game items are the output of at least one active blueprint
 * (REQ-INV-029); the e2e stack's UEX/SCWiki syncs never run, so the catalog is seeded per scenario
 * via {@link BackendSeeder#seedOrderableItem} (a {@code game_item} + active {@code blueprint} +
 * resolved RESOURCE ingredient over JDBC — the same shape the stack bootstrap seeds once for the
 * item-order picker). Item stock rows for the modal flows are seeded through the real {@code POST
 * /api/v1/inventory} with a {@code gameItemId} payload ({@link
 * BackendSeeder#createItemInventoryEntry}), and the allocation-gate fixture seeds one qualifying
 * ITEM order ({@link BackendSeeder#createItemJobOrder}) plus one MATERIAL order as the negative.
 * Each scenario uses its <strong>own game item</strong> for isolation in the shared, sequentially
 * run stack.
 *
 * <p><b>Drive via UI, verify via API.</b> Like the material sibling, every mutation goes through
 * the real form / modal and the outcome is asserted through the same grouped {@code catalog=ITEM}
 * endpoint the item views render from ({@code GET
 * /api/v1/inventory/my-inventory/grouped?catalog=ITEM&gameItemIds=…}), which never races the
 * post-write render. The item picker is a remote-searched combobox ({@code remote-game-items} →
 * {@code /inventory/item-search}, uncached), so a freshly seeded widget is offered at once; the
 * location picker stays the 10-minute-cached local combobox, so the flows select whatever it offers
 * rather than assuming a fresh location is listed.
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

  // Shared reference data (seeded once).
  private static String opsHubLocId;

  // Per-scenario game items + rows (one item per scenario, see class Javadoc).
  private static String einbuchenGameItemId;
  private static String opsGameItemId;
  private static String opsItemRowId;
  private static String syncGameItemId;
  private static String syncItemRowId;
  private static String gateGameItemId;
  private static String gateItemOrderId;
  private static String gateMaterialOrderId;

  // REQ-INV-039 (#1742) need-label fixture: an ITEM order for 7 units with nothing manufactured,
  // delivered or earmarked, and never written to by another case — so the label is a fixed 7.
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
    // One blueprint ingredient shared by every seeded widget — the bookable-item predicate only
    // needs the blueprint to exist, the ingredient material itself is never booked here.
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
   * <em>Einbuchen (item mode).</em> Switches the create form to item mode via the catalog toggle,
   * picks the seeded widget from the remote-searched item combobox, a location and a whole amount,
   * submits the AJAX twin and asserts the stock landed (grouped {@code catalog=ITEM} read grew by
   * exactly the entered amount) and that the new row is reachable in the {@code
   * /inventory/my?view=items} tree: gameItem group → stack → lazily loaded entry leaf.
   */
  // covers REQ-INV-029/030
  @Test
  void einbuchenCreatesItemStock() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-einbuchen",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          // Item mode: the toggle hides+disables the material block (no quality field renders)
          // and switches the amount to whole units (REQ-INV-029).
          page.getByTestId("inventory-mode-item").check();
          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#gameItemId) .krt-combobox__input"),
              einbuchenGameItemId);
          // The location lookup is 10-minute cached, so pick whatever the combobox offers (the
          // stack assertion is location-agnostic — the widget is unique to this scenario).
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#amount").fill("3");

          double before = totalAmount(myItemStacks(einbuchenGameItemId));
          // #577: book-in is an X-Requested-With AJAX twin (navigate-after-AJAX on success), so
          // wait on the XHR POST; keep the footer-clear so the trusted click is not intercepted.
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

          // Let the success handler's own navigation to the source listing settle before driving
          // the item view, so the explicit navigate below never aborts it mid-flight.
          page.waitForURL(url -> url.contains("/inventory/my"));

          // REQ-INV-030: the new stock is reachable through the item tree — group, stack, entry.
          // The row (and the location the cached combobox happened to offer) is read back through
          // the flat catalog=ITEM list, since the create response is consumed by the page.
          JsonObject row = findMyItemRow(einbuchenGameItemId);
          openMyItemViewToEntry(
              page,
              einbuchenGameItemId,
              row.getAsJsonObject("location").get("id").getAsString(),
              row.get("id").getAsString());
        });
  }

  /**
   * <em>Umbuchen + Ausbuchen (item row).</em> Expands the item tree to the seeded 50-unit entry,
   * asserts the item view renders neither a quality column nor a mission split (REQ-INV-029/031 —
   * locator absence), transfers 20 units to a different location through the Umbuchen modal
   * (append-only: source keeps 30, destination receives a fresh 20), then discards the remaining 30
   * at the source through the book-out modal — the source stack falls under the deletion epsilon
   * and vanishes, leaving only the destination stack.
   */
  // covers REQ-INV-029/030/031
  @Test
  void umbuchenAndBookOutItemRow() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-umbuchen-ausbuchen",
        page -> {
          openMyItemViewToEntry(page, opsGameItemId, opsHubLocId, opsItemRowId);

          // Item view renders no quality column (no .tree-gauge anywhere in the tree) and the
          // entry carries no mission split — only the Auftrag dimension exists on item rows.
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

          // Umbuchen: transfer 20 whole units to a different (cached) location.
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

          // Ausbuchen (DISCARD, full remainder): the source row falls under the deletion epsilon
          // and its stack vanishes — only the destination stack remains.
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
   * <em>Einbuchen allocation gate (REQ-INV-031).</em> In item mode with the gate widget picked, the
   * "+ Auftrag" allocation row offers only ITEM orders whose lines request that gameItem: the
   * seeded qualifying ITEM order's option stays enabled while the MATERIAL order's option is
   * filtered out (the {@code data-game-items} CSV sibling of the material {@code data-materials}
   * filter). The mission allocation section is hidden outright — item rows reject the mission
   * dimension.
   */
  // covers REQ-INV-031
  @Test
  void einbuchenAllocationGateFiltersOrdersByCatalog() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded bookable game-item catalog");
    runFlow(
        "item-inventory-allocation-gate",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          page.getByTestId("inventory-mode-item").check();
          // Item mode has no mission dimension (REQ-INV-031): the section is hidden outright.
          assertThat(page.locator("#missionAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));

          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#gameItemId) .krt-combobox__input"), gateGameItemId);

          // A fresh allocation row is filtered by the picked gameItem on add: the qualifying ITEM
          // order stays selectable, the MATERIAL order is disabled + hidden.
          page.locator("[data-trigger='inv-input-add-order']").click();
          Locator orderSelect = page.locator("#jobOrderAllocRows [data-alloc-target]").first();
          assertThat(orderSelect).isVisible();
          assertThat(orderSelect.locator("option[value='" + gateItemOrderId + "']")).isEnabled();
          assertThat(orderSelect.locator("option[value='" + gateMaterialOrderId + "']"))
              .isDisabled();
        });
  }

  /**
   * <em>Restmenge an der Auftragsoption, Item-Variante (REQ-INV-039, #1742).</em> The material
   * picker states what an order still needs; the item picker states the same thing from a different
   * calculation — ordered minus delivered minus already earmarked, in whole pieces. Picks the game
   * item of an order for 7 units with nothing built, delivered or earmarked, adds an allocation row
   * and asserts the option carries the count.
   *
   * <p>Also pins the deliberate asymmetry: item rows carry no quality at all (REQ-INV-029), so the
   * material picker's quality marker must never appear here.
   */
  // covers REQ-INV-039 (item mode)
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
          // "#<id> - <handle> (OPEN) · noch 7 Stück" — whole units, and no quality floor beside it.
          assertThat(option)
              .hasText(
                  Pattern.compile(".*7.*"),
                  new LocatorAssertions.HasTextOptions().setTimeout(10_000));
          assertThat(option).not().hasText(Pattern.compile(".*650.*"));
        });
  }

  /**
   * <em>Live peer-sync of an item write (REQ-FE-010/015).</em> Two contexts of the same admin view
   * {@code /inventory/all?view=items} with the seeded 100-unit widget expanded. Context B books out
   * 40 units (DISCARD) through the book-out modal — the write broadcasts the existing {@code
   * inventory}/{@code stock} seam — and context A, a passive viewer that never reloads, must show
   * the widget's group total drop to 60 IN PLACE (its own filtered fragment re-fetch rides the
   * {@code view=items} query state).
   */
  // covers REQ-INV-030 (item view) + REQ-FE-010/015 (single inventory/stock seam)
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

        // A full reload on A would clear this marker; the live in-place swap leaves it intact.
        pageA.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: A is registered with the relay once its `inventory` subscribe is
        // acked.
        pageA.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageA.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics &&"
                            + " window.krtLiveSync.subscribedTopics().indexOf('inventory') >="
                            + " 0)")));

        // Context B books out 40 of the 100 units (broadcasts inventory/[stock] on success).
        pageB
            .locator("button[data-trigger='inv-admin-bookout'][data-id='" + syncItemRowId + "']")
            .click();
        assertThat(pageB.locator("#bookOutModal")).isVisible();
        pageB.locator("input[name='type'][value='DISCARD']").check();
        pageB.locator("#amount").fill("40");
        submitTransferInPlace(pageB, "#bookOutSubmitBtn");

        // The assertion under test: context A — which did nothing — re-renders its item tree in
        // place (the global room coalesces at ~1.5 s) and shows the reduced whole-unit total.
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

  // --------------------------------------------------------------------------------------------
  // Shared flow scaffolding
  // --------------------------------------------------------------------------------------------

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
   * Navigates to {@code /inventory/my?view=items} and expands the game item's group then the stack
   * at the given location, waiting for the lazily-fetched entry leaf row to appear (REQ-INV-030 —
   * the item tree mirrors the material tree's lazy-load semantics with a view-scoped expansion
   * state). The game item is unique per scenario so the group selector is unambiguous; the stack
   * header is additionally keyed by location because a transfer splits the item across two stacks
   * under the same group. Expansion is idempotent like the material sibling's helper: a
   * restored-open container is not blind-clicked shut (the guard reads the computed {@code
   * display}, not Playwright visibility).
   *
   * @param page the authenticated page
   * @param gameItemId the (scenario-unique) game item whose group to expand
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
    // 20 s, not the 5 s default: the lazy stack-entries fetch + render is slow on WebKit under
    // load.
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + rowId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Navigates to {@code /inventory/all?view=items} and expands the sync widget's group then its
   * single stack, waiting for the lazily-fetched entry leaf row to load — the item-view twin of
   * {@link InventorySharedLagerLiveSyncE2eTest}'s expansion. Blind clicks are safe here: each
   * live-sync context starts from the login-time storage state, so no expansion is ever restored.
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
   * Reports whether the Lager tree container matched by {@code selector} is collapsed, reading its
   * synchronously-set computed {@code display} rather than Playwright visibility — a restored-open
   * stack is {@code display: block} yet momentarily zero-height while its lazy leaf rows fetch, so
   * {@code isVisible()} would misread it as hidden and a blind toggle would collapse it.
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
   * Submits an open book-out / Umbuchen modal and waits for its in-place AJAX write to settle (both
   * post to {@code /inventory/{id}/transfer} and re-swap the grouped table on success — neither
   * path navigates). Sets the {@code window.__krtNoReload} marker, drops the {@code position:
   * fixed} footer out of the way (it can otherwise intercept the trusted click), waits on the XHR
   * POST so the backend has provably answered, and finally asserts the marker survived.
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
   * Selects, in the Umbuchen modal's transfer-target location combobox, the first option whose
   * value differs from the source location, and returns that destination id — the same cached-
   * option-set-tolerant pick as the material sibling's helper (a distinct option always exists
   * because the bootstrap refinery hub is cached while the source here is a fresh location).
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

  // --------------------------------------------------------------------------------------------
  // API verification helpers (read the same grouped catalog=ITEM endpoint the item views use)
  // --------------------------------------------------------------------------------------------

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
   * Resolves the caller's inventory row holding the given game item from the flat {@code
   * catalog=ITEM} list — the leaf-row anchor for the tree assertions after a UI book-in (the create
   * response body is consumed by the page, not the test).
   *
   * @param gameItemId the (scenario-unique) game item whose row to find
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
