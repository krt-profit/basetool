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
 * The pledge-and-receive flow of „Bedarf &amp; Spenden" (REQ-SUP-007, REQ-SUP-009): a member
 * pledges a free donation on „Meine Spenden", the receiving unit confirms it on „Eingehende
 * Spenden", and both pages update in place without a reload.
 */
@Tag("e2e")
class SupplyPledgeReceiveE2eTest {

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
  private static String materialId;
  private static String locationId;

  /**
   * Launches the browser, switches the supply surface on, lets IRIDIUM take donations and seeds a
   * material and a location the dialogs select.
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
    materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "Supply E2E Ore " + suffix);
    locationId = seeder.createLocation(USERNAME, PASSWORD, "Supply E2E Port " + suffix);
    int settings =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/settings",
            "{\"enabled\":true,\"governingAreaId\":null}");
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    int unit =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/units/" + IRIDIUM_ID,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
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
   * Pledges a free donation, confirms it as the receiving unit and checks the state both through
   * the page and through the backend.
   */
  @Test
  void aPledgeIsReceivedByTheUnitInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/supply/donations/mine");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(HIDE_FOOTER);

        page.locator("[data-testid='supply-free-pledge']").click();
        page.locator("#supply-pledge-unit").selectOption(IRIDIUM_ID);
        page.locator("#supply-pledge-material").selectOption(materialId);
        page.locator("#supply-pledge-quality").fill("500");
        page.locator("#supply-pledge-amount").fill("3");
        clearToasts(page);
        page.locator("[data-testid='supply-pledge-submit']").click();

        assertSuccessToast(page);
        assertThat(page.locator("[data-testid='supply-donation-row']"))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the pledge must update the page in place — no reload on success");
        assertEquals("PLEDGED", latestDonationStatus());

        E2eSupport.navigate(page, baseUrl + "/supply/donations/incoming?orgUnitId=" + IRIDIUM_ID);
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(HIDE_FOOTER);

        Locator receive = page.locator("[data-testid='supply-donation-receive']").first();
        assertThat(receive).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        receive.click();
        page.locator("#supply-receive-location").selectOption(locationId);
        clearToasts(page);
        page.locator("[data-testid='supply-receive-submit']").click();

        assertSuccessToast(page);
        assertThat(page.locator("[data-testid='supply-donation-receive']"))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the receipt must update the page in place — no reload on success");
        assertEquals("RECEIVED", latestDonationStatus());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-pledge-receive");
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
   * Reads the status of the caller's most recent donation from the backend.
   *
   * @return the donation's status name
   */
  private static String latestDonationStatus() {
    String body =
        new BackendSeeder()
            .getBody(USERNAME, PASSWORD, "/api/v1/supply/me/donations?sort=createdAt,desc&size=1");
    JsonArray content = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("content");
    return content.get(0).getAsJsonObject().get("status").getAsString();
  }
}
