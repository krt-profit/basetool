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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shared steps of the exchange E2E tests (REQ-XCH-005, REQ-XCH-029): the registered client and
 * its capabilities, the member's device login on Keycloak's pages, waiting for the gateway's
 * registry mirror, and the change-set builders.
 */
final class ExchangeE2eSupport {

  /** The admin who registers the client and switches the exchange on. */
  static final String ADMIN = "test-admin";

  /** The admin's throwaway password from {@code realm-export.e2e.json}. */
  static final String ADMIN_PASSWORD = "test-admin-pw";

  /** The E2E realm's third-party client, public, device grant, consent and DPoP-bound tokens. */
  static final String CLIENT_ID = "e2e-exchange-client";

  /** The registry display name, which the member page shows. */
  static final String CLIENT_NAME = "E2E Exchange Client";

  /** The scopes every device login requests. */
  static final String SCOPES =
      "offline_access exchange.connect exchange.blueprints.read exchange.blueprints.write"
          + " exchange.stock.read exchange.stock.write";

  /** The capabilities the registry grants the client. */
  static final List<String> CAPABILITIES =
      List.of(
          "exchange.connect",
          "exchange.blueprints.read",
          "exchange.blueprints.write",
          "exchange.stock.read",
          "exchange.stock.write");

  /** The canonical IRIDIUM Squadron every exchange member is homed in. */
  static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /** The seeded material stock lots hold, see {@code exchange-e2e-seed.sql}. */
  static final String MATERIAL_NAME = "E2E Exchange Metal";

  /** The Lager location of every stock lot, from {@code uex-catalog-seed.sql}. */
  static final String LOCATION_NAME = "E2E Refinery Hub";

  /** How long the gateway may take to see a registry change through its mirror. */
  static final Duration MIRROR_WAIT = Duration.ofSeconds(90);

  private ExchangeE2eSupport() {}

  /**
   * Seeds the exchange catalogue rows, registers the client, switches the exchange on and homes
   * each member in the IRIDIUM Squadron.
   *
   * @param seeder the backend seeder
   * @param members the member accounts and their passwords, alternating
   */
  static void prepare(BackendSeeder seeder, String... members) {
    seeder.seedSql("/exchange-e2e-seed.sql");
    for (int i = 0; i < members.length; i += 2) {
      String memberId = seeder.getUserId(members[i], members[i + 1]);
      seeder.assignStaffelMembership(
          ADMIN, ADMIN_PASSWORD, memberId, IRIDIUM_SQUADRON_ID, false, false);
    }
    registerClient(seeder);
    switchExchangeOn(seeder);
  }

  /**
   * Connects a new installation: a fresh key, a device login the member approves, and a first
   * gateway answer once the gateway knows the client.
   *
   * @param browser the browser the member approves the login in
   * @param member the member's Keycloak username
   * @param password the member's throwaway password
   * @return the connected client
   * @throws Exception if the login or a gateway call fails
   */
  static ExchangeTestClient connect(Browser browser, String member, String password)
      throws Exception {
    ExchangeTestClient client = new ExchangeTestClient(CLIENT_ID);
    ExchangeTestClient.DeviceLogin login = client.startDeviceLogin(SCOPES);
    approveOnTheDevicePage(browser, login, member, password);
    client.awaitToken(login);
    awaitAnswer(client, "GET", "/exchange/v1", a -> a.status() == 200);
    return client;
  }

