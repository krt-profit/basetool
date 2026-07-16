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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Anonymous (unauthenticated) Job-Order creation (UC-12): a guest fills the public request form at
 * {@code /orders/create} and chooses both org-unit pickers.
 *
 * <ul>
 *   <li><b>Auftraggeber / requesting</b> — offers <em>every</em> active org unit (Staffeln + SKs),
 *       with no profit restriction; any of them may be the customer.
 *   <li><b>Bearbeitende Einheit / responsible</b> — offers only <em>profit-eligible</em> org units
 *       (Staffeln + SKs) and is pre-selected to the configured intake Spezialkommando ({@code
 *       job_order.intake_special_command_id}). A guest may override it with any other
 *       profit-eligible unit; the backend honours that pick and otherwise (absent / non-profit /
 *       unresolvable) routes the order to the intake SK — a guest can never direct work to a
 *       non-profit unit.
 * </ul>
 *
 * <p>Covers both order kinds (material + item) through the UI, plus the two responsible-fallback
 * edge cases that the UI cannot reach (a non-profit or omitted responsible) via a direct anonymous
 * API call. The guest cannot read the order back (the queue is gated), so each UI flow verifies the
 * persisted responsible / requesting units through an admin read-back ({@link
 * BackendSeeder#findOrderByHandle}); the API edges assert the (guest-redacted but org-unit-bearing)
 * create response directly.
 *
 * <p>Preconditions are seeded once ({@link #setUp()}): a profit-eligible SK that is also wired as
 * the intake SK, a non-profit SK (offered only as a requester), a job-order material, and — at
 * stack bootstrap ({@link E2eStackExtension}) — one orderable item for the item picker. IRIDIUM is
 * the profit-eligible Staffel opted in at bootstrap.
 */
@Tag("e2e")
class AnonymousJobOrderE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";
  private static final String INTAKE_SETTING_KEY = "job_order.intake_special_command_id";

  private static Playwright playwright;
  private static Browser browser;

  /** A profit-eligible SK; also wired as the intake SK, so it is the responsible-picker default. */
  private static String intakeProfitSkId;

  /** A non-profit-eligible SK: offered as a requester, but excluded from the responsible picker. */
  private static String nonProfitSkId;

  /** A job-order material id for the direct-API edge cases. */
  private static String materialId;

  /**
   * Launches the browser and seeds the preconditions: a profit-eligible SK (also set as the intake
   * SK), a non-profit SK, and a job-order material. The orderable item the item flow needs is
   * seeded at stack bootstrap so it precedes the frontend's item-catalog cache warming.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      intakeProfitSkId =
          seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Anon Intake SK", "EAIN");
      seeder.setSpecialCommandProfitEligible(ADMIN_USER, ADMIN_PASSWORD, intakeProfitSkId, true);
      nonProfitSkId =
          seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Anon Combat SK", "EACB");
      seeder.setSystemSetting(ADMIN_USER, ADMIN_PASSWORD, INTAKE_SETTING_KEY, intakeProfitSkId);
      materialId = seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Anon Material");
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
   * Material order via the UI as an anonymous guest: asserts the two pickers' contents and the
   * intake preselection, then chooses a (non-profit) requester and overrides the responsible to a
   * profit Staffel (IRIDIUM). An admin read-back confirms the order persisted with exactly those
   * units.
   */
  @Test
  void anonymousCreatesMaterialOrderChoosingRequesterAndResponsible() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded SKs / intake setting");
    String baseUrl = STACK.baseUrl();
    String handle = "E2E Anon Material " + UUID.randomUUID();
    try (BrowserContext context = anonymousContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");

        // Requesting picker offers every active org unit, including the non-profit SK.
        assertThat(page.locator("#requestingOrgUnitId option[value='" + nonProfitSkId + "']"))
            .hasCount(1);
        // Responsible picker offers profit-eligible units only: the profit SK + IRIDIUM are
        // present,
        // the non-profit SK is absent — and the intake (profit) SK is pre-selected.
        assertThat(page.locator("#responsibleOrgUnitId option[value='" + intakeProfitSkId + "']"))
            .hasCount(1);
        assertThat(page.locator("#responsibleOrgUnitId option[value='" + IRIDIUM_ID + "']"))
            .hasCount(1);
        assertThat(page.locator("#responsibleOrgUnitId option[value='" + nonProfitSkId + "']"))
            .hasCount(0);
        assertThat(page.locator("#responsibleOrgUnitId")).hasValue(intakeProfitSkId);

        // Choose a non-profit requester and override the responsible to the IRIDIUM Staffel.
        page.locator("#requestingOrgUnitId").selectOption(nonProfitSkId);
        page.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#handle").fill(handle);
        E2eSupport.selectComboboxFirstOption(page.getByTestId("order-material-select"));
        page.getByTestId("order-material-amount").fill("50");
        E2eSupport.clickSubmitClearingFooter(page.getByTestId("order-submit"));
        page.waitForLoadState();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "anon-joborder-material");
        throw failure;
      }
    }

    JsonObject order = new BackendSeeder().findOrderByHandle(ADMIN_USER, ADMIN_PASSWORD, handle);
    assertNotNull(order, "anonymous material order must be persisted and readable by an admin");
    assertEquals("MATERIAL", order.get("type").getAsString());
    assertEquals(IRIDIUM_ID, orgUnitId(order, "responsibleOrgUnit"), "chosen profit responsible");
    assertEquals(nonProfitSkId, orgUnitId(order, "requestingOrgUnit"), "chosen requester");
  }

  /**
   * Item order via the UI as an anonymous guest: switches to item mode, confirms the item form's
   * responsible picker is pre-selected to the intake SK, chooses a Staffel requester, leaves the
   * responsible at the intake default, adds one item line and submits. An admin read-back confirms
   * the {@code ITEM} order persisted with the intake (default) responsible and the chosen
   * requester.
   */
  @Test
  void anonymousCreatesItemOrderWithDefaultResponsibleAndChosenRequester() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded SKs / intake setting / item");
    String baseUrl = STACK.baseUrl();
    String handle = "E2E Anon Item " + UUID.randomUUID();
    try (BrowserContext context = anonymousContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        page.getByTestId("order-mode-item").check();

        // The item form's responsible picker is pre-selected to the configured intake SK.
        assertThat(page.locator("#item-responsibleOrgUnitId")).hasValue(intakeProfitSkId);

        // Choose a Staffel requester; leave the responsible at the intake default.
        page.locator("#item-requestingOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#item-handle").fill(handle);

        // Pick whatever orderable item the (bootstrap-seeded, frontend-cached) picker offers. The
        // item picker is now a searchable combobox (krtSearchableSelect): open it and pick the
        // first offered option, then wait until the blueprint + material derivation renders the
        // first material's quality control. That control appears with the hidden materialId and
        // the (already-set) blueprintId the create payload needs.
        page.getByTestId("order-item-combobox").first().click();
        page.locator("li[role='option']").first().click();
        page.locator("select[name='items[0].materials[0].quality']").waitFor();

        E2eSupport.clickSubmitClearingFooter(page.getByTestId("order-item-submit"));
        page.waitForLoadState();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "anon-joborder-item");
        throw failure;
      }
    }

    JsonObject order = new BackendSeeder().findOrderByHandle(ADMIN_USER, ADMIN_PASSWORD, handle);
    assertNotNull(order, "anonymous item order must be persisted and readable by an admin");
    assertEquals("ITEM", order.get("type").getAsString());
    assertEquals(
        intakeProfitSkId, orgUnitId(order, "responsibleOrgUnit"), "default intake responsible");
    assertEquals(IRIDIUM_ID, orgUnitId(order, "requestingOrgUnit"), "chosen requester");
  }

  /**
   * Edge the UI cannot trigger (the responsible picker offers profit-eligible units only): a direct
   * anonymous API create naming a <em>non-profit</em> SK as the responsible unit is not rejected —
   * the order falls back to the configured intake SK. The requester (here the non-profit SK) is
   * honoured.
   */
  @Test
  void anonymousApiNonProfitResponsibleFallsBackToIntakeSk() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded SKs / intake setting / material");
    String handle = "E2E Anon Api NonProfit " + UUID.randomUUID();
    JsonObject created =
        new BackendSeeder()
            .anonymousCreateMaterialOrder(
                nonProfitSkId, nonProfitSkId, handle, materialId, 650, 10);
    assertEquals(
        intakeProfitSkId,
        orgUnitId(created, "responsibleOrgUnit"),
        "a non-profit responsible pick must fall back to the intake SK");
    assertEquals(nonProfitSkId, orgUnitId(created, "requestingOrgUnit"), "requester honoured");
  }

  /**
   * Edge the UI cannot trigger: a direct anonymous API create that omits the responsible unit
   * entirely falls back to the configured intake SK. The requester (a Staffel here) is honoured.
   */
  @Test
  void anonymousApiOmittedResponsibleFallsBackToIntakeSk() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded SKs / intake setting / material");
    String handle = "E2E Anon Api Omitted " + UUID.randomUUID();
    JsonObject created =
        new BackendSeeder()
            .anonymousCreateMaterialOrder(null, IRIDIUM_ID, handle, materialId, 650, 10);
    assertEquals(
        intakeProfitSkId,
        orgUnitId(created, "responsibleOrgUnit"),
        "an omitted responsible must fall back to the intake SK");
    assertEquals(IRIDIUM_ID, orgUnitId(created, "requestingOrgUnit"), "requester honoured");
  }

  /**
   * Opens a fresh anonymous browser context (self-signed cert tolerated, no stored auth state) so
   * the flow runs as an unauthenticated guest.
   *
   * @return a new anonymous browser context
   */
  private static BrowserContext anonymousContext() {
    return browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
  }

  /**
   * Extracts the {@code id} of a {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto} org-unit reference field
   * ({@code responsibleOrgUnit} / {@code requestingOrgUnit}) from a job-order JSON object.
   *
   * @param order the job-order JSON
   * @param field the reference field name
   * @return the referenced org unit's id, or {@code null} when the field is absent / null
   */
  private static String orgUnitId(JsonObject order, String field) {
    if (!order.has(field) || order.get(field).isJsonNull()) {
      return null;
    }
    return order.getAsJsonObject(field).get("id").getAsString();
  }
}
