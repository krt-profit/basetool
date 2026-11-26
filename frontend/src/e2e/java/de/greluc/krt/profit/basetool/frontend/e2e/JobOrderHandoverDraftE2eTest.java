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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The job order's handover dialogs keep a half-filled handover as a tab-scoped draft (REQ-FE-041):
 * after a reload the page offers it, restore reopens the dialog through its opener, and the
 * material handover rebuilds its rows once the dialog loaded the order's inventory entries; the
 * save removes the draft.
 */
@Tag("e2e")
class JobOrderHandoverDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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

  /** Brings a half-filled material handover back with its row, and the save removes the draft. */
  @Test
  void aReloadOffersTheMaterialHandoverWithItsRow() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Draft Material");
    String locationId =
        seeder.createLocation(USERNAME, PASSWORD, "E2E Draft Loc " + UUID.randomUUID());
    String orderId =
        seeder.createJobOrder(
            USERNAME, PASSWORD, IRIDIUM_ID, "E2E Draft Handover", materialId, 650, 200.0);
    String entryId =
        seeder.createInventoryItemForJobOrder(
            USERNAME, PASSWORD, materialId, locationId, orderId, 750, 100.0);
    String recipient = "E2E Draft Recipient " + UUID.randomUUID().toString().substring(0, 6);
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/" + orderId + "?tab=handovers");
        openMaterialHandover(page);
        page.locator("#add-handover-item-btn").click();
        page.locator("select[name='items[0].inventoryItemId']").selectOption(entryId);
        page.locator("input[name='items[0].amount']").fill("40");
        fillTime(page, "#handover-modal");
        page.locator("#recipientHandle").fill(recipient);
        awaitDraft(page, "job-order-handover", recipient);
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        page.waitForResponse(
            response ->
                response.url().contains("/materials/") && response.url().contains("/inventory"),
            () ->
                page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click());
        assertThat(page.locator("#handover-modal")).isVisible();
        assertThat(page.locator("select[name='items[0].inventoryItemId']")).hasValue(entryId);
        assertThat(page.locator("input[name='items[0].amount']")).hasValue("40");
        assertThat(page.locator("#recipientHandle")).hasValue(recipient);
        assertThat(page.locator("#handoverTimeDate")).hasValue(LocalDate.now().toString());

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + orderId + "/handovers")
                    && "POST".equals(response.request().method())
                    && response.status() < 300,
            () -> page.getByTestId("order-handover-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-handover-draft");
        throw failure;
      }
    }
  }

  /** Brings a half-filled item handover back with its amount, and the save removes the draft. */
  @Test
  void aReloadOffersTheItemHandoverWithItsAmount() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Draft Material");
    String widget = seeder.seedOrderableItem("E2E Draft Widget", materialId);
    String orderId =
        seeder.createItemJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E Draft Item", widget, 2);
    seeder.manufactureItemOrderLineFully(
        USERNAME,
        PASSWORD,
        orderId,
        seeder.createLocation(USERNAME, PASSWORD, "E2E Draft Item Loc " + UUID.randomUUID()));
    String recipient = "E2E Draft Item Recipient " + UUID.randomUUID().toString().substring(0, 6);
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/orders/" + orderId + "?tab=item-handovers");
        page.getByTestId("item-handover-open").click();
        fillTime(page, "#item-handover-modal");
        page.locator("#itemRecipientHandle").fill(recipient);
        page.locator("input[name='entries[0].amount']").fill("1");
        awaitDraft(page, "job-order-item-handover", recipient);
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#item-handover-modal")).isVisible();
        assertThat(page.locator("#itemRecipientHandle")).hasValue(recipient);
        assertThat(page.locator("input[name='entries[0].amount']")).hasValue("1");

        page.waitForResponse(
            response ->
                response.url().contains("/orders/" + orderId + "/item-handovers")
                    && "POST".equals(response.request().method())
                    && response.status() < 300,
            () -> page.getByTestId("item-handover-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-item-handover-draft");
        throw failure;
      }
    }
  }

  /**
   * Opens the material handover and waits for the inventory entries its rows choose from.
   *
   * @param page the page showing the order's handovers
   */
  private static void openMaterialHandover(Page page) {
    page.waitForResponse(
        response -> response.url().contains("/materials/") && response.url().contains("/inventory"),
        () -> page.getByTestId("order-handover-open").click());
  }

  /**
   * Fills the handover time of a handover dialog with today at noon.
   *
   * @param page the page
   * @param dialog the selector of the handover dialog
   */
  private static void fillTime(Page page, String dialog) {
    page.locator(dialog + " .date-part").fill(LocalDate.now().toString());
    page.locator(dialog + " .time-part").fill("12:00");
  }

  /**
   * Opens a signed-in browser context.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth));
  }

  /**
   * Waits for the draft of a dialog form that holds the given text.
   *
   * @param page the page
   * @param formKey the dialog form's draft key
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String formKey, String text) {
    page.waitForFunction(
        "([key, text]) => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes(key) && sessionStorage.getItem(k).includes(text))",
        new String[] {formKey, text});
  }
}
