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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Cross-Staffel handover flow (UC-09): Staffel A creates a job order, Staffel B supplies it with
 * B-owned inventory, and an Officer of A then records a handover of that foreign item through A's
 * order — optionally naming Staffel B as the recipient. Proves the job-order workspace lets one
 * Staffel act on inventory another Staffel contributed, end to end through the handover UI.
 *
 * <p>Builds on the same seeding as {@link CrossStaffelJobOrderE2eTest}, but drives the full
 * handover modal (item + amount + time + recipient) and verifies the handover row appears.
 */
@Tag("e2e")
class CrossStaffelHandoverE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";
  private static final String STAFFEL_B_NAME = "E2E Handover B";

  private static Playwright playwright;
  private static Browser browser;
  private static String jobOrderId;
  private static String bInventoryItemId;

  /**
   * Seeds an Officer homed in Staffel A (IRIDIUM), a fresh Staffel B with {@code test-member} homed
   * in it, a job order owned by A, and a B-owned inventory item linked to that order (the foreign
   * supply to be handed over).
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD),
          IRIDIUM_ID,
          false,
          false);
      String staffelBId = seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, STAFFEL_B_NAME, "EHOB");
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
          staffelBId,
          false,
          false);
      String materialId = seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E HO X-Mat");
      String bLocationId = seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E HO X-Loc");
      jobOrderId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, IRIDIUM_ID, "E2E HO X-Order", materialId, 650, 80);
      bInventoryItemId =
          seeder.createInventoryItemForJobOrder(
              MEMBER_USER, MEMBER_PASSWORD, materialId, bLocationId, jobOrderId, 750, 60);
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
   * An Officer of Staffel A records a handover of Staffel B's linked inventory through A's order,
   * naming B as the recipient when that option is offered, and verifies the handover row appears.
   */
  @Test
  void officerHandsOverForeignStaffelInventory() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId + "?tab=handovers");

        page.waitForResponse(
            response ->
                response.url().contains("/materials/") && response.url().contains("/inventory"),
            () -> page.getByTestId("order-handover-open").click());

        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(bInventoryItemId);
        page.locator("input[name='items[0].amount']").fill("40");

        page.locator("#handover-modal .date-part").fill(LocalDate.now().toString());
        page.locator("#handover-modal .time-part").fill("12:00");
        page.locator("#recipientHandle").fill("E2E CrossStaffel Recipient");
        // recipientSquadron is an optional name dropdown; name Staffel B as the recipient when it
        // is
        // offered (the cross-Staffel recipient case), otherwise leave it blank.
        Locator bRecipientOption =
            page.locator("#recipientSquadron option[value='" + STAFFEL_B_NAME + "']");
        if (bRecipientOption.count() > 0) {
          page.locator("#recipientSquadron").selectOption(STAFFEL_B_NAME);
        }

        // Submit in place (#575): the material handover swaps sections via AJAX and closes the
        // modal — no Post/Redirect/Get navigation to await. Mark the window to prove no full reload
        // happened, submit, then web-first-wait for the handover row carrying the recipient to
        // appear in the re-rendered history (which also proves persistence), and assert the marker.
        page.evaluate("window.__krtNoReload = true;");
        page.getByTestId("order-handover-submit").click();
        assertThat(
                page.getByTestId("order-handover-row")
                    .filter(new Locator.FilterOptions().setHasText("E2E CrossStaffel Recipient")))
            .isVisible();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "material handover must swap in place — no full-page reload cleared the window marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "crossstaffel-handover");
        throw failure;
      }
    }
  }
}
