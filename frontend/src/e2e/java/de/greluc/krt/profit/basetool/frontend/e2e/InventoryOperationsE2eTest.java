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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flows for the inventory operations on {@code /inventory/my}: einbuchen, ausbuchen,
 * umbuchen, verkaufen, job-order and mission allocation, the Herkunft picker (REQ-INV-027), and
 * edge cases (over-booking, no-op transfer, personal entry with allocations).
 *
 * <p>Mutations are driven through the UI and verified via the grouped backend endpoint, or via the
 * rendered chip for allocations. Each scenario uses its own material for isolation. Cached
 * dropdowns are handled by selecting whatever they offer.
 */
@Tag("e2e")
class InventoryOperationsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Tolerance for SCU-amount equality assertions (the backend rounds to three decimals). */
  private static final double AMOUNT_DELTA = 0.001;

  /** Quality stamped on every seeded row; clears the job order's {@code minQuality} of 650. */
  private static final int SEED_QUALITY = 750;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /**
   * Authenticated storage state shared by all tests in this class; each test opens its own {@link
   * BrowserContext} from it.
   */
  private static Path storageState;

  private static String opsHubLocId;
  private static String refineryHubLocId;
  private static String assignOrderId;
  private static String missionId;

  private static String discardMatId;
  private static String discardItemId;
  private static String discardAllMatId;
  private static String discardAllItemId;
  private static String transferMatId;
  private static String transferItemId;
  private static String sellMatId;
  private static String sellItemId;
  private static String assignOrderMatId;
  private static String assignOrderItemId;

  /**
   * Own fixture for {@link #rePickingAnAllocatedOrderEditsItsSliceInsteadOfPosting()}: that test
   * allocates and then re-allocates on the same entry, so it must not share the {@code
   * assignOrder*} row whose amount another test asserts.
   */
  private static String rePickMatId;

  /** The 100-SCU entry {@link #rePickMatId} is booked in on. */
  private static String rePickItemId;

  /** The job order that entry is earmarked to, twice. */
  private static String rePickOrderId;

  private static String assignMissionMatId;
  private static String assignMissionItemId;
  private static String herkunftMatId;
  private static String herkunftItemId;
  private static String herkunftOrderId;
  private static String prefillMatId;
  private static String prefillItemId;
  private static String overbookMatId;
  private static String overbookItemId;
  private static String sameLocMatId;
  private static String sameLocItemId;
  private static String orgUnitMatId;
  private static String orgUnitItemId;
  private static String viewStateMatId;
  private static String viewStateItemId;

  private static String needMatId;
  private static String needOrderId;

  /**
   * Launches the browser, performs the single shared login, and (ephemeral stack only) seeds the
   * IRIDIUM membership plus one isolated material+row per scenario: a shared source location, the
   * bootstrap refinery hub for the same-location edge, a mission, a job order requesting the
   * assignment material, and a sell terminal for the SELL flow.
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

    opsHubLocId = seeder.createLocation(USERNAME, PASSWORD, "E2E Inv Ops Hub");
    refineryHubLocId = seeder.findLocationIdByName(USERNAME, PASSWORD, "E2E Refinery Hub");
    missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Inv Mission", true);

    discardMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Discard Partial Mat");
    discardItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, discardMatId, opsHubLocId, SEED_QUALITY, 100);

    discardAllMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Discard All Mat");
    discardAllItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, discardAllMatId, opsHubLocId, SEED_QUALITY, 50);

    transferMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Transfer Mat");
    transferItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, transferMatId, opsHubLocId, SEED_QUALITY, 100);

    sellMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Sell Mat");
    sellItemId =
        seeder.createInventoryItem(USERNAME, PASSWORD, sellMatId, opsHubLocId, SEED_QUALITY, 80);
    seeder.seedSellableTerminal(sellMatId);

    assignOrderMatId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Inv Assign Order Mat");
    assignOrderItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, assignOrderMatId, opsHubLocId, SEED_QUALITY, 100);
    assignOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Inv Assign Order", assignOrderMatId, 650, 100);

    rePickMatId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Inv Re-Pick Mat");
    rePickItemId =
        seeder.createInventoryItem(USERNAME, PASSWORD, rePickMatId, opsHubLocId, SEED_QUALITY, 100);
    rePickOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Inv Re-Pick Order", rePickMatId, 650, 100);

    needMatId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Inv Need Mat");
    needOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Inv Need Order", needMatId, 650, 400);

    assignMissionMatId =
        seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Assign Mission Mat");
    assignMissionItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, assignMissionMatId, opsHubLocId, SEED_QUALITY, 100);

    herkunftMatId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Inv Herkunft Mat");
    herkunftItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, herkunftMatId, opsHubLocId, SEED_QUALITY, 100);
    herkunftOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Inv Herkunft Order", herkunftMatId, 650, 100);

    prefillMatId =
        seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Herkunft Prefill Mat");
    prefillItemId =
        seeder.createInventoryItem(USERNAME, PASSWORD, prefillMatId, opsHubLocId, SEED_QUALITY, 60);

    overbookMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Overbook Mat");
    overbookItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, overbookMatId, opsHubLocId, SEED_QUALITY, 50);

    sameLocMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Same Loc Mat");
    sameLocItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, sameLocMatId, refineryHubLocId, SEED_QUALITY, 50);

    orgUnitMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Org Unit Mat");
    orgUnitItemId =
        seeder.createPersonalInventoryItem(
            USERNAME, PASSWORD, orgUnitMatId, opsHubLocId, SEED_QUALITY, 40);

    viewStateMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv View State Mat");
    viewStateItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, viewStateMatId, opsHubLocId, SEED_QUALITY, 100);
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
   * <em>Einbuchen.</em> Fills the create form ({@code /inventory/input?source=my}) with a
   * dropdown-offered material + location, a quality and an amount, submits, and asserts the owned
   * total for that material grew by exactly the entered amount. The amount is checked as a delta
   * (before vs. after) because the cached dropdown may offer a material that already holds stock.
   */
  @Test
  void einbuchenCreatesStockForThePickedMaterial() {
    runFlow(
        "inventory-einbuchen",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"));
          String pickedMaterialId = page.locator("#materialId").inputValue();
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#quality").fill(String.valueOf(SEED_QUALITY));
          page.locator("#amount").fill("42");

          double before = totalAmount(stacksForMaterial(pickedMaterialId));
          page.evaluate(
              "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                  + " 'none'; } }");
          page.waitForResponse(
              r -> r.url().contains("/inventory/input") && "POST".equals(r.request().method()),
              () -> page.locator("form[action$='/inventory/input'] button[type='submit']").click());
          double after = totalAmount(stacksForMaterial(pickedMaterialId));

          assertEquals(
              42.0, after - before, AMOUNT_DELTA, "created amount should add to the total");
        });
  }

  /**
   * <em>Ausbuchen (Teilmenge).</em> Books out 40 of a 100-SCU row as a plain DISCARD and asserts
   * the owned total drops to 60.
   */
  @Test
  void ausbuchenDiscardPartialReducesStock() {
    runFlow(
        "inventory-ausbuchen-partial",
        page -> {
          openBookOutModal(page, discardMatId, discardItemId);
          page.locator("input[name='type'][value='DISCARD']").check();
          page.locator("#amount").fill("40");
          submitBookOutInPlace(page);

          assertEquals(
              60.0, totalAmount(stacksForMaterial(discardMatId)), AMOUNT_DELTA, "100 - 40 = 60");
        });
  }

  /**
   * <em>Ausbuchen (Vollmenge).</em> Books out the entire 50-SCU row as a DISCARD; the
   * post-decrement amount falls below the deletion epsilon, so the stack vanishes from the owned
   * Lager entirely.
   */
  @Test
  void ausbuchenDiscardAllRemovesTheStack() {
    runFlow(
        "inventory-ausbuchen-all",
        page -> {
          openBookOutModal(page, discardAllMatId, discardAllItemId);
          page.locator("input[name='type'][value='DISCARD']").check();
          page.locator("#amount").fill("50");
          submitBookOutInPlace(page);

          assertEquals(
              0,
              stackCount(stacksForMaterial(discardAllMatId)),
              "fully discarded stack is removed");
        });
  }

  /**
   * Umbuchen: transfers 30 of a 100-SCU row to another location, leaving 70 at the source and a new
   * 30-SCU stack at the destination. Also asserts the owner org-unit picker renders preset to the
   * row's owning unit (REQ-INV-007).
   */
  @Test
  void umbuchenTransfersStockToAnotherLocation() {
    runFlow(
        "inventory-umbuchen",
        page -> {
          openUmbuchenModal(page, transferMatId, transferItemId);
          assertThat(page.locator("#umbuchenTargetOwningOrgUnitWrapper")).isVisible();
          assertThat(page.locator("#umbuchenTargetOwningOrgUnitId")).hasValue(IRIDIUM_ID);
          String destinationLocationId = selectDifferentUmbuchenLocation(page, opsHubLocId);
          page.locator("#umbuchenAmount").fill("30");
          submitUmbuchenInPlace(page);

          JsonArray stacks = stacksForMaterial(transferMatId);
          assertEquals(2, stackCount(stacks), "transfer splits the row into source + destination");
          assertEquals(
              70.0, amountAtLocation(stacks, opsHubLocId), AMOUNT_DELTA, "source keeps 70");
          assertEquals(
              30.0,
              amountAtLocation(stacks, destinationLocationId),
              AMOUNT_DELTA,
              "destination receives 30");
        });
  }

  /**
   * <em>Verkaufen.</em> Sells 30 of an 80-SCU row at the seeded terminal. The seeded {@code
   * material_price} enables the otherwise-disabled SELL radio; the row drops to 50 (no mission
   * link, so no finance entry is involved).
   */
  @Test
  void verkaufenSellsStockAndReducesIt() {
    runFlow(
        "inventory-verkaufen",
        page -> {
          openBookOutModal(page, sellMatId, sellItemId);
          Locator sellRadio = page.locator("input[name='type'][value='SELL']");
          assertThat(sellRadio)
              .isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(15_000));
          sellRadio.check();
          page.locator("#terminal").selectOption(new SelectOption().setIndex(1));
          page.locator("#sellAmount").fill("1500");
          page.locator("#amount").fill("30");
          submitBookOutInPlace(page);

          assertEquals(
              50.0, totalAmount(stacksForMaterial(sellMatId)), AMOUNT_DELTA, "80 - 30 = 50");
        });
  }

  /**
   * <em>Zuweisen zu einem Auftrag.</em> Adds a job-order allocation chip on the entry (Variante C,
   * REQ-INV-027): opens the "+ Zuordnen" combobox, picks the order, enters the amount and saves (an
   * AJAX {@code POST /inventory/{id}/allocation}). The entry's Auftrag split then shows the order's
   * chip, re-rendered in place from the returned DTO without a reload.
   */
  @Test
  void zuweisenAssignsStockToAJobOrder() {
    runFlow(
        "inventory-zuweisen-auftrag",
        page -> {
          openMyInventoryToEntry(page, assignOrderMatId, assignOrderItemId);
          assignAllocationViaChip(page, assignOrderItemId, "JOB_ORDER", assignOrderId, "100");

          assertThat(
                  page.locator(
                      "div.assoc-split[data-entry-id='"
                          + assignOrderItemId
                          + "'][data-assoc-field='JOB_ORDER']"
                          + " [data-assoc-chip='jobOrder'][data-target-id='"
                          + assignOrderId
                          + "']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        });
  }

  /**
   * Picking an order already allocated on the entry opens its existing slice in edit mode and saves
   * with a {@code PATCH} instead of posting a duplicate (REQ-INV-027). Asserts the stale option is
   * still offered, as the test's precondition.
   */
  @Test
  void rePickingAnAllocatedOrderEditsItsSliceInsteadOfPosting() {
    runFlow(
        "inventory-zuweisen-erneut",
        page -> {
          openMyInventoryToEntry(page, rePickMatId, rePickItemId);
          assignAllocationViaChip(page, rePickItemId, "JOB_ORDER", rePickOrderId, "40");

          Locator split =
              page.locator(
                  "div.assoc-split[data-entry-id='"
                      + rePickItemId
                      + "'][data-assoc-field='JOB_ORDER']");
          assertThat(
                  split.locator(
                      "[data-assoc-chip='jobOrder'][data-target-id='" + rePickOrderId + "']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));

          split.locator("button[data-trigger='inv-my-assoc-add-open']").click();
          Locator pop = split.locator("[data-assoc-pop]");
          pop.locator(".krt-combobox__input").click();
          Locator option =
              pop.locator("li.krt-combobox__option[data-value='" + rePickOrderId + "']");
          assertThat(option).isVisible();

          option.click();
          assertEquals(
              "edit",
              pop.getAttribute("data-assoc-mode"),
              "a target already on the entry must open its slice, not arm a duplicate add");
          assertThat(pop.locator("[data-assoc-amount-input]")).hasValue("40");
          assertThat(pop.locator("button[data-trigger='inv-my-assoc-remove']")).isVisible();

          pop.locator("[data-assoc-amount-input]").fill("60");
          Response response =
              page.waitForResponse(
                  r -> r.url().contains("/allocation"),
                  () -> pop.locator("button[data-trigger='inv-my-assoc-save']").click());
          assertEquals("PATCH", response.request().method(), "editing a slice is a PATCH");
          assertEquals(200, response.status(), "and it is not the duplicate-target 400");
        });
  }

  /**
   * <em>Zuweisen zu einem Einsatz.</em> Adds a mission allocation chip on the entry (Variante C,
   * REQ-INV-027) through the same "+ Zuordnen" combobox → {@code POST /inventory/{id}/allocation};
   * the entry's Einsatz split then shows the mission's chip.
   */
  @Test
  void zuweisenAssignsStockToAMission() {
    runFlow(
        "inventory-zuweisen-einsatz",
        page -> {
          openMyInventoryToEntry(page, assignMissionMatId, assignMissionItemId);
          assignAllocationViaChip(page, assignMissionItemId, "MISSION", missionId, "100");

          assertThat(
                  page.locator(
                      "div.assoc-split[data-entry-id='"
                          + assignMissionItemId
                          + "'][data-assoc-field='MISSION']"
                          + " [data-assoc-chip='mission'][data-target-id='"
                          + missionId
                          + "']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        });
  }

  /**
   * Herkunft picker (REQ-INV-027): with 70 of 100 SCU earmarked, booking out 50 is blocked until 40
   * is assigned to the order; afterwards the row holds 50 and the order chip shows 30.
   */
  @Test
  void herkunftPickerGatesTheRestAndDeductsFromTheChosenTag() {
    runFlow(
        "inventory-herkunft-picker",
        page -> {
          openMyInventoryToEntry(page, herkunftMatId, herkunftItemId);
          assignAllocationViaChip(page, herkunftItemId, "JOB_ORDER", herkunftOrderId, "70");

          openBookOutModal(page, herkunftMatId, herkunftItemId);
          page.locator("input[name='type'][value='DISCARD']").check();

          Locator submit = page.locator("#bookOutSubmitBtn");
          Locator orderInput =
              page.locator(
                  "#bookOutModal [data-herkunft-dim='JOB_ORDER']"
                      + " [data-herkunft-input][data-herkunft-target='"
                      + herkunftOrderId
                      + "']");
          Locator warn =
              page.locator("#bookOutModal [data-herkunft-dim='JOB_ORDER'] [data-herkunft-warn]");

          page.locator("#amount").fill("50");
          assertThat(submit).isDisabled();
          assertThat(warn).isVisible();

          orderInput.fill("40");
          assertThat(submit).isEnabled();

          submitBookOutInPlace(page);

          assertEquals(
              50.0, totalAmount(stacksForMaterial(herkunftMatId)), AMOUNT_DELTA, "100 - 50 = 50");
          Locator orderChip =
              page.locator(
                  "div.assoc-split[data-entry-id='"
                      + herkunftItemId
                      + "'][data-assoc-field='JOB_ORDER'] [data-assoc-chip='jobOrder']"
                      + "[data-target-id='"
                      + herkunftOrderId
                      + "']");
          assertThat(orderChip)
              .hasAttribute(
                  "data-amount",
                  Pattern.compile("^30(\\.0+)?$"),
                  new LocatorAssertions.HasAttributeOptions().setTimeout(20_000));
        });
  }

  /**
   * Herkunft prefill (REQ-INV-027): with the whole row earmarked to one mission, the Umbuchen
   * picker prefills and locks the mission field and re-syncs it on an amount change; transferring
   * 40 shrinks the mission chip from 60 to 20.
   */
  @Test
  void herkunftPickerPrefillsAndLocksTheOnlyTagWhenThereIsNoRest() {
    runFlow(
        "inventory-herkunft-prefill",
        page -> {
          openMyInventoryToEntry(page, prefillMatId, prefillItemId);
          assignAllocationViaChip(page, prefillItemId, "MISSION", missionId, "60");

          openUmbuchenModal(page, prefillMatId, prefillItemId);
          String dimSelector = "#umbuchenModal [data-herkunft-dim='MISSION']";
          Locator missionInput =
              page.locator(
                  dimSelector + " [data-herkunft-input][data-herkunft-target='" + missionId + "']");

          assertThat(missionInput).hasValue("60");
          assertThat(missionInput).not().isEditable();
          assertThat(page.locator(dimSelector + " [data-herkunft-auto-note]")).isVisible();
          assertThat(page.locator(dimSelector + " [data-herkunft-warn]")).isHidden();
          assertThat(page.locator("#umbuchenSubmitBtn")).isEnabled();

          String destinationLocationId = selectDifferentUmbuchenLocation(page, opsHubLocId);
          page.locator("#umbuchenAmount").fill("40");
          assertThat(missionInput).hasValue("40");
          assertThat(page.locator("#umbuchenSubmitBtn")).isEnabled();

          submitUmbuchenInPlace(page);

          JsonArray stacks = stacksForMaterial(prefillMatId);
          assertEquals(
              20.0, amountAtLocation(stacks, opsHubLocId), AMOUNT_DELTA, "source keeps 60 - 40");
          assertEquals(
              40.0,
              amountAtLocation(stacks, destinationLocationId),
              AMOUNT_DELTA,
              "destination receives the transferred 40");
          Locator missionChip =
              page.locator(
                  "div.assoc-split[data-entry-id='"
                      + prefillItemId
                      + "'][data-assoc-field='MISSION'] [data-assoc-chip='mission']"
                      + "[data-target-id='"
                      + missionId
                      + "']");
          assertThat(missionChip)
              .hasAttribute(
                  "data-amount",
                  Pattern.compile("^20(\\.0+)?$"),
                  new LocatorAssertions.HasAttributeOptions().setTimeout(20_000));
        });
  }

  /**
   * Edge case: booking out more than is held is rejected by the backend (the SCU input only guards
   * "&gt; 0", not the held maximum); the in-place book-out surfaces the error as a toast without
   * navigating, and the 50-SCU row is left untouched.
   */
  @Test
  void edgeCaseBookingOutMoreThanAvailableLeavesStockUnchanged() {
    runFlow(
        "inventory-overbook",
        page -> {
          openBookOutModal(page, overbookMatId, overbookItemId);
          page.locator("input[name='type'][value='DISCARD']").check();
          page.locator("#amount").fill("999");
          submitBookOutInPlace(page);

          assertEquals(
              50.0,
              totalAmount(stacksForMaterial(overbookMatId)),
              AMOUNT_DELTA,
              "an over-booking must not change the held amount");
        });
  }

  /**
   * Edge case: an Umbuchen LOCATION transfer that changes neither the owner nor the location (the
   * Umbuchen modal's preselected source defaults) is rejected by the backend, so the single 50-SCU
   * stack stays intact.
   */
  @Test
  void edgeCaseTransferToSameLocationLeavesStockUnchanged() {
    runFlow(
        "inventory-transfer-noop",
        page -> {
          openUmbuchenModal(page, sameLocMatId, sameLocItemId);
          page.locator("#umbuchenAmount").fill("10");
          submitUmbuchenInPlace(page);

          JsonArray stacks = stacksForMaterial(sameLocMatId);
          assertEquals(1, stackCount(stacks), "a no-op transfer must not split the row");
          assertEquals(
              50.0,
              amountAtLocation(stacks, refineryHubLocId),
              AMOUNT_DELTA,
              "a rejected transfer must not change the held amount");
        });
  }

  /**
   * The check-in order option shows the order's outstanding need (REQ-INV-039), and additionally a
   * marker when the entered quality is below the order's floor.
   */
  @Test
  void checkInOrderOptionStatesTheOutstandingNeedAndTheQualityFloor() {
    runFlow(
        "inventory-order-need-label",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          page.locator("#quality").fill("700");
          E2eSupport.selectComboboxByValue(
              page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"),
              needMatId,
              "E2E Inv Need Mat");
          page.locator("[data-trigger='inv-input-add-order']").click();

          Locator option =
              page.locator(
                  "#jobOrderAllocRows [data-alloc-target] option[value='" + needOrderId + "']");
          assertThat(option).hasCount(1);
          assertThat(option)
              .hasText(
                  Pattern.compile(".*·\\s*noch\\s*400.*"),
                  new LocatorAssertions.HasTextOptions().setTimeout(10_000));
          assertThat(option).not().hasText(Pattern.compile(".*benötigt.*"));

          page.locator("#quality").fill("100");
          assertThat(option)
              .hasText(
                  Pattern.compile(".*benötigt\s*650.*"),
                  new LocatorAssertions.HasTextOptions().setTimeout(10_000));
          assertThat(option).hasText(Pattern.compile(".*·\\s*noch\\s*400.*"));
        });
  }

  /**
   * Edge case: a personal entry cannot carry an assignment (REQ-INV, REQ-INV-027). Since Variante C
   * the create form splits at check-in via repeatable allocation rows; ticking "personal" hides and
   * clears those sections, so the invariant is enforced in the UI (the backend also rejects a
   * personal+assignment create with a 422, covered by the controller unit test).
   */
  @Test
  void edgeCasePersonalEntryCannotCarryAnAssignment() {
    runFlow(
        "inventory-personal-assignment",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/input?source=my");
          page.waitForLoadState();

          assertThat(page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"))
              .hasAttribute(
                  "placeholder", Pattern.compile(".*material.*", Pattern.CASE_INSENSITIVE));
          assertThat(page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"))
              .hasAttribute(
                  "placeholder", Pattern.compile(".*(ort|location).*", Pattern.CASE_INSENSITIVE));

          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"));
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#quality").fill(String.valueOf(SEED_QUALITY));
          page.locator("#amount").fill("5");

          page.locator("[data-trigger='inv-input-add-mission']").click();
          page.locator("#missionAllocRows [data-alloc-target]")
              .first()
              .selectOption(new SelectOption().setIndex(1));
          assertThat(page.locator("#missionAllocRows [data-alloc-row]")).hasCount(1);

          page.locator("#personal").check();

          assertThat(page.locator("#missionAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));
          assertThat(page.locator("#jobOrderAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));
          assertThat(page.locator("#missionAllocRows [data-alloc-row]")).hasCount(0);
        });
  }

  /**
   * A partial in-place book-out keeps the expanded group and stack open, with the leaf row visible
   * again without manual re-expansion (REQ-INV-002).
   */
  /**
   * <em>Einheit ändern</em> (REQ-INV-052). Moves a personal row to „Keine Einheit" and back to the
   * member's Staffel through the row action, each in place, and checks the stack's owning unit
   * through the API after each step.
   */
  @Test
  void changingAPersonalRowsOrgUnitWorksBothWaysInPlace() {
    runFlow(
        "inventory-org-unit-change",
        page -> {
          openMyInventoryToEntry(page, orgUnitMatId, orgUnitItemId);
          submitOrgUnitChange(page, orgUnitItemId, "");
          assertTrue(
              owningUnitIdsOf(stacksForMaterial(orgUnitMatId)).contains("none"),
              "the row carries no unit");

          openMyInventoryToEntry(page, orgUnitMatId, orgUnitItemId);
          submitOrgUnitChange(page, orgUnitItemId, IRIDIUM_ID);
          assertTrue(
              owningUnitIdsOf(stacksForMaterial(orgUnitMatId)).contains(IRIDIUM_ID),
              "the row carries the Staffel again");
        });
  }

  @Test
  void inPlaceBookOutKeepsTheExpandedTreeState() {
    runFlow(
        "inventory-viewstate-persist",
        page -> {
          openBookOutModal(page, viewStateMatId, viewStateItemId);
          page.locator("input[name='type'][value='DISCARD']").check();
          page.locator("#amount").fill("10");

          page.evaluate("window.__krtNoReload = true;");
          page.evaluate(
              "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                  + " 'none'; } }");
          page.waitForResponse(
              r -> r.url().contains("/stack/entries") && "GET".equals(r.request().method()),
              () -> page.locator("#bookOutSubmitBtn").click());

          assertEquals(
              Boolean.TRUE,
              page.evaluate("window.__krtNoReload === true"),
              "the in-place book-out must not reload the page");
          assertThat(page.locator("div.tree-row--leaf[data-item-id='" + viewStateItemId + "']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        });
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
   * Runs {@code flow} in a fresh authenticated context and page; on failure dumps a screenshot and
   * HTML under {@code build/e2e/<label>-failure.*} and rethrows.
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
   * Navigates to {@code /inventory/my} and expands the material group and its stack until the entry
   * leaf row appears.
   *
   * @param page the authenticated page
   * @param materialId the scenario-unique material whose group and stack to expand
   * @param itemId the seeded inventory item id whose leaf row signals the entries loaded
   */
  private static void openMyInventoryToEntry(Page page, String materialId, String itemId) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/my");
    page.waitForLoadState();
    Locator groupRow = page.locator("div.tree-row--group[data-material-id='" + materialId + "']");
    assertThat(groupRow).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
    if (isCollapsed(
        page,
        "div.tree-row--group[data-material-id='" + materialId + "'] + div.tree-group-items")) {
      groupRow.click();
    }
    Locator stackHeader = page.locator("div.stack-header[data-material-id='" + materialId + "']");
    assertThat(stackHeader).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
    if (isCollapsed(
        page, "div.stack-header[data-material-id='" + materialId + "'] + div.tree-stack-entries")) {
      stackHeader.click();
    }
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + itemId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Reports whether the Lager tree container matched by {@code selector} is collapsed, judged by
   * its computed {@code display}, not Playwright visibility.
   *
   * @param page the authenticated page
   * @param selector the CSS selector of the group-items / stack-entries container
   * @return {@code true} when the container is absent or {@code display: none}, {@code false} when
   *     it is shown
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
   * Adds an allocation chip to a stack entry through the "+ Zuordnen" combobox (REQ-INV-027) and
   * waits for the in-place {@code POST /inventory/{id}/allocation}, asserting no page reload.
   *
   * @param page the authenticated page expanded to the entry (see {@link #openMyInventoryToEntry})
   * @param itemId the entry whose split to edit
   * @param field the allocation dimension, {@code JOB_ORDER} or {@code MISSION}
   * @param targetId the job-order / mission id to allocate (the combobox option value)
   * @param amount the amount to allocate (must not exceed the entry's amount)
   */
  private static void assignAllocationViaChip(
      Page page, String itemId, String field, String targetId, String amount) {
    Locator split =
        page.locator(
            "div.assoc-split[data-entry-id='" + itemId + "'][data-assoc-field='" + field + "']");
    page.evaluate("window.__krtNoReload = true;");
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    split.locator("button[data-trigger='inv-my-assoc-add-open']").click();
    Locator pop = split.locator("[data-assoc-pop]");
    pop.locator(".krt-combobox__input").click();
    pop.locator("li.krt-combobox__option[data-value='" + targetId + "']").click();
    pop.locator("[data-assoc-amount-input]").fill(amount);
    page.waitForResponse(
        r -> r.url().contains("/allocation") && "POST".equals(r.request().method()),
        () -> pop.locator("button[data-trigger='inv-my-assoc-save']").click());
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the in-place allocation write must not reload the page");
  }

  /**
   * Expands to the row (see {@link #openMyInventoryToEntry}) and clicks its book-out button, which
   * opens the shared book-out modal preloaded with that row's id, amount, version and location.
   *
   * @param page the authenticated page
   * @param materialId the scenario-unique material of the row
   * @param itemId the seeded inventory item id to book out
   */
  private static void openBookOutModal(Page page, String materialId, String itemId) {
    openMyInventoryToEntry(page, materialId, itemId);
    page.locator("button[data-trigger='inv-my-bookout'][data-id='" + itemId + "']").click();
    assertThat(page.locator("#bookOutModal")).isVisible();
  }

  /**
   * Expands to the row (see {@link #openMyInventoryToEntry}) and opens its Umbuchen modal in
   * LOCATION mode, preloaded with the row's data.
   *
   * @param page the authenticated page
   * @param materialId the scenario-unique material of the row
   * @param itemId the seeded inventory item id to rebook
   */
  private static void openUmbuchenModal(Page page, String materialId, String itemId) {
    openMyInventoryToEntry(page, materialId, itemId);
    page.locator("button[data-trigger='inv-my-umbuchen'][data-id='" + itemId + "']").click();
    assertThat(page.locator("#umbuchenModal")).isVisible();
  }

  /**
   * Submits the open book-out modal, waits for its {@code POST /inventory/{id}/transfer} to answer,
   * and asserts the page was not reloaded.
   *
   * @param page the authenticated page with the book-out modal open and filled
   */
  private static void submitBookOutInPlace(Page page) {
    page.evaluate("window.__krtNoReload = true;");
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/transfer") && "POST".equals(r.request().method()),
        () -> page.locator("#bookOutSubmitBtn").click());
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the in-place book-out must not reload the page");
  }

  /**
   * Submits the open Umbuchen modal in LOCATION mode, waits for its {@code POST
   * /inventory/{id}/transfer} to answer, and asserts the page was not reloaded.
   *
   * @param page the authenticated page with the Umbuchen modal open and filled
   */
  private static void submitUmbuchenInPlace(Page page) {
    page.evaluate("window.__krtNoReload = true;");
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/transfer") && "POST".equals(r.request().method()),
        () -> page.locator("#umbuchenSubmitBtn").click());
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the in-place Umbuchen must not reload the page");
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
   * Opens the org-unit change dialog of a personal row, picks {@code orgUnitId} (empty for no
   * unit), submits it, waits for {@code POST /inventory/{id}/org-unit} and asserts no page reload.
   *
   * @param page the authenticated page expanded to the entry
   * @param itemId the personal row
   * @param orgUnitId the unit to pick, or {@code ""} for no unit
   */
  private static void submitOrgUnitChange(Page page, String itemId, String orgUnitId) {
    page.locator("button[data-trigger='inv-my-org-unit'][data-id='" + itemId + "']").click();
    assertThat(page.locator("#orgUnitChangeModal")).isVisible();
    Locator select = page.locator("#orgUnitChangeTarget");
    assertThat(select.locator("option[value='" + IRIDIUM_ID + "']")).hasCount(1);
    select.selectOption(orgUnitId);
    page.evaluate("window.__krtNoReload = true;");
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/org-unit") && "POST".equals(r.request().method()),
        () -> page.locator("#orgUnitChangeSubmitBtn").click());
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the in-place org-unit change must not reload the page");
  }

  /**
   * Collects the owning-unit ids of the given stacks, {@code none} for a stack without one.
   *
   * @param stacks the stacks of one material
   * @return the owning-unit ids
   */
  private static java.util.Set<String> owningUnitIdsOf(JsonArray stacks) {
    java.util.Set<String> ids = new java.util.HashSet<>();
    stacks.forEach(
        s -> {
          com.google.gson.JsonElement unit = s.getAsJsonObject().get("owningSquadron");
          ids.add(
              unit == null || unit.isJsonNull()
                  ? "none"
                  : unit.getAsJsonObject().get("id").getAsString());
        });
    return ids;
  }

  /**
   * Fetches the owned ("my") grouped Lager for one material and returns that material's stacks as a
   * JSON array (empty when the material holds no owned stock).
   *
   * @param materialId the material to query
   * @return the material's stack array, or an empty array
   */
  private static JsonArray stacksForMaterial(String materialId) {
    String body =
        seeder.getBody(
            USERNAME, PASSWORD, "/api/v1/inventory/my-inventory/grouped?materialIds=" + materialId);
    JsonArray groups = JsonParser.parseString(body).getAsJsonArray();
    return groups.isEmpty()
        ? new JsonArray()
        : groups.get(0).getAsJsonObject().getAsJsonArray("stacks");
  }

  /**
   * Sums the {@code totalAmount} across all given stacks.
   *
   * @param stacks the stacks of one material
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
   * @param stacks the stacks of one material
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

  /**
   * Counts the stacks of a material.
   *
   * @param stacks the stacks of one material
   * @return the number of stacks
   */
  private static int stackCount(JsonArray stacks) {
    return stacks.size();
  }
}