  /**
   * Names the installation of a connected client.
   *
   * @param client the connected client
   * @param label the label the member sees on „Verbundene Anwendungen"
   * @throws Exception if the call cannot be sent
   */
  static void label(ExchangeTestClient client, String label) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("label", label);
    assertOk(client.call("POST", "/exchange/v1/me/installation", body));
  }

  /**
   * Approves a device login as a member does, in a fresh browser context: opens the bare
   * verification page, types the user code, signs in and grants the consent, until neither a login
   * form, a code form nor a consent form is left (REQ-XCH-005, REQ-XCH-027).
   *
   * <p>Asserts that the code page shows the phishing warning and that the consent page shows its
   * warning and exactly this login's user code, that both pages were reached, and that the success
   * page offers the way to the Basetool and the close-tab button.
   *
   * @param browser the browser
   * @param login the started device login, whose bare verification page and user code are used
   * @param member the member's Keycloak username
   * @param password the member's throwaway password
   */
  static void approveOnTheDevicePage(
      Browser browser, ExchangeTestClient.DeviceLogin login, String member, String password) {
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        page.navigate(login.verificationUri());
        boolean codePageSeen = false;
        boolean consentPageSeen = false;
        for (int step = 0; step < 6; step++) {
          page.waitForLoadState(LoadState.LOAD);
          Locator accept = page.locator("input[name='accept']");
          Locator passwordField = page.locator("#password");
          Locator userCode = page.locator("input[name='device_user_code']");
          if (accept.count() > 0) {
            assertThat(page.locator("#krt-device-consent-warning")).isVisible();
            assertThat(page.locator("#krt-device-user-code")).hasText(login.userCode());
            consentPageSeen = true;
            accept.click();
            detached(accept);
          } else if (passwordField.count() > 0) {
            E2eSupport.submitKeycloakLogin(page, member, password);
            detached(passwordField);
          } else if (userCode.count() > 0) {
            assertThat(page.locator("#krt-device-phishing-warning")).isVisible();
            userCode.fill(login.userCode());
            codePageSeen = true;
            page.locator("#kc-user-verify-device-user-code-form input[type='submit']").click();
            detached(userCode);
          } else {
            assertTrue(codePageSeen, "the bare verification page asked for the user code");
            assertTrue(consentPageSeen, "the device login reached the consent page");
            assertThat(page.locator("#krt-home-link")).hasAttribute("href", "/");
            assertThat(page.locator("#krt-close-tab")).isVisible();
            assertThat(page.locator("#krt-close-tab-hint")).isHidden();
            return;
          }
        }
        throw new AssertionError("the device page did not finish within six steps");
      } catch (AssertionError | RuntimeException failure) {
        E2eSupport.dump(page, "exchange-device-page");
        throw failure;
      }
    }
  }

  /**
   * Waits until a form element has left the page after its submit.
   *
   * @param element the element of the submitted form
   */
  private static void detached(Locator element) {
    element
        .first()
        .waitFor(
            new Locator.WaitForOptions()
                .setState(WaitForSelectorState.DETACHED)
                .setTimeout(30_000));
  }

  /**
   * Registers the client with the exchange capabilities, or re-activates it when an earlier run or
   * test left it suspended.
   *
   * @param seeder the backend seeder
   */
  static void registerClient(BackendSeeder seeder) {
    JsonObject existing = registryEntry(seeder);
    if (existing != null) {
      if (!"ACTIVE".equals(existing.get("status").getAsString())) {
        seeder.postBody(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/connected-apps/admin/clients/"
                + existing.get("id").getAsString()
                + "/activate",
            "{\"version\":" + existing.get("version").getAsLong() + "}");
      }
      return;
    }
    JsonObject request = new JsonObject();
    request.addProperty("clientId", CLIENT_ID);
    request.addProperty("displayName", CLIENT_NAME);
    JsonArray capabilities = new JsonArray();
    CAPABILITIES.forEach(capabilities::add);
    request.add("capabilities", capabilities);
    seeder.postBody(
        ADMIN, ADMIN_PASSWORD, "/api/v1/connected-apps/admin/clients", request.toString());
  }

  /**
   * Reads the client's registry entry through the admin API.
   *
   * @param seeder the backend seeder
   * @return the entry with its {@code id}, {@code status} and {@code version}, or {@code null} when
   *     the client is not registered
   */
  static JsonObject registryEntry(BackendSeeder seeder) {
    JsonArray clients =
        JsonParser.parseString(
                seeder.getBody(ADMIN, ADMIN_PASSWORD, "/api/v1/connected-apps/admin/clients"))
            .getAsJsonArray();
    for (JsonElement element : clients) {
      JsonObject entry = element.getAsJsonObject();
      if (CLIENT_ID.equals(entry.get("clientId").getAsString())) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Switches the global exchange switch on when it is off.
   *
   * @param seeder the backend seeder
   */
  static void switchExchangeOn(BackendSeeder seeder) {
    JsonObject settings =
        JsonParser.parseString(
                seeder.getBody(ADMIN, ADMIN_PASSWORD, "/api/v1/connected-apps/admin/settings"))
            .getAsJsonObject();
    if (settings.get("enabled").getAsBoolean()) {
      return;
    }
    int status =
        seeder.putForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/connected-apps/admin/settings",
            "{\"enabled\":true,\"version\":" + settings.get("version").getAsLong() + "}");
    assertEquals(200, status, "the exchange switch turns on");
  }

  /**
   * Repeats a gateway call until its answer matches, for a change the gateway sees through its
   * registry mirror.
   *
   * @param client the exchange client
   * @param method the HTTP method
   * @param path the gateway path
   * @param done the condition the answer must meet
   * @return the matching answer
   * @throws Exception if a call cannot be sent or no answer matches within {@link #MIRROR_WAIT}
   */
  static ExchangeTestClient.Answer awaitAnswer(
      ExchangeTestClient client,
      String method,
      String path,
      Predicate<ExchangeTestClient.Answer> done)
      throws Exception {
    Instant deadline = Instant.now().plus(MIRROR_WAIT);
    ExchangeTestClient.Answer answer = client.call(method, path, null);
    while (!done.test(answer) && Instant.now().isBefore(deadline)) {
      Thread.sleep(2_000);
      answer = client.call(method, path, null);
    }
    assertTrue(done.test(answer), method + " " + path + " never answered as expected: " + answer);
    return answer;
  }

  /**
   * Builds a {@code set-quantity} op for one lot of a material at the seeded location.
   *
   * @param material the resolved material reference
   * @param quality the lot's quality
   * @param amount the new amount in SCU
   * @param expected the amount the client believes the lot holds
   * @return the op
   */
  static JsonObject setQuantity(JsonObject material, int quality, double amount, double expected) {
    JsonObject location = new JsonObject();
    location.addProperty("name", LOCATION_NAME);
    JsonObject op = new JsonObject();
    op.addProperty("op", "set-quantity");
    op.add("material", material);
    op.add("location", location);
    op.addProperty("quality", quality);
    op.addProperty("stolen", false);
    op.add("quantity", quantity(amount));
    op.add("expectedQuantity", quantity(expected));
    return op;
  }

  /**
   * Builds an {@code add} op for one blueprint, observed in the game log.
   *
   * @param ref the resolved blueprint reference
   * @return the op
   */
  static JsonObject addBlueprint(JsonObject ref) {
    JsonObject provenance = new JsonObject();
    provenance.addProperty("source", "log");
    JsonObject op = new JsonObject();
    op.addProperty("op", "add");
    op.add("ref", ref);
    op.add("provenance", provenance);
    return op;
  }

  /**
   * Builds an SCU quantity.
   *
   * @param amount the amount
   * @return {@code {amount, unit}}
   */
  static JsonObject quantity(double amount) {
    JsonObject quantity = new JsonObject();
    quantity.addProperty("amount", amount);
    quantity.addProperty("unit", "SCU");
    return quantity;
  }

  /**
   * Wraps ops into a change set.
   *
   * @param ops the ops, in order
   * @return {@code {"ops": [...]}}
   */
  static JsonObject changeSet(List<JsonObject> ops) {
    JsonArray array = new JsonArray();
    ops.forEach(array::add);
    JsonObject changeSet = new JsonObject();
    changeSet.add("ops", array);
    return changeSet;
  }

  /**
   * Reads the amount the member holds of a material at the seeded location and one quality.
   *
   * @param client the exchange client
   * @param material the resolved material reference
   * @param quality the lot's quality
   * @return the amount in SCU, {@code 0} when there is no such lot
   * @throws Exception if the call cannot be sent
   */
  static double heldAmount(ExchangeTestClient client, JsonObject material, int quality)
      throws Exception {
    ExchangeTestClient.Answer answer = client.call("GET", "/exchange/v1/me/stock", null);
    assertOk(answer);
    String bt = material.get("bt").getAsString();
    double amount = 0;
    for (JsonElement item : answer.body().getAsJsonArray("items")) {
      JsonObject lot = item.getAsJsonObject();
      if (bt.equals(lot.getAsJsonObject("material").get("bt").getAsString())
          && LOCATION_NAME.equals(lot.getAsJsonObject("location").get("name").getAsString())
          && lot.has("quality")
          && !lot.get("quality").isJsonNull()
          && lot.get("quality").getAsInt() == quality) {
        amount += lot.getAsJsonObject("quantity").get("amount").getAsDouble();
      }
    }
    return amount;
  }

  /**
   * Asserts a gateway answer is a success.
   *
   * @param answer the answer
   */
  static void assertOk(ExchangeTestClient.Answer answer) {
    assertTrue(answer.status() == 200 || answer.status() == 201, "expected success: " + answer);
  }

  /**
   * Asserts a change result applied every one of its ops.
   *
   * @param answer the answer
   * @param ops the number of ops the change set held
   */
  static void assertApplied(ExchangeTestClient.Answer answer, int ops) {
    assertEquals(200, answer.status(), "the change set is accepted: " + answer);
    assertEquals(ops, answer.body().get("applied").getAsInt(), "every op is applied: " + answer);
    assertEquals(0, answer.body().get("notApplied").getAsInt(), "no op is refused: " + answer);
  }
}
