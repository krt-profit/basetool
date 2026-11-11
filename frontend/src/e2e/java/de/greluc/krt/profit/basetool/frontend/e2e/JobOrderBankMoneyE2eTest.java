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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Exercises the default Bank path through the ordinary order actions. */
@Tag("e2e")
class JobOrderBankMoneyE2eTest {
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN = "test-admin";
  private static final String WRITER = "test-bank-member";
  private static final String EMPLOYEE = "test-bank-employee";
  private static final String STAFF = "test-bank-management";
  private static final String FOUR_MILLION = "4000000";
  private static final String SIX_MILLION = "6000000";
  private static final String TEN_MILLION = "10000000";
  private static final String ONE_MILLION = "1000000";

  /** Handles two real deposits, existing funds and requester transfers without an order holder. */
  @Test
  void creditsAndReturnsThroughAccountPositions() {
    assumeTrue(STACK.managesStack(), "requires isolated Bank and Order fixtures");
    var seeder = new BackendSeeder();
    var fixture = seed(seeder);
    String base = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (var playwright = Playwright.create();
        var browser = E2eSupport.launchBrowser(playwright, true);
        var orderContext = browser.newContext(options);
        var staffContext = browser.newContext(options)) {
      var order = orderContext.newPage();
      var staff = staffContext.newPage();
      try {
        E2eSupport.login(order, base, WRITER, password(WRITER));
        E2eSupport.navigate(order, base + "/orders/create?lang=de");
        String orderPath =
            JobOrderMoneyE2eTest.createOrder(
                order, base, fixture.collectionUnit(), fixture.requesterUnit(), TEN_MILLION);
        String money = orderPath + "/money";
        String operation = recoverPendingDeposit(order, money, fixture);
        figure(order, "order-money-provided", "0");
        order.waitForFunction(
            "topic => window.krtLiveSync?.subscribedTopics().includes(topic)",
            "order:" + orderPath.substring("/orders/".length()));

        E2eSupport.login(staff, base, STAFF, password(STAFF));
        E2eSupport.navigate(staff, base + "/bank/requests");
        reject(staff, pendingRequest(seeder, fixture.wrongAccount(), 5_000_000));
        assertThat(order.getByTestId("order-money-bank-repair")).isVisible();
        order.getByTestId("order-money-bank-repair").click();
        assertThat(order.getByTestId("order-money-repair-bank")).isChecked();
        assertThat(order.getByTestId("order-money-repair-bank-free")).isHidden();
        order.getByTestId("order-money-repair-account").selectOption(fixture.collectionAccount());
        order.getByTestId("order-money-repair-amount").fill(FOUR_MILLION);
        String repairPath = money + "/operations/" + operation + "/bank-replacement";
        var replacement = post(order, repairPath, "order-money-repair-save");
        assertEquals(operation, replacement.get("operationId").getAsString());
        assertEquals(2, replacement.get("linkedRevision").getAsInt());
        assertEquals("WAITING", replacement.get("state").getAsString());
        confirm(
            staff,
            pendingRequest(seeder, fixture.collectionAccount(), 4_000_000),
            fixture.holderA());
        figure(order, "order-money-provided", "4.000.000");

        prepareDeposit(order, fixture, fixture.collectionAccount(), SIX_MILLION);
        var second = post(order, money + "/provisions", "order-money-save");
        assertEquals("WAITING", second.get("state").getAsString());
        confirm(
            staff,
            pendingRequest(seeder, fixture.collectionAccount(), 6_000_000),
            fixture.holderB());
        figure(order, "order-money-provided", "10.000.000");
        assertEquals(0, balance(seeder, fixture.wrongAccount()));
        assertEquals(10_000_000, balance(seeder, fixture.collectionAccount()));

        order.getByTestId("order-money-provide").click();
        order.getByTestId("order-money-bank-purpose").selectOption("AUTHORISATION");
        order.getByTestId("order-money-bank-account").selectOption(fixture.collectionAccount());
        order.getByTestId("order-money-amount").fill(ONE_MILLION);
        var authorisation = post(order, money + "/provisions", "order-money-save");
        assertEquals("AUTHORISED", authorisation.get("state").getAsString());
        figure(order, "order-money-provided", "11.000.000");
        assertEquals(10_000_000, balance(seeder, fixture.collectionAccount()));

        String designation = designate(order, money, fixture.requesterAccount());
        String position =
            order
                .locator("[data-testid='order-money-account-holding'][data-available='11000000']")
                .getAttribute("data-money-account-holding");
        assertNotNull(position);
        prepareTransfer(order, "PURPOSE_HANDOVER", position, designation, TEN_MILLION);
        var delivery = post(order, money + "/movements", "order-money-movement-save");
        assertEquals("POSTED", delivery.get("state").getAsString());
        assertEquals("0", delivery.get("actualFee").getAsString());
        assertThat(order.locator("#order-status-badge.status-completed")).isVisible();
        assertEquals(10_000_000, balance(seeder, fixture.requesterAccount()));
        assertEquals(0, balance(seeder, fixture.collectionAccount()));

        adjust(order, money, position, "SHORTAGE", "250000");
        adjust(order, money, position, "RELEASE", "750000");
        figure(order, "order-money-holdings", "0");
        assertEquals(0, balance(seeder, fixture.collectionAccount()));

        E2eSupport.navigate(staff, base + orderPath + "?lang=de");
        prepareTransfer(staff, "REQUESTER_RETURN", position, designation, ONE_MILLION);
        var returned = post(staff, money + "/movements/settlement", "order-money-movement-save");
        assertEquals("POSTED", returned.get("state").getAsString());
        figure(order, "order-money-handed-over", "9.000.000");
        figure(order, "order-money-holdings", "1.000.000");
        figure(order, "order-money-provided", "11.000.000");
        assertEquals(9_000_000, balance(seeder, fixture.requesterAccount()));
        assertEquals(1_000_000, balance(seeder, fixture.collectionAccount()));
        var result = json(seeder.getBody(WRITER, password(WRITER), "/api/v1" + money));
        assertEquals(0, result.getAsJsonArray("holders").size());
        assertEquals(
            1,
            result.getAsJsonArray("accountHoldings").asList().stream()
                .map(element -> element.getAsJsonObject().get("amount").getAsBigDecimal())
                .filter(amount -> amount.signum() > 0)
                .count());
        assertFalse(result.toString().contains("Private fixture rejection"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(order, "joborder-bank-money-order");
        E2eSupport.dump(staff, "joborder-bank-money-staff");
        throw failure;
      }
    }
  }

  private static Fixture seed(BackendSeeder seeder) {
    String tag = UUID.randomUUID().toString().substring(0, 7);
    String collectionUnit = unit(seeder, "Collection " + tag, "C" + tag);
    String requesterUnit = unit(seeder, "Requester " + tag, "R" + tag);
    String wrongUnit = unit(seeder, "Alternative " + tag, "A" + tag);
    seeder.moveOrgUnitToProfit(ADMIN, password(ADMIN), collectionUnit);
    String writer = seeder.getUserId(WRITER, password(WRITER));
    String staff = seeder.getUserId(STAFF, password(STAFF));
    seeder.assignStaffelMembership(ADMIN, password(ADMIN), writer, collectionUnit, true, false);
    seeder.assignStaffelMembership(ADMIN, password(ADMIN), staff, requesterUnit, true, false);
    String collection = account(seeder, collectionUnit, "Collection " + tag);
    String requester = account(seeder, requesterUnit, "Requester " + tag);
    String wrong = account(seeder, wrongUnit, "Alternative " + tag);
    seeder.ensureBankGrant(STAFF, password(STAFF), writer, collection, true, true, true);
    seeder.ensureBankGrant(STAFF, password(STAFF), writer, requester, false, false, false);
    seeder.ensureBankGrant(STAFF, password(STAFF), writer, wrong, true, false, false);
    grantAccountVisibility(seeder, requester, writer);
    grantAccountVisibility(seeder, wrong, writer);
    String holderA =
        seeder.registerBankHolder(
            STAFF, password(STAFF), seeder.getUserId(EMPLOYEE, password(EMPLOYEE)));
    String holderB = seeder.registerBankHolder(STAFF, password(STAFF), staff);
    return new Fixture(
        collectionUnit, requesterUnit, collection, requester, wrong, holderA, holderB);
  }

  private static void grantAccountVisibility(BackendSeeder seeder, String account, String user) {
    seeder.postBody(
        ADMIN,
        password(ADMIN),
        "/api/v1/bank/org-units/accounts/" + account + "/visibility/user/" + user,
        "{}");
  }

  private static String recoverPendingDeposit(Page order, String money, Fixture fixture) {
    var accepted = new AtomicReference<JsonObject>();
    var submissions = new AtomicInteger();
    String path = money + "/provisions";
    order.route(
        "**" + path,
        route -> {
          submissions.incrementAndGet();
          var terms = json(route.request().postData());
          var bank = terms.getAsJsonObject("bank");
          assertTrue(terms.get("bankHandling").getAsBoolean());
          assertTrue(bank.get("accountOnly").getAsBoolean());
          assertFalse(bank.has("custodianId"));
          assertFalse(terms.has("holderId"));
          var response = route.fetch();
          assertTrue(response.ok(), response::text);
          accepted.set(json(response.text()));
          route.abort();
        });
    prepareDeposit(order, fixture, fixture.wrongAccount(), "5000000");
    order.getByTestId("order-money-save").click();
    assertThat(order.getByTestId("order-money-unknown")).isVisible();
    var original = accepted.get();
    assertNotNull(original);
    assertEquals("WAITING", original.get("state").getAsString());
    String operation = original.get("operationId").getAsString();
    var recovered =
        order.waitForResponse(
            response -> response.url().endsWith(money + "/operations/" + operation), order::reload);
    assertEquals("WAITING", json(recovered.text()).get("state").getAsString());
    assertThat(order.getByTestId("order-money-unknown")).isHidden();
    assertEquals(1, submissions.get());
    order.unroute("**" + path);
    return operation;
  }

  private static void prepareDeposit(Page order, Fixture fixture, String account, String amount) {
    order.getByTestId("order-money-provide").click();
    assertThat(order.getByTestId("order-money-bank")).isChecked();
    assertThat(order.getByTestId("order-money-bank-purpose")).hasValue("DEPOSIT");
    assertThat(order.getByTestId("order-money-bank-custodian")).isHidden();
    assertThat(order.getByTestId("order-money-holder-mode")).isHidden();
    var accounts = order.getByTestId("order-money-bank-account");
    assertThat(accounts).hasValue(fixture.collectionAccount());
    if (!fixture.collectionAccount().equals(account)) {
      accounts.selectOption(account);
    }
    order.getByTestId("order-money-amount").fill(amount);
    order.getByTestId("order-money-bank-payment").check();
  }

  private static String designate(Page order, String money, String account) {
    order.getByTestId("order-money-designate-recipient").click();
    order.getByTestId("order-money-recipient-mode").selectOption("bank");
    order.getByTestId("order-money-recipient-bank-account").selectOption(account);
    String designation =
        post(order, money + "/recipients", "order-money-recipient-save").get("id").getAsString();
    assertThat(order.locator("[data-money-bank-recipient='" + designation + "']")).isAttached();
    return designation;
  }

  private static void prepareTransfer(
      Page page, String kind, String position, String designation, String amount) {
    page.getByTestId("order-money-move").click();
    assertThat(page.getByTestId("order-money-movement-bank")).isChecked();
    page.getByTestId("order-money-movement-kind").selectOption(kind);
    page.getByTestId("order-money-movement-account").selectOption(position);
    page.getByTestId("order-money-handover-recipient").selectOption(designation);
    page.getByTestId("order-money-movement-amount").fill(amount);
    assertThat(page.getByTestId("order-money-source-holder")).isHidden();
  }

  private static void adjust(
      Page order, String money, String position, String kind, String amount) {
    order.getByTestId("order-money-move").click();
    order.getByTestId("order-money-movement-kind").selectOption(kind);
    assertThat(order.getByTestId("order-money-movement-bank")).isHidden();
    order.getByTestId("order-money-movement-account").selectOption(position);
    order.getByTestId("order-money-movement-amount").fill(amount);
    var result = post(order, money + "/movements/settlement", "order-money-movement-save");
    assertFalse(result.get("bankHandling").getAsBoolean());
    assertEquals(kind, result.get("kind").getAsString());
  }

  private static String pendingRequest(BackendSeeder seeder, String account, long amount) {
    String id = seeder.findOwnPendingBookingRequestId(WRITER, password(WRITER), account, amount);
    assertNotNull(id);
    return id;
  }

  private static void reject(Page staff, String request) {
    staff.locator(requestAction("reject", request)).click();
    staff.getByTestId("bank-reject-reason").fill("Private fixture rejection");
    post(staff, "/api/proxy/bank/requests/" + request + "/reject", "bank-reject-submit");
  }

  private static void confirm(Page staff, String request, String holder) {
    staff.locator(requestAction("confirm", request)).click();
    assertThat(staff.getByTestId("bank-confirm-holder")).isHidden();
    E2eSupport.selectComboboxByValue(
        staff.getByTestId("bank-confirm-account-deposit-holder"), holder);
    post(staff, "/api/proxy/bank/requests/" + request + "/confirm", "bank-confirm-submit");
  }

  private static String requestAction(String action, String request) {
    return "[data-testid='bank-request-" + action + "-btn'][data-field-_id='" + request + "']";
  }

  private static JsonObject post(Page page, String path, String testId) {
    var response =
        page.waitForResponse(
            result -> result.url().endsWith(path) && "POST".equals(result.request().method()),
            () -> page.getByTestId(testId).click());
    assertTrue(response.ok(), () -> response.status() + " " + response.text());
    return json(response.text());
  }

  private static void figure(Page page, String testId, String amount) {
    assertThat(page.getByTestId(testId).locator(".kpi-value")).hasText(amount + " aUEC");
  }

  private static String unit(BackendSeeder seeder, String name, String code) {
    return seeder.createSquadron(ADMIN, password(ADMIN), name, code);
  }

  private static String account(BackendSeeder seeder, String unit, String name) {
    return seeder.ensureOrgUnitBankAccount(STAFF, password(STAFF), name, unit);
  }

  private static long balance(BackendSeeder seeder, String account) {
    return seeder.bankAccountBalance(STAFF, password(STAFF), account);
  }

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  private static String password(String user) {
    return user + "-pw";
  }

  private record Fixture(
      String collectionUnit,
      String requesterUnit,
      String collectionAccount,
      String requesterAccount,
      String wrongAccount,
      String holderA,
      String holderB) {}
}
