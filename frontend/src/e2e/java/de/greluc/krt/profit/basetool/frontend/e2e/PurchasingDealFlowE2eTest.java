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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The deal flow of „Ankauf" up to the bank request (REQ-PUR-004, REQ-PUR-007, REQ-PUR-012,
 * REQ-PUR-022): a delegate creates a partner and a deal for a purchasable need, requests the first
 * tranche and cancels the deal again, and every page updates in place without a reload.
 */
@Tag("e2e")
class PurchasingDealFlowE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String HIDE_FOOTER =
      "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
          + " 'none'; } }";

  private static Playwright playwright;
  private static Browser browser;
  private static String needId;
  private static String partnerName;

  /**
   * Launches the browser and prepares the supply and purchasing surfaces: the unit may raise needs,
   * a purchasable need exists, a funding account is set and the test user is a delegate.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    }
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    partnerName = "Ankauf E2E Partner " + suffix;
    needId = seeder.preparePurchasing(USERNAME, PASSWORD, suffix);
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
   * Creates a partner and a deal, requests the first tranche and cancels the deal, checking each
   * step through the page and through the backend.
   */
  @Test
  void aDealIsCreatedFundedByRequestAndCancelledInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/purchasing/partners");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(HIDE_FOOTER);

        page.locator("[data-testid='purchasing-new-partner']").click();
        page.locator("[data-testid='purchasing-partner-name']").fill(partnerName);
        clearToasts(page);
        page.locator("[data-testid='purchasing-partner-submit']").click();

        assertSuccessToast(page);
        assertThat(
                page.locator("[data-testid='purchasing-partner-row']")
                    .filter(new Locator.FilterOptions().setHasText(partnerName)))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the partner must appear in place — no reload on success");

        E2eSupport.navigate(page, baseUrl + "/purchasing/deals");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(HIDE_FOOTER);

        page.locator("[data-testid='purchasing-new-deal']").click();
        page.locator("[data-testid='purchasing-deal-need']").selectOption(needId);
        page.locator("[data-testid='purchasing-deal-partner']")
            .selectOption(
                new com.microsoft.playwright.options.SelectOption().setLabel(partnerName));
        page.locator("#purchasing-deal-amount").fill("5");
        page.locator("#purchasing-deal-price").fill("100");
        page.locator("#purchasing-deal-model").selectOption("ON_DELIVERY");
        clearToasts(page);
        page.locator("[data-testid='purchasing-deal-submit']").click();

        assertSuccessToast(page);
        assertThat(page.locator("[data-testid='purchasing-deal-row']"))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the deal must appear in place — no reload on success");
        String dealId = latestDeal().get("id").getAsString();
        assertEquals("DRAFT", latestDeal().get("status").getAsString());

        E2eSupport.navigate(page, baseUrl + "/purchasing/deals/" + dealId);
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(HIDE_FOOTER);

        page.locator("[data-testid='purchasing-request-tranche']").click();
        clearToasts(page);
        page.locator("[data-testid='purchasing-tranche-submit']").click();

        assertSuccessToast(page);
        assertThat(page.locator("[data-testid='purchasing-tranche-row']"))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
        assertThat(page.locator("[data-testid='purchasing-deal-status']"))
            .hasAttribute(
                "data-status",
                "SUBMITTED",
                new LocatorAssertions.HasAttributeOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the tranche must appear in place — no reload on success");
        assertEquals("SUBMITTED", latestDeal().get("status").getAsString());

        page.locator("[data-testid='purchasing-cancel-deal']").click();
        clearToasts(page);
        page.locator("[data-testid='purchasing-cancel-submit']").click();

        assertSuccessToast(page);
        assertThat(page.locator("[data-testid='purchasing-deal-status']"))
            .hasAttribute(
                "data-status",
                "CANCELLED",
                new LocatorAssertions.HasAttributeOptions().setTimeout(10_000));
        assertEquals("CANCELLED", latestDeal().get("status").getAsString());
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the cancellation must update the page in place — no reload on success");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "purchasing-deal-flow");
        throw failure;
      }
    }
  }

  /**
   * Removes the toasts of earlier steps so the next assertion sees only its own.
   *
   * @param page the page whose toasts are cleared
   */
  private static void clearToasts(Page page) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
  }

  /**
   * Asserts a success toast and no error toast.
   *
   * @param page the page that just completed a write
   */
  private static void assertSuccessToast(Page page) {
    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
  }

  /**
   * Reads the caller's most recent deal from the backend.
   *
   * @return the deal's JSON object
   */
  private static JsonObject latestDeal() {
    String body =
        new BackendSeeder()
            .getBody(
                USERNAME,
                PASSWORD,
                "/api/v1/purchasing/deals?sort=createdAt,desc&size=1&mine=true");
    JsonArray content = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("content");
    return content.get(0).getAsJsonObject();
  }
}
