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
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flows for the squadron Lager (REQ-INV-*): the six inventory operations a logistician
 * drives through the personal-Lager UI ({@code /inventory/my}) plus their guarding edge cases.
 *
 * <p>One method per operation — <em>einbuchen</em> (create), <em>ausbuchen</em> (DISCARD, partial
 * and full), <em>umbuchen</em> (TRANSFER), <em>verkaufen</em> (SELL), and the two append-only-safe
 * association edits, <em>zuweisen zu einem Auftrag</em> (job order) and <em>zuweisen zu einem
 * Einsatz</em> (mission) — plus the <em>Herkunft</em> deduct-from picker (REQ-INV-027), which both
 * gates a book-out when the rest cannot cover it and directs the deduction onto a chosen earmark,
 * and three edge cases: over-booking past the held amount, a no-op transfer to the same
 * user+location, and the cross-field invariant that a personal entry may carry neither a job order
 * nor a mission. The append-only model means TRANSFER and create insert new rows while DISCARD/SELL
 * decrement (and delete at the {@code 1e-4} epsilon), so each scenario uses its <strong>own unique
 * material</strong> to stay isolated in the shared, sequentially-run stack.
 *
 * <p><b>Drive via UI, verify via API.</b> Every mutation goes through the real Thymeleaf form /
 * book-out modal / allocation-chip combobox — i.e. the genuine frontend → backend → DB path. The
 * book-out / transfer / sell outcomes are then asserted by reading the same grouped endpoint the
 * {@code /inventory/my} view itself uses ({@code GET /api/v1/inventory/my-inventory/grouped?…})
 * through {@link BackendSeeder}, which is far more robust than re-expanding the lazily-loaded,
 * grouped tree table and never races the post-write render. The grouped query returns every row the
 * caller owns regardless of the {@code personal} flag, so the seeded non-personal rows surface
 * there. The two allocation scenarios instead assert the rendered chip, since the grouped endpoint
 * no longer carries the per-entry job-order / mission allocation (Variante C, REQ-INV-027).
 *
 * <p><b>Cache-awareness.</b> The create-form material/location dropdowns come from the frontend's
 * 10-minute cached lookups, so — like {@code JobOrderCreateE2eTest} — the create flow selects
 * whatever the dropdown offers and reads the picked id back for verification rather than assuming a
 * freshly-seeded entry is listed. The Umbuchen modal's transfer dropdown is likewise cached, so the
 * same-location edge case anchors its row at the bootstrap-seeded {@code E2E Refinery Hub} (always
 * cached) to make the Umbuchen modal preselect the source as the transfer target. The job-order and
 * mission lookups are <em>not</em> cached, so freshly seeded ones appear in the allocation-chip
 * combobox at once.
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
   * One authenticated session reused across every test in this class. The OIDC login is the suite's
   * documented flakiness hot-spot, so it runs once here instead of per test; each test still opens
   * its own {@link BrowserContext} from this storage state, so the flows stay isolated.
   */
  private static Path storageState;

  // Shared reference data (seeded once).
  private static String opsHubLocId;
  private static String refineryHubLocId;
  private static String assignOrderId;
  private static String missionId;

  // Per-scenario material + inventory-item ids (seeded once, one material per scenario).
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
  private static String assignMissionMatId;
  private static String assignMissionItemId;
  private static String herkunftMatId;
  private static String herkunftItemId;
  private static String herkunftOrderId;
  private static String overbookMatId;
  private static String overbookItemId;
  private static String sameLocMatId;
  private static String sameLocItemId;
  private static String viewStateMatId;
  private static String viewStateItemId;

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
    // Bootstrap catalog location (uex-catalog-seed.sql) — guaranteed in the cached location lookup.
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

    assignMissionMatId =
        seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Assign Mission Mat");
    assignMissionItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, assignMissionMatId, opsHubLocId, SEED_QUALITY, 100);

    // Deduct-from ("Herkunft") picker fixture: a job-order-eligible 100-SCU row plus an order that
    // requests it, so the flow can earmark 70 (rest 30) and then book out from the tag.
    herkunftMatId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Inv Herkunft Mat");
    herkunftItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, herkunftMatId, opsHubLocId, SEED_QUALITY, 100);
    herkunftOrderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Inv Herkunft Order", herkunftMatId, 650, 100);

    overbookMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Overbook Mat");
    overbookItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, overbookMatId, opsHubLocId, SEED_QUALITY, 50);

    sameLocMatId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Inv Same Loc Mat");
    // Anchored at the cached refinery hub so the book-out modal preselects it as the transfer
    // target, making an unmodified TRANSFER a same-user+same-location no-op the backend rejects.
    sameLocItemId =
        seeder.createInventoryItem(
            USERNAME, PASSWORD, sameLocMatId, refineryHubLocId, SEED_QUALITY, 50);

    // Isolated row for the tree view-state persistence test: a partial DISCARD leaves the row (and
    // its item id) in place, so the leaf must resurface after the post-write re-swap.
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

          // Material + location are searchable comboboxes; the enhancer's hidden input keeps the
          // original id, so the picked value is still read off #materialId.
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"));
          String pickedMaterialId = page.locator("#materialId").inputValue();
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#quality").fill(String.valueOf(SEED_QUALITY));
          page.locator("#amount").fill("42");

          double before = totalAmount(stacksForMaterial(pickedMaterialId));
          // #577: book-in is now an X-Requested-With AJAX twin (navigate-after-AJAX on success), so
          // wait on the XHR POST rather than a document navigation; keep the footer-clear so the
          // trusted click is not intercepted by the fixed footer.
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
   * <em>Umbuchen.</em> Transfers 30 of a 100-SCU row to a different location (same user) through
   * the dedicated Umbuchen modal's LOCATION mode (#868 moved the transfer out of the Ausbuchen
   * dialog). The append-only model leaves 70 at the source and inserts a fresh 30 at the
   * destination, so the material now spans two owned stacks. Also asserts the owner org-unit picker
   * renders preset to the row's owning unit (REQ-INV-007, #1328): its membership fetch must go
   * through the frontend's {@code /users/{id}/memberships} proxy — the former direct {@code
   * /api/v1/users/…} browser call had no frontend route, 404ed, and silently hid the picker.
   */
  @Test
  void umbuchenTransfersStockToAnotherLocation() {
    runFlow(
        "inventory-umbuchen",
        page -> {
          openUmbuchenModal(page, transferMatId, transferItemId);
          // #1328/REQ-INV-007: the "Buchen in OrgUnit" picker must render for an owner with at
          // least one membership (test-admin is seeded into IRIDIUM) and be preset to the row's
          // current owning org unit. It populates from an async membership fetch, so rely on the
          // assertion's auto-wait.
          assertThat(page.locator("#umbuchenTargetOwningOrgUnitWrapper")).isVisible();
          assertThat(page.locator("#umbuchenTargetOwningOrgUnitId")).hasValue(IRIDIUM_ID);
          // LOCATION mode is the Umbuchen modal's default; pick a destination distinct from source.
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
          // The SELL radio stays disabled until the modal's async terminals fetch resolves.
          assertThat(sellRadio)
              .isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(15_000));
          sellRadio.check();
          // Index 0 is the disabled "...wählen..." placeholder; index 1 is the seeded terminal.
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
   * <em>Herkunft-Picker (REQ-INV-027).</em> The Ausbuchen deduct-from picker both gates and directs
   * a book-out. Earmarks 70 of a 100-SCU row to a job order (rest 30), opens the book-out modal and
   * books out 50: with the order input left at 0 the 30 rest cannot cover the 50, so the picker
   * disables the submit and shows the "assign at least" warning; entering 40 into the order input
   * satisfies the plan and re-enables it. After the write the row holds 50 and the order chip has
   * shrunk from 70 to 30 — proving the 40 came out of the chosen tag (not silently from the rest),
   * the client-side mirror of {@code InventoryCheckoutService.resolveReductionPlan}.
   */
  @Test
  void herkunftPickerGatesTheRestAndDeductsFromTheChosenTag() {
    runFlow(
        "inventory-herkunft-picker",
        page -> {
          // Earmark 70 of the 100-SCU row to the job order (rest = 30).
          openMyInventoryToEntry(page, herkunftMatId, herkunftItemId);
          assignAllocationViaChip(page, herkunftItemId, "JOB_ORDER", herkunftOrderId, "70");

          // Re-open on a fresh page so the book-out modal builds its picker from the persisted
          // chip.
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

          // Book out 50: with the tag at 0 the 30 rest cannot cover it, so the picker gates the
          // submit and states the minimum that must go to the tag.
          page.locator("#amount").fill("50");
          assertThat(submit).isDisabled();
          assertThat(warn).isVisible();

          // Direct 40 of the 50 to the order tag — the plan is now valid and the submit re-enables.
          orderInput.fill("40");
          assertThat(submit).isEnabled();

          submitBookOutInPlace(page);

          // Stock dropped by the full 50 ...
          assertEquals(
              50.0, totalAmount(stacksForMaterial(herkunftMatId)), AMOUNT_DELTA, "100 - 50 = 50");
          // ... and exactly 40 came out of the order earmark (70 - 40 = 30), not the rest. The
          // partial book-out leaves the row, so the tree restores and the reduced chip re-renders.
          Locator orderChip =
              page.locator(
                  "div.assoc-split[data-entry-id='"
                      + herkunftItemId
                      + "'][data-assoc-field='JOB_ORDER'] [data-assoc-chip='jobOrder']"
                      + "[data-target-id='"
                      + herkunftOrderId
                      + "']");
          // 40 of the 50 booked out came from the order tag (70 - 40 = 30), not the rest. The
          // book-out re-swap re-renders the reduced chip asynchronously, so assert the chip's
          // data-amount with an auto-retrying matcher rather than reading it once: a slower browser
          // can otherwise still expose the pre-swap 70 at read time (firefox flake). The pattern
          // tolerates the Double's rendered forms (30 / 30.0 / 30.000).
          assertThat(orderChip)
              .hasAttribute(
                  "data-amount",
                  Pattern.compile("^30(\\.0+)?$"),
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
          // LOCATION mode is the Umbuchen modal's default; leave target user + location at their
          // preselected source values, then submit a no-op TRANSFER the backend rejects.
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

          // REQ-FE-016: the catalog pickers announce what they search — the material/location
          // comboboxes must carry their kind-specific placeholder, not the user-picker wording
          // (which contains neither "material" nor "Ort"/"location" in either locale).
          assertThat(page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"))
              .hasAttribute("placeholder", Pattern.compile("(?i).*material.*"));
          assertThat(page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"))
              .hasAttribute("placeholder", Pattern.compile("(?i).*(ort|location).*"));

          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#materialId) .krt-combobox__input"));
          E2eSupport.selectComboboxFirstOption(
              page.locator(".krt-combobox:has(#locationId) .krt-combobox__input"));
          page.locator("#quality").fill(String.valueOf(SEED_QUALITY));
          page.locator("#amount").fill("5");

          // Add a mission earmark via the split-at-check-in UI: index 0 is the placeholder, index 1
          // the seeded mission.
          page.locator("[data-trigger='inv-input-add-mission']").click();
          page.locator("#missionAllocRows [data-alloc-target]")
              .first()
              .selectOption(new SelectOption().setIndex(1));
          assertThat(page.locator("#missionAllocRows [data-alloc-row]")).hasCount(1);

          // Marking the entry personal hides and clears both allocation sections — a personal entry
          // can never carry an assignment.
          page.locator("#personal").check();

          assertThat(page.locator("#missionAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));
          assertThat(page.locator("#jobOrderAllocGroup"))
              .hasClass(Pattern.compile(".*krtm-hidden.*"));
          assertThat(page.locator("#missionAllocRows [data-alloc-row]")).hasCount(0);
        });
  }

  /**
   * REQ-INV-002 view-state persistence: a modal write must not collapse the tree the user was
   * working in. Expands the material group and its stack (so the leaf is loaded and visible), books
   * out a partial amount in place, and asserts the same leaf row is visible again <em>without any
   * manual re-expand</em> — the post-write grouped-table re-swap restores the persisted group +
   * stack expansion from {@code localStorage} and re-loads the entries. The assertion waits on the
   * restore-triggered stack-entries GET first, so the leaf is checked against the freshly
   * re-swapped DOM rather than the pre-swap one.
   */
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
          // The book-out re-swaps the grouped table, and the restore re-fetches this stack's
          // entries; wait for that post-write GET so the leaf is asserted against the restored DOM.
          page.waitForResponse(
              r -> r.url().contains("/stack/entries") && "GET".equals(r.request().method()),
              () -> page.locator("#bookOutSubmitBtn").click());

          assertEquals(
              Boolean.TRUE,
              page.evaluate("window.__krtNoReload === true"),
              "the in-place book-out must not reload the page");
          // 20 s, not the 5 s default: the re-swap + lazy stack-entries re-fetch is slow on WebKit
          // under CI load.
          assertThat(page.locator("div.tree-row--leaf[data-item-id='" + viewStateItemId + "']"))
              .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        });
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
   * rethrowing — the per-test boilerplate every method would otherwise repeat.
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
   * Navigates to {@code /inventory/my} and expands the material group then its single stack,
   * waiting for the lazily-fetched entry leaf row to appear. Material is unique per scenario, so
   * the group / stack / book-out / association selectors all resolve unambiguously by material or
   * item id.
   *
   * @param page the authenticated page
   * @param materialId the (scenario-unique) material whose group + stack to expand
   * @param itemId the seeded inventory item id whose leaf row signals the entries loaded
   */
  private static void openMyInventoryToEntry(Page page, String materialId, String itemId) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/my");
    page.waitForLoadState();
    // The Lager tree persists + restores its group / stack expansion per user in localStorage
    // (REQ-INV-002, restored on DOMContentLoaded), so a material opened earlier in the same flow
    // comes back already expanded. Each expansion is therefore idempotent: click to open only while
    // its container is still collapsed, since a blind toggle click on an already-open row would
    // collapse it (the deterministic failure when this helper ran twice for the same material).
    // The guard reads the container's computed display, not Playwright visibility: a restored-open
    // stack is display:block yet momentarily zero-height while its lazy leaf rows fetch, which
    // isVisible() would misread as hidden and wrongly collapse.
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
    // 20 s, not the 5 s default: the lazy stack-entries fetch + render is slow on WebKit under
    // load.
    assertThat(page.locator("div.tree-row--leaf[data-item-id='" + itemId + "']"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
  }

  /**
   * Reports whether the Lager tree container matched by {@code selector} is collapsed, reading its
   * synchronously-set computed {@code display} rather than Playwright visibility. A restored-open
   * stack is {@code display: block} yet momentarily zero-height while its lazy leaf rows fetch, so
   * {@code isVisible()} would misread it as hidden; the computed {@code display} is unambiguous.
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
   * Adds a Variante-C allocation chip to a stack entry via the inline "+ Zuordnen" combobox
   * (REQ-INV-027): opens the popover of the entry's {@code field} split, picks the target option in
   * the enhanced searchable combobox by its value, fills the amount and clicks Speichern, waiting
   * for the in-place {@code POST /inventory/{id}/allocation} to settle. Drops the fixed footer (it
   * can otherwise intercept the trusted clicks) and asserts the write did not reload the page.
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
    // The "+ Zuordnen" <select data-krt-combobox> is enhanced into a .krt-combobox: click the
    // textbox to open the listbox, then pick the option by its data-value (the target UUID, since
    // the visible label is the display id / mission name, not the id).
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
   * Expands to the row (see {@link #openMyInventoryToEntry}) and clicks its Umbuchen (rebook)
   * button, which opens the dedicated Umbuchen modal in its default LOCATION (transfer) mode
   * preloaded with that row's id, amount, version and preselected source user + location (#868
   * moved the transfer out of the Ausbuchen dialog into this modal).
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
   * Submits the open book-out modal and waits for its in-place AJAX write to settle (#577 part 2:
   * the book-out posts to {@code /inventory/{id}/transfer} and re-swaps the grouped table on
   * success, or surfaces a toast on a backend rejection — neither path navigates). Sets the {@code
   * window.__krtNoReload} marker and drops the {@code position: fixed} footer out of the way (it
   * can otherwise intercept the trusted click), then waits on the XHR POST so the backend has
   * provably answered before the caller reads the stock back. Finally asserts the marker survived,
   * proving the page was never reloaded.
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
   * Submits the open Umbuchen modal in its LOCATION (transfer) mode and waits for the in-place AJAX
   * write to settle. Like the book-out twin (#577 part 2, consolidated in #868), the transfer posts
   * to {@code /inventory/{id}/transfer} and re-swaps the grouped table on success, or surfaces a
   * toast on a backend rejection — neither path navigates. Sets the {@code window.__krtNoReload}
   * marker and drops the {@code position: fixed} footer out of the way (it can otherwise intercept
   * the trusted click), then waits on the XHR POST so the backend has provably answered before the
   * caller reads the stock back. Finally asserts the marker survived, proving the page was never
   * reloaded.
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
   * Selects, in the Umbuchen modal's transfer-target location combobox, the first option whose
   * value differs from the source location, and returns that destination id. The picker is a
   * searchable combobox (the native {@code <select>} is replaced and its options only render into
   * the {@code role=listbox} while the popup is open), so this opens the popup first and reads each
   * option's {@code data-value}. Robust to whether the source location is itself listed in the
   * (cached) option set: a different option always exists because the bootstrap refinery hub is
   * cached and the source here is a separately-created location.
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
  // API verification helpers (read the same grouped endpoint the /inventory/my view uses)
  // --------------------------------------------------------------------------------------------

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
