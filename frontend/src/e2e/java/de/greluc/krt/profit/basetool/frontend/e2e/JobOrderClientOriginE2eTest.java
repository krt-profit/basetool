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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The origin markers of a client's writes on a job order (REQ-ORDERS-078): the creator chip in the
 * overview row and the detail head, the client on a target-history entry and on the comment, and a
 * web write of the comment that clears the comment's marker in place while the chip stays.
 *
 * <p>The client's writes are recorded over JDBC as the shared commands leave them, so the journey
 * needs no exchange switch; the client is the E2E realm's registered one, named by its display
 * name.
 */
@Tag("e2e")
class JobOrderClientOriginE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = ExchangeE2eSupport.ADMIN;
  private static final String PASSWORD = ExchangeE2eSupport.ADMIN_PASSWORD;
  private static final String IRIDIUM = ExchangeE2eSupport.IRIDIUM_SQUADRON_ID;
  private static final String CHIP = "über " + ExchangeE2eSupport.CLIENT_NAME;

  @Test
  void aClientsWritesCarryTheirOriginUntilTheWebWritesTheComment() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    ExchangeE2eSupport.registerClient(seeder);
    String handle = "origin-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId = createAuecOrder(seeder, handle);
    assertEquals(
        200,
        seeder.patchForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/orders/" + orderId + "/money/target",
            "{\"target\":\"600000\",\"version\":0}"),
        "the web changes the target");
    seeder.markJobOrderClientOrigin(orderId, ExchangeE2eSupport.CLIENT_ID, "Bitte nach Area18");
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/orders?lang=de&type=AUEC");
        Locator row = page.locator("[data-testid='order-row'][data-id='" + orderId + "']");
        assertThat(row.getByTestId("order-created-client")).hasText(CHIP);

        E2eSupport.navigate(page, baseUrl + "/orders/" + orderId + "?lang=de");
        assertThat(page.locator(".page-title").getByTestId("order-created-client")).hasText(CHIP);
        Locator header = page.locator("#order-header-results");
        assertThat(header).containsText("Bitte nach Area18");
        assertThat(header.getByTestId("order-comment-client")).hasText(CHIP);
        assertThat(page.getByTestId("order-money-target-change-client")).containsText(CHIP);

        page.evaluate("() => { window.__originNoReload = true; }");
        page.getByTestId("order-edit").click();
        page.getByTestId("order-money-edit-comment").fill("Bitte nach Lorville");
        var saved =
            page.waitForResponse(
                response ->
                    response.url().endsWith("/orders/" + orderId + "/money/information")
                        && "PATCH".equals(response.request().method()),
                () -> page.getByTestId("order-money-save-information").click());
        assertTrue(saved.ok(), () -> saved.status() + " " + saved.text());

        assertThat(header).containsText("Bitte nach Lorville");
        assertThat(header.getByTestId("order-comment-client")).hasCount(0);
        assertThat(page.locator(".page-title").getByTestId("order-created-client")).hasText(CHIP);
        assertEquals(Boolean.TRUE, page.evaluate("() => window.__originNoReload === true"));
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "job-order-client-origin");
        throw failure;
      }
    }
  }

  private static String createAuecOrder(BackendSeeder seeder, String handle) {
    JsonObject request = new JsonObject();
    request.addProperty("responsibleOrgUnitId", IRIDIUM);
    request.addProperty("requestingOrgUnitId", IRIDIUM);
    request.addProperty("handle", handle);
    request.addProperty("target", "500000");
    JsonObject created =
        JsonParser.parseString(
                seeder.postBody(USERNAME, PASSWORD, "/api/v1/orders/auec", request.toString()))
            .getAsJsonObject();
    return created.get("id").getAsString();
  }
}
