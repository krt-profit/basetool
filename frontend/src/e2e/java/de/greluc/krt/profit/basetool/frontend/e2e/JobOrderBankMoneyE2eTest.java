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
import com.microsoft.playwright.Route;
import java.util.ArrayList;
import java.util.List;
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
        assertThat(order.getByTestId("order-money-repair-save")).isHidden();
        prepareDeposit(order, fixture, fixture.collectionAccount(), SIX_MILLION);
        confirm(
            staff,
            pendingRequest(seeder, fixture.collectionAccount(), 4_000_000),
            fixture.holderA());
        figure(order, "order-money-provided", "4.000.000");

        assertThat(order.getByTestId("order-money-amount")).hasValue("6.000.000");
        assertThat(order.getByTestId("order-money-bank-account"))
            .hasValue(fixture.collectionAccount());
        assertThat(order.getByTestId("order-money-bank")).isChecked();
        assertThat(order.getByTestId("order-money-bank-payment")).isChecked();
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
        returnPersonalReceipt(order, staff, seeder, fixture, money, designation);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(order, "joborder-bank-money-order");
        E2eSupport.dump(staff, "joborder-bank-money-staff");
        throw failure;
      }
    }
  }

  private static void returnPersonalReceipt(
      Page order,
      Page staff,
      BackendSeeder seeder,
      Fixture fixture,
      String money,
      String designation) {
    String writer = seeder.getUserId(WRITER, password(WRITER));
    String payer = seeder.registerBankHolder(STAFF, password(STAFF), writer);
    assertEquals(
        201,
        seeder.bankDeposit(STAFF, password(STAFF), fixture.collectionAccount(), payer, 1_000_000));
    order.getByTestId("order-money-provide").click();
    order.getByTestId("order-money-bank-free").check();
    order.getByTestId("order-money-amount").fill(ONE_MILLION);
    String holder =
        post(order, money + "/provisions", "order-money-save").get("holderId").getAsString();

    order.getByTestId("order-money-move").click();
    order.getByTestId("order-money-movement-kind").selectOption("HANDOVER");
    order.getByTestId("order-money-source-holder").selectOption(holder);
    order.getByTestId("order-money-handover-recipient").selectOption(designation);
    order.getByTestId("order-money-movement-amount").fill("300000");
    var handoverAccount = order.getByTestId("order-money-movement-bank-account");
    handoverAccount.selectOption(fixture.collectionAccount());
    var payingCustodian = order.getByTestId("order-money-movement-bank-custodian");
    var receivingCustodian = order.getByTestId("order-money-movement-bank-target-custodian");
    payingCustodian.selectOption(payer);
    receivingCustodian.selectOption(fixture.holderA());
    order.getByTestId("order-money-movement-bank-payment").check();
    try (var paused = new PausedBankOptions(order, money)) {
      paused.refresh();
      assertThat(handoverAccount).hasValue(fixture.collectionAccount());
      assertThat(payingCustodian).hasValue(payer);
      assertThat(receivingCustodian).hasValue(fixture.holderA());
      assertThat(order.getByTestId("order-money-movement-save")).isDisabled();
    }
    var handover = post(order, money + "/movements", "order-money-movement-save");
    assertEquals("POSTED", handover.get("state").getAsString());
    figure(staff, "order-money-handed-over", "9.300.000");

    staff.getByTestId("order-money-move").click();
    staff.getByTestId("order-money-movement-kind").selectOption("REQUESTER_RETURN");
    staff.locator("#order-money-movement-source-type").selectOption("player");
    staff.getByTestId("order-money-handover-recipient").selectOption(designation);
    staff.getByTestId("order-money-destination-holder").selectOption(holder);
    staff.getByTestId("order-money-movement-amount").fill("100000");
    var source = staff.getByTestId("order-money-movement-bank-account");
    assertThat(source).hasValue(fixture.requesterAccount());
    assertThat(source).isDisabled();
    assertThat(staff.getByTestId("order-money-source-holder")).isHidden();
    staff.getByTestId("order-money-movement-bank-custodian").selectOption(fixture.holderA());
    staff.getByTestId("order-money-movement-bank-justification").fill("E2E requester return");
    var preview =
        staff.waitForResponse(
            result -> result.url().endsWith(money + "/movements/settlement/preview"),
            () -> staff.getByTestId("order-money-movement-bank-payment").check());
    assertTrue(preview.ok(), () -> preview.status() + " " + preview.text());
    if (json(preview.text()).get("fee").getAsBigDecimal().signum() > 0) {
      staff.getByTestId("order-money-movement-top-up").check();
    }
    var returned = post(staff, money + "/movements/settlement", "order-money-movement-save");
    assertEquals("POSTED", returned.get("state").getAsString());
    assertEquals("REQUESTER_RETURN", returned.get("kind").getAsString());
    assertTrue(returned.get("actualPayment").getAsBoolean());
    assertEquals(holder, returned.get("destinationHolderId").getAsString());
    figure(order, "order-money-handed-over", "9.200.000");
    var current = json(seeder.getBody(WRITER, password(WRITER), "/api/v1" + money));
    assertEquals(
        1,
        current.getAsJsonArray("history").asList().stream()
            .map(element -> element.getAsJsonObject().get("kind").getAsString())
            .filter("HANDOVER"::equals)
            .count());
    assertEquals(
        "9000000",
        current
            .getAsJsonArray("accountReturns")
            .get(0)
            .getAsJsonObject()
            .get("availableAmount")
            .getAsString());
  }

  /** Records the reported payout through the source account and its paying custodian. */
  @Test
  void settlesWithAnAccountlessBeneficiary() {
    assumeTrue(STACK.managesStack(), "requires isolated Bank and Order fixtures");
    var seeder = new BackendSeeder();
    var fixture = seed(seeder);
    String writer = seeder.getUserId(WRITER, password(WRITER));
    String custodian = seeder.registerBankHolder(STAFF, password(STAFF), writer);
    assertEquals(
        201,
        seeder.bankDeposit(
            STAFF, password(STAFF), fixture.collectionAccount(), custodian, 1_000_000));

    String base = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (var playwright = Playwright.create();
        var browser = E2eSupport.launchBrowser(playwright, true);
        var context = browser.newContext(options)) {
      var order = context.newPage();
      E2eSupport.login(order, base, WRITER, password(WRITER));
      E2eSupport.navigate(order, base + "/orders/create?lang=de");
      String orderPath =
          JobOrderMoneyE2eTest.createOrder(
              order, base, fixture.collectionUnit(), fixture.requesterUnit(), ONE_MILLION);
      String money = orderPath + "/money";
      order.getByTestId("order-money-provide").click();
      order.getByTestId("order-money-bank-free").check();
      order.getByTestId("order-money-amount").fill(ONE_MILLION);
      String holder =
          post(order, money + "/provisions", "order-money-save").get("holderId").getAsString();
      figure(order, "order-money-holdings", "1.000.000");

      order.getByTestId("order-money-move").click();
      assertThat(order.getByTestId("order-money-movement-bank")).isChecked();
      order.getByTestId("order-money-movement-kind").selectOption("SETTLEMENT");
      order.getByTestId("order-money-source-holder").selectOption(holder);
      String beneficiary = "E2E Beneficiary";
      order.getByTestId("order-money-destination-choice").fill(beneficiary);
      order.locator("li[role='option'][data-value='accountless']:visible").click();
      order.getByTestId("order-money-movement-amount").fill("300000");
      order
          .getByTestId("order-money-movement-bank-account")
          .selectOption(fixture.collectionAccount());
      order.getByTestId("order-money-movement-bank-custodian").selectOption(custodian);
      assertThat(order.getByTestId("order-money-movement-bank-target-custodian")).isHidden();
      order.getByTestId("order-money-movement-bank-justification").fill("E2E beneficiary payment");
      order.getByTestId("order-money-movement-bank-payment").check();

      var settled = post(order, money + "/movements/settlement", "order-money-movement-save");
      assertEquals("POSTED", settled.get("state").getAsString());
      assertEquals("SETTLEMENT", settled.get("kind").getAsString());
      assertTrue(settled.get("actualPayment").getAsBoolean());
      assertEquals(beneficiary, settled.get("counterpartyName").getAsString());
      assertEquals(
          700_000 - settled.get("actualFee").getAsLong(),
          balance(seeder, fixture.collectionAccount()));
      var result = json(seeder.getBody(WRITER, password(WRITER), "/api/v1" + money));
      assertEquals("300000", result.get("settled").getAsString());
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
    var accounts = order.getByTestId("order-money-recipient-bank-account");
    accounts.selectOption(account);
    try (var paused = new PausedBankOptions(order, money)) {
      paused.refresh();
      assertThat(accounts).hasValue(account);
      String designation =
          post(order, money + "/recipients", "order-money-recipient-save").get("id").getAsString();
      assertThat(order.locator("[data-money-bank-recipient='" + designation + "']")).isAttached();
      return designation;
    }
  }

  /** Pauses Bank options reads while the form responds to a money fragment update. */
  private static final class PausedBankOptions implements AutoCloseable {
    private final Page page;
    private final String path;
    private final List<Route> pending = new ArrayList<>();

    private PausedBankOptions(Page page, String money) {
      this.page = page;
      path = "**" + money + "/bank-accounts";
      page.route(path, pending::add);
    }

    private void refresh() {
      page.locator("#order-money-results")
          .evaluate(
              "container => document.dispatchEvent("
                  + "new CustomEvent('krt:swapped', { detail: { container } }))");
      page.waitForCondition(() -> !pending.isEmpty());
    }

    @Override
    public void close() {
      page.unroute(path);
      pending.forEach(Route::resume);
    }
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
