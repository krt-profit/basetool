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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Refinery-order lifecycle beyond create + store (UC-20): editing and cancelling through the UI,
 * the status filter on the list, and the create/update validation + optimistic-locking edges.
 * Together with {@code RefineryOrderCreateE2eTest} (create) and {@code RefineryOrderStoreE2eTest}
 * (store) this rounds out the full set of refinery functions.
 *
 * <p><b>Drive via UI, verify via API.</b> The edit and cancel flows are exercised in the real
 * browser (the order-detail form and the list filter); the persisted effect (the new money value,
 * the {@code CANCELED} status) is asserted through {@link BackendSeeder}. The validation and 409
 * edges run purely through the API because they assert HTTP status codes the UI deliberately hides
 * behind a generic toast.
 *
 * <p>{@code test-admin} (ADMIN, IRIDIUM member) owns every order. Each test seeds its own order so
 * the mutations stay isolated.
 */
@Tag("e2e")
class RefineryOrderLifecycleE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The catalog-seeded refinery-hosting location every valid order in this suite runs at. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String hubLocationId;
  private static String materialId;
  private static String nonRefineryLocationId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership, a manual RAW
   * input material, the refinery hub id, and one ordinary (non-refinery) location for the negative
   * create probe.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      hubLocationId = seeder.findLocationIdByName(USERNAME, PASSWORD, REFINERY_HUB);
      materialId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Lifecycle Material");
      nonRefineryLocationId =
          seeder.createLocation(USERNAME, PASSWORD, "E2E Lifecycle Non-Refinery Loc");
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

  /** Editing the Ore-Sales value on the detail form and saving persists the new value. */
  @Test
  void editsAnOrderThroughTheUi() {
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/" + orderId);
        page.waitForLoadState();
        // The input-material picker (a searchable combobox — its id stays on the enhancer's hidden
        // input) is required, but its options come from the 10-minute-cached /api/v1/materials,
        // which need not contain this test's freshly-seeded material — so the order's pre-selected
        // option can be absent, leaving the picker empty and blocking the submit with a validation
        // bubble. Pick whatever RAW material the combobox offers (it renders no placeholder option)
        // so the form validates. The form carries no output-material field, so the backend
        // re-infers the output from the new input; the test asserts the edited Ore-Sales, not the
        // material.
        E2eSupport.selectComboboxFirstOption(
            page.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"));
        page.locator("#oreSales").fill("12345");
        // Drop the fixed footer so the bottom Save button is clickable, then submit and wait for
        // the
        // update POST's own response (the backend commit) before the API read-back. Targeting that
        // POST — rather than the settled post-redirect navigation (awaitFormPost) — avoids a WebKit
        // flake where the redirect does not settle within the timeout under CI load (the #505
        // pattern); the read-back needs only the commit, not the re-rendered list page.
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        page.waitForResponse(
            response ->
                response.url().contains("/refinery-orders/" + orderId)
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='refineryOrderMainForm']").click());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-lifecycle-edit");
        throw failure;
      }
    }
    assertEquals(
        12345.0, orderOreSales(orderId), "the edited Ore-Sales value is persisted to the order");
  }

  /** Cancelling an order from the detail page transitions it to {@code CANCELED}. */
  @Test
  void cancelsAnOrderThroughTheUi() {
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/" + orderId);
        page.waitForLoadState();
        // The cancel control is a submit inside a small <form action=".../{id}/delete">. Drop the
        // fixed footer so it is clickable, then wait for the delete POST's own response — tying the
        // wait to that exact request means a mis-targeted click (e.g. the adjacent back link, which
        // also lands on the list) fails loudly instead of passing on the wrong navigation.
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        // Cancel now shows a KRT confirm dialog before posting (#575). Click the cancel button to
        // open the overlay, then accept it inside waitForResponse so the wait is registered before
        // the confirm fires the delete POST. Selector matches the green
        // OrgChartPositionCrudE2eTest.
        page.locator("form[action$='/" + orderId + "/delete'] button[type='submit']").click();
        page.waitForResponse(
            response ->
                response.url().contains("/refinery-orders/" + orderId + "/delete")
                    && "POST".equals(response.request().method()),
            () -> page.locator(".krt-confirm-overlay .krt-confirm-ok").click());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-lifecycle-cancel");
        throw failure;
      }
    }
    assertEquals("CANCELED", orderStatus(orderId), "the cancelled order is marked CANCELED");
  }

  /**
   * The list's status filter hides a {@code CANCELED} order under the default {@code OPEN}+{@code
   * IN_PROGRESS} view and shows it under an explicit {@code CANCELED} filter.
   *
   * <p>The filter is driven through its {@code ?status=…} URL contract — the form the visible
   * status checkboxes belong to is GET-submitted to exactly that query, and its manual "Filtern"
   * button is JS-hidden (progressive enhancement) so it cannot be clicked in the harness.
   * Navigating the URL exercises the same backend filter the UI invokes.
   */
  @Test
  void statusFilterRevealsCanceledOrders() {
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
    seeder.deleteRefineryOrder(USERNAME, PASSWORD, orderId);

    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String rowLink = "a[href$='/refinery-orders/" + orderId + "']";
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        // Default filter is OPEN+IN_PROGRESS, so the cancelled order's detail link is absent.
        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        page.waitForLoadState();
        assertThat(page.locator(rowLink)).hasCount(0);

        // The CANCELED status filter reveals it.
        E2eSupport.navigate(page, baseUrl + "/refinery-orders?status=CANCELED");
        page.waitForLoadState();
        assertThat(page.locator(rowLink).first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-lifecycle-filter");
        throw failure;
      }
    }
  }

  /** Creating a refinery order at a location that hosts no refinery is rejected with 400. */
  @Test
  void rejectsANonRefineryLocationWith400() {
    assertEquals(
        400,
        seeder.attemptCreateRefineryOrderStatus(
            USERNAME, PASSWORD, nonRefineryLocationId, materialId, null),
        "a non-refinery location must be rejected with 400");
  }

  /** Creating a refinery order with no goods is rejected with 400 (the goods list is required). */
  @Test
  void rejectsEmptyGoodsWith400() {
    assertEquals(
        400,
        seeder.attemptCreateRefineryOrderStatus(USERNAME, PASSWORD, hubLocationId, null, null),
        "an empty goods list must be rejected with 400");
  }

  /** Submitting an update with a stale optimistic-lock version is rejected with 409. */
  @Test
  void rejectsStaleVersionUpdateWith409() {
    String orderId =
        seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
    long version = seeder.getRefineryOrderVersion(USERNAME, PASSWORD, orderId);

    int first =
        seeder.attemptUpdateRefineryOrderStatus(
            USERNAME, PASSWORD, orderId, hubLocationId, materialId, version);
    assertTrue(first >= 200 && first < 300, "the first update at the current version succeeds");

    assertEquals(
        409,
        seeder.attemptUpdateRefineryOrderStatus(
            USERNAME, PASSWORD, orderId, hubLocationId, materialId, version),
        "re-using the now-stale version must conflict with 409");
  }

  // --------------------------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------------------------

  /**
   * Reads the current status of a refinery order via {@code GET /api/v1/refinery-orders/{id}}.
   *
   * @param orderId the refinery order id
   * @return the order's status string
   */
  private static String orderStatus(String orderId) {
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/refinery-orders/" + orderId))
        .getAsJsonObject()
        .get("status")
        .getAsString();
  }

  /**
   * Reads the persisted Ore-Sales value of a refinery order, defaulting a {@code null} (unset)
   * money field to {@code 0} so the assertion compares a plain number.
   *
   * @param orderId the refinery order id
   * @return the order's {@code oreSales}, or {@code 0.0} when unset
   */
  private static double orderOreSales(String orderId) {
    var order =
        JsonParser.parseString(
                seeder.getBody(USERNAME, PASSWORD, "/api/v1/refinery-orders/" + orderId))
            .getAsJsonObject()
            .get("oreSales");
    return order == null || order.isJsonNull() ? 0.0 : order.getAsDouble();
  }
}
