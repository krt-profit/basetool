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

import static de.greluc.krt.profit.basetool.frontend.e2e.ExchangeE2eSupport.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A member who leaves the org is refused by the gateway on the next request (REQ-XCH-008): an admin
 * withdraws the member's KRT Member role in the Basetool (REQ-SEC-088), the departure follows from
 * that write, and it ends the exchange connection before the client calls again.
 */
@Tag("e2e")
class ExchangeDepartureE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** The member who departs; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange-departed";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-departed-pw";

  /** The admin who withdraws the role. */
  private static final String ADMIN = "test-admin";

  /** The admin's throwaway password from {@code realm-export.e2e.json}. */
  private static final String ADMIN_PASSWORD = "test-admin-pw";

  private static Playwright playwright;
  private static Browser browser;

  /** Seeds the catalogue rows, homes the member, registers the client and switches it on. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "the flow needs the local stack's gateway and Keycloak");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    ExchangeE2eSupport.prepare(new BackendSeeder(), MEMBER, MEMBER_PASSWORD);
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
   * Connects the member's client, has an admin withdraw the member's KRT Member role, and checks
   * the client's very next call is refused with {@code 401 CLIENT_REVOKED}.
   *
   * @throws Exception if a call to the backend or the gateway cannot be sent
   */
  @Test
  void aDepartedMemberIsRefusedOnTheNextRequest() throws Exception {
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    assertOk(client.call("GET", "/exchange/v1/me/blueprints", null));

    withdrawKrtMember();

    ExchangeTestClient.Answer refused = client.call("GET", "/exchange/v1/me/blueprints", null);
    assertEquals(401, refused.status(), "the departed member's client is refused: " + refused);
    assertEquals("CLIENT_REVOKED", refused.code(), "refused as revoked: " + refused);
  }

  /** Withdraws the member's KRT Member role as the admin, echoing the member's role-set counter. */
  private static void withdrawKrtMember() {
    BackendSeeder seeder = new BackendSeeder();
    String memberId = seeder.getUserId(MEMBER, MEMBER_PASSWORD);
    long version =
        JsonParser.parseString(
                seeder.getBody(ADMIN, ADMIN_PASSWORD, "/api/v1/users/" + memberId + "/roles"))
            .getAsJsonObject()
            .get("version")
            .getAsLong();
    int status =
        seeder.putForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/users/" + memberId + "/roles/KRT_MEMBER",
            "{\"assigned\":false,\"version\":" + version + "}");
    assertTrue(status >= 200 && status < 300, "the admin withdraws KRT Member: HTTP " + status);
  }
}
