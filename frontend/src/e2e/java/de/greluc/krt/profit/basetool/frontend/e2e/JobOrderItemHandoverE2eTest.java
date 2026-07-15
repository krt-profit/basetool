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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
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
 * Functional flow for ITEM job orders: create one through the UI, deliver it across two item
 * handovers, and verify the per-line decrement, the post-handover edit freeze, and the
 * auto-complete once every line is fully delivered.
 *
 * <p>Item orders order finished items (a blueprint output) rather than raw materials; the backend
 * snapshots the derived materials at create time. The item-handover modal renders one number input
 * per still-outstanding ordered line ({@code entries[i].amount}) and posts to {@code POST
 * /orders/{id}/item-handovers} (LOGISTICIAN/OFFICER/ADMIN-gated), which increments {@code
 * deliveredAmount} and auto-completes the order once outstanding hits zero — at which point the
 * {@code item-handover-open} button disappears ({@code th:if="${hasOutstandingItemLines}"}).
 *
 * <p>The order is created with amount {@code 2} and delivered {@code 1 + 1}, so the run exercises a
 * partial handover (button still present) and the completing handover (button gone). Between the
 * two it asserts the Phase-3 edit freeze: once any item handover exists, {@code GET
 * /orders/{id}/items/edit} redirects back to the detail page instead of rendering the editor. The
 * orderable item the picker offers is seeded once at stack bootstrap ({@link E2eStackExtension}),
 * so the flow needs the ephemeral stack (otherwise the item picker is empty). The actor is {@code
 * test-admin}, which satisfies the handover role gate through the role hierarchy.
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

        // The guest cannot read the queue, but this admin can resolve the new order's id by handle.
        JsonObject order = new BackendSeeder().findOrderByHandle(USERNAME, PASSWORD, handle);
        assertNotNull(order, "the created ITEM order must be readable by the admin");
        String id = order.get("id").getAsString();
        String detailUrl = baseUrl + "/orders/" + id;
        // The item-handover controls live in a non-default tab pane (an item order defaults to the
        // "items" tab), so deeplink straight to the item-handovers tab before asserting or driving
        // them; the /items/edit route below is a separate page and keeps the bare detail URL.
        String itemHandoverUrl = detailUrl + "?tab=item-handovers";

        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        // Partial handover: deliver one of two units. The log-handover button must remain.
        recordItemHandover(page, "1", "E2E Item Recipient A");
        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(
                page.getByTestId("item-handover-row")
                    .filter(new Locator.FilterOptions().setHasText("E2E Item Recipient A")))
            .isVisible();
        assertThat(page.getByTestId("item-handover-open")).isVisible();

        // Edit freeze: with a handover on record, the item-edit route redirects to the detail page
        // rather than rendering the editor, so its submit control is never shown.
        E2eSupport.navigate(page, detailUrl + "/items/edit");
        assertThat(page.getByTestId("order-item-submit")).hasCount(0);

        // Completing handover: deliver the last unit; the order auto-completes and the log-handover
        // button disappears once no line is outstanding.
        E2eSupport.navigate(page, itemHandoverUrl);
        recordItemHandover(page, "1", "E2E Item Recipient B");
        E2eSupport.navigate(page, itemHandoverUrl);
        assertThat(page.getByTestId("item-handover-open")).hasCount(0);
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

    // The item picker is a searchable combobox; open it and take the first offered option, then
    // wait
    // until the blueprint auto-selects and the derivation renders the first material's quality
    // control (the same gate the anonymous item-order flow relies on).
    page.getByTestId("order-item-combobox").first().click();
    page.locator("li[role='option']").first().click();
    page.locator("select[name='items[0].materials[0].quality']").waitFor();

    // Order two units, then wait for the amount-triggered re-derivation to re-render the quality
    // control before submitting.
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
    // The button toggles the modal open via the global open-modal-display delegation (no fetch).
    page.getByTestId("item-handover-open").click();
    page.locator("#item-handover-modal .date-part").fill(LocalDate.now().toString());
    page.locator("#item-handover-modal .time-part").fill("12:00");
    page.locator("#itemRecipientHandle").fill(recipient);
    page.locator("input[name='entries[0].amount']").fill(amount);
    // Submit in place (#575): the item handover swaps the items/handover sections via AJAX instead
    // of a Post/Redirect/Get. Await the item-handover XHR POST (the delivery commit) rather than a
    // document navigation that never comes; the caller re-navigates to assert the result.
    page.waitForResponse(
        response ->
            response.url().contains("/item-handovers")
                && "POST".equals(response.request().method()),
        () -> page.getByTestId("item-handover-submit").click());
  }
}
