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
import com.microsoft.playwright.Locator;
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
 * Functional coverage for the audited "Mein Inventar" (personal inventory) area at {@code
 * /personal-inventory}, which previously had no end-to-end test. This is the {@code
 * PersonalInventoryItem} feature (free-text name + UEX location + quantity, per JWT {@code sub}) —
 * distinct from the squadron Lager's personal view at {@code /inventory/my} that {@code
 * InventoryOperationsE2eTest} covers.
 *
 * <p>It drives the create-in-place and delete-in-place mutations (REQ-AUDIT-001 audited writes,
 * REQ-FE-001/002 in-place {@code #pi-results} swap): both go through {@code window.krtFetch.write}
 * and re-render the results fragment without a page reload. The create flow exercises the bespoke
 * UEX-location typeahead ({@code /personal-inventory/uex-search}), which requires a city carrying a
 * numeric {@code id_city} to select — the E2E catalog seeds {@code "E2E Personal Inventory City"}
 * for exactly this test.
 *
 * <p>Actor: the suite-default {@code test-admin}. Personal inventory is per-user and carries no
 * org-unit scope, so no squadron membership seed is required — the single shared OIDC login is the
 * only setup. Tagged {@code @Tag("e2e")}: it mutates data, so it runs only against the ephemeral,
 * disposable stack.
 */
@Tag("e2e")
class PersonalInventoryCrudE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Name of the UEX city seeded with a numeric {@code id_city} (see {@code uex-catalog-seed.sql}).
   */
  private static final String SEEDED_CITY = "E2E Personal Inventory City";

  /** Distinctive display name of the personal-inventory item this test creates then deletes. */
  private static final String ITEM_NAME = "E2E Mein Inventar Widget";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated session reused across the test. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Creates a personal-inventory entry through the modal — selecting the seeded UEX city via the
   * location typeahead — and asserts it appears in the {@code #pi-results} list in place (no
   * reload, no error toast) and is persisted in the backend; then deletes it through the KRT
   * confirm modal and asserts the row is gone in place. Together these exercise the two audited
   * mutations of the area.
   */
  @Test
  void addsAndDeletesAPersonalInventoryEntryInPlace() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/personal-inventory");
        page.waitForLoadState();

        // Marker on the live document: a full navigation/reload wipes it, so its survival proves
        // both writes stayed in place. The position:fixed footer can cover the modal submit button.
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        // CREATE — open the modal, fill the fields, pick the seeded city from the typeahead.
        page.locator("button[data-trigger='pi-open-create']").click();
        Locator name = page.locator("#krt-pi-name");
        assertThat(name).isVisible();
        name.fill(ITEM_NAME);
        page.locator("#krt-pi-quantity").fill("5");
        page.locator("#krt-pi-location-search").fill(SEEDED_CITY);
        Locator firstResult =
            page.locator("#krt-pi-location-results .krt-pi-typeahead-item").first();
        assertThat(firstResult)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        firstResult.click();

        page.waitForResponse(
            response ->
                response.url().endsWith("/personal-inventory/add")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#krt-pi-form button[type='submit']").click());

        assertThat(itemRow(page)).isVisible();
        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the create must update in place — no page reload cleared the marker");
        assertTrue(persistedItemExists(), "the created entry must be persisted in the backend");

        // DELETE — open the confirm modal, confirm, and assert the row is removed in place.
        itemRow(page).locator("[data-trigger='pi-open-delete']").click();
        assertThat(page.locator("#krt-pi-delete-modal")).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/personal-inventory/")
                    && response.url().endsWith("/delete")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#krt-pi-delete-form button[type='submit']").click());

        assertThat(itemRow(page)).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the delete must update in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "personal-inventory-crud");
        throw failure;
      }
    }
  }

  // ---------------------------------------------------------------------- helpers --

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Locates the results-table row carrying this test's item name.
   *
   * @param page the page showing the personal-inventory list
   * @return a locator for the item's {@code <tr>} (count 0 once it is deleted)
   */
  private static Locator itemRow(Page page) {
    return page.locator(
        "#pi-results tr[data-item-id]", new Page.LocatorOptions().setHasText(ITEM_NAME));
  }

  /**
   * Reads the caller's personal inventory filtered by this test's item name straight from the
   * backend ({@code GET /api/v1/personal-inventory?q=...}) and reports whether a matching entry
   * exists, so the create assertion does not rely on the DOM alone.
   *
   * @return {@code true} if the backend list contains an entry named {@link #ITEM_NAME}
   */
  private static boolean persistedItemExists() {
    String encoded = java.net.URLEncoder.encode(ITEM_NAME, java.nio.charset.StandardCharsets.UTF_8);
    String body =
        new BackendSeeder()
            .getBody(USERNAME, PASSWORD, "/api/v1/personal-inventory?size=50&q=" + encoded);
    for (var element : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("content")) {
      var item = element.getAsJsonObject();
      if (item.has("name") && ITEM_NAME.equals(item.get("name").getAsString())) {
        return true;
      }
    }
    return false;
  }
}
