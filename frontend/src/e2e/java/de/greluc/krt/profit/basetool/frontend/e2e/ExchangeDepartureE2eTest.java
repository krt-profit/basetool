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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A member who leaves the org is refused by the gateway on the next request (REQ-XCH-008): the
 * member loses every realm role in Keycloak, their next sign-in reconciles the departure, and the
 * departure ends the exchange connection before the client calls again.
 */
@Tag("e2e")
class ExchangeDepartureE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** The member who departs; nothing else in the suite uses the account. */
  private static final String MEMBER = "test-exchange-departed";

  /** The member's throwaway password from {@code realm-export.e2e.json}. */
  private static final String MEMBER_PASSWORD = "test-exchange-departed-pw";

  /** The stack Keycloak's base URL as this JVM reaches it. */
  private static final String KEYCLOAK = "http://localhost:18080/auth";

  /** The realm roles the member holds directly; the default role carries „KRT Member" too. */
  private static final List<String> MEMBER_ROLES = List.of("KRT Member", "default-roles-basetool");

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
   * Connects the member's client, takes every realm role from the member in Keycloak, lets the
   * member's next backend sign-in reconcile the departure, and checks the client's very next call
   * is refused with {@code 401 CLIENT_REVOKED}.
   *
   * @throws Exception if a call to Keycloak, the backend or the gateway cannot be sent
   */
  @Test
  void aDepartedMemberIsRefusedOnTheNextRequest() throws Exception {
    ExchangeTestClient client = ExchangeE2eSupport.connect(browser, MEMBER, MEMBER_PASSWORD);
    assertOk(client.call("GET", "/exchange/v1/me/blueprints", null));

    removeRealmRoles();
    new BackendSeeder().attemptGetStatus(MEMBER, MEMBER_PASSWORD, "/api/v1/users/me");

    ExchangeTestClient.Answer refused = client.call("GET", "/exchange/v1/me/blueprints", null);
    assertEquals(401, refused.status(), "the departed member's client is refused: " + refused);
    assertEquals("CLIENT_REVOKED", refused.code(), "refused as revoked: " + refused);
  }

  /**
   * Removes the member's direct realm roles through the Keycloak Admin API as the stack's bootstrap
   * administrator, so the member holds no Basetool role any more.
   *
   * @throws Exception if a Keycloak call fails
   */
  private static void removeRealmRoles() throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String token = adminToken(http);
    JsonArray users =
        JsonParser.parseString(
                send(
                    http,
                    token,
                    "GET",
                    "/admin/realms/basetool/users?exact=true&username=" + encode(MEMBER),
                    null))
            .getAsJsonArray();
    assertEquals(1, users.size(), "Keycloak knows the member once");
    String userId = users.get(0).getAsJsonObject().get("id").getAsString();
    JsonArray roles = new JsonArray();
    for (String name : MEMBER_ROLES) {
      JsonObject role =
          JsonParser.parseString(
                  send(
                      http,
                      token,
                      "GET",
                      "/admin/realms/basetool/roles/" + encode(name).replace("+", "%20"),
                      null))
              .getAsJsonObject();
      JsonObject reference = new JsonObject();
      reference.addProperty("id", role.get("id").getAsString());
      reference.addProperty("name", name);
      roles.add(reference);
    }
    send(
        http,
        token,
        "DELETE",
        "/admin/realms/basetool/users/" + userId + "/role-mappings/realm",
        roles.toString());
  }

  /**
   * Signs the bootstrap administrator in to the {@code master} realm.
   *
   * @param http the HTTP client
   * @return the administrator's access token
   * @throws Exception if Keycloak does not issue a token
   */
  private static String adminToken(HttpClient http) throws Exception {
    String form =
        "grant_type=password&client_id=admin-cli&username="
            + encode(E2eStackExtension.KEYCLOAK_ADMIN_USER)
            + "&password="
            + encode(E2eStackExtension.KEYCLOAK_ADMIN_PASSWORD);
    HttpResponse<String> answer =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(KEYCLOAK + "/realms/master/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            BodyHandlers.ofString());
    assertEquals(200, answer.statusCode(), "the Keycloak administrator signs in");
    return JsonParser.parseString(answer.body())
        .getAsJsonObject()
        .get("access_token")
        .getAsString();
  }

  /**
   * Sends one Keycloak Admin API request and asserts it succeeded.
   *
   * @param http the HTTP client
   * @param token the administrator's access token
   * @param method the HTTP method
   * @param path the path below {@code /auth}
   * @param body the JSON body, or {@code null} for none
   * @return the response body
   * @throws Exception if the request cannot be sent
   */
  private static String send(HttpClient http, String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(KEYCLOAK + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> answer = http.send(request.build(), BodyHandlers.ofString());
    assertTrue(
        answer.statusCode() >= 200 && answer.statusCode() < 300,
        method + " " + path + " failed: HTTP " + answer.statusCode() + " " + answer.body());
    return answer.body();
  }

  /**
   * URL-encodes a value.
   *
   * @param value the value
   * @return the encoded value
   */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
