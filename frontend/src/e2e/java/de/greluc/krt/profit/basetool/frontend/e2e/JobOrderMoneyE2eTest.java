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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises exact monetary terms and committed operation recovery in the shared order workspace.
 */
@Tag("e2e")
class JobOrderMoneyE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM = "00000000-0000-0000-0000-000000000001";
  private static final String READER = "test-member";
  private static final String READER_PASSWORD = "test-member-pw";
  private static final String AMOUNT = "9007199254740993";
  private static final String GROUPED_AMOUNT = "9.007.199.254.740.993";

  /** Records one Bank-free provision and reads the same committed outcome after response loss. */
  @Test
  void recoversExactCommittedProvisionAfterReload() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/orders/create?lang=de");
        String orderPath = createOrder(page, baseUrl, IRIDIUM);
        assertThat(page.getByTestId("order-money-target")).containsText(GROUPED_AMOUNT);
        assertThat(page.locator("#tab-materials, #tab-items")).hasCount(0);

        AtomicReference<JsonObject> submitted = new AtomicReference<>();
        AtomicReference<JsonObject> committed = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        page.route(
            "**" + orderPath + "/money/provisions",
            route -> {
              attempts.incrementAndGet();
              submitted.set(json(route.request().postData()));
              var response = route.fetch();
              assertEquals(200, response.status());
              committed.set(json(response.text()));
              route.abort();
            });

        page.getByTestId("order-money-provide").click();
        Locator amount = page.getByTestId("order-money-amount");
        amount.pressSequentially(AMOUNT);
        assertEquals(GROUPED_AMOUNT, shownAmount(amount));
        amount.press("Home");
        amount.press("ArrowRight");
        amount.pressSequentially("8");
        assertEquals("98.007.199.254.740.993", shownAmount(amount));
        assertEquals(2, ((Number) amount.evaluate("field => field.selectionStart")).intValue());
        amount.press("Backspace");
        assertEquals(GROUPED_AMOUNT, shownAmount(amount));
        assertEquals(AMOUNT, amount.evaluate("field => field.value"));
        assertEquals(AMOUNT, amount.evaluate("field => new FormData(field.form).get(field.name)"));
        page.getByTestId("order-money-bank-free").check();
        page.getByTestId("order-money-save").click();
        assertThat(page.getByTestId("order-money-unknown")).isVisible();

        JsonObject terms = submitted.get();
        assertNotNull(terms);
        assertEquals(AMOUNT, terms.get("amount").getAsString());
        assertEquals(false, terms.get("bankHandling").getAsBoolean());
        String operationId = terms.get("operationId").getAsString();
        assertEquals(operationId, UUID.fromString(operationId).toString());
        assertEquals(operationId, committed.get().get("operationId").getAsString());

        var recovered =
            page.waitForResponse(
                response ->
                    response.url().endsWith(orderPath + "/money/operations/" + operationId)
                        && "GET".equals(response.request().method()),
                page::reload);
        assertEquals(200, recovered.status());
        assertEquals(operationId, json(recovered.text()).get("operationId").getAsString());
        assertEquals(AMOUNT, json(recovered.text()).get("amount").getAsString());
        assertThat(page.getByTestId("order-money-provided")).containsText(GROUPED_AMOUNT);
        assertThat(page.getByTestId("order-money-history-entry")).hasCount(1);
        assertThat(page.getByTestId("order-money-history-entry")).containsText(GROUPED_AMOUNT);
        assertEquals(1, attempts.get());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-money-recovery");
        throw failure;
      }
    }
  }

  /**
   * Keeps a reader's open draft while the responsible writer provisions for an accountless holder.
   */
  @Test
  void reportsAnAccountlessHolderAndKeepsAPeerDraft() {
    assumeTrue(STACK.managesStack(), "requires the isolated requester fixture");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String requestingUnit = assignReaderHome(seeder, suffix);

    String baseUrl = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext writerContext = browser.newContext(options);
        BrowserContext readerContext = browser.newContext(options)) {
      Page writer = writerContext.newPage();
      Page reader = readerContext.newPage();
      Page queue = readerContext.newPage();
      E2eSupport.login(writer, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(writer, baseUrl + "/orders/create?lang=de");
      String orderPath = createOrder(writer, baseUrl, requestingUnit);
      String orderId = orderPath.substring("/orders/".length());
      E2eSupport.login(reader, baseUrl, READER, READER_PASSWORD);
      E2eSupport.navigate(reader, baseUrl + orderPath + "?lang=de");
      E2eSupport.navigate(queue, baseUrl + "/orders?type=AUEC");
      var row = queue.locator("[data-testid='order-row'][data-id='" + orderId + "']");
      assertThat(row).isVisible();
      waitForRoom(reader, "order:" + orderId);
      waitForRoom(queue, "orders");

      reader.getByTestId("order-money-provide").click();
      reader.getByTestId("order-money-amount").fill("78");
      assertThat(reader.getByTestId("order-money-holder-mode")).isHidden();

      String holder = "E2E Accountless " + suffix;
      writer.getByTestId("order-money-provide").click();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-holder-choice").fill(holder);
      writer.locator("li[role='option'][data-value='accountless']").click();
      writer.getByTestId("order-money-amount").fill("1.234,5");
      writer.getByTestId("order-money-bank-free").check();
      assertThat(writer.getByTestId("order-money-save")).isDisabled();

      Locator invalidAmount = writer.getByTestId("order-money-amount");
      invalidAmount.fill("-1");
      assertEquals("-1", shownAmount(invalidAmount));
      assertThat(writer.getByTestId("order-money-save")).isDisabled();
      JsonObject unbooked =
          json(seeder.getBody(USERNAME, PASSWORD, "/api/v1" + orderPath + "/money"));
      assertEquals("0", unbooked.get("provided").getAsString());
      assertThat(writer.getByTestId("order-money-history-entry")).hasCount(0);

      var preview =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions/preview"),
              () -> writer.getByTestId("order-money-amount").fill("1.234"));
      assertEquals(200, preview.status());
      JsonObject proposed = json(preview.text());
      assertEquals("1234", proposed.get("amount").getAsString());
      assertEquals("0", proposed.get("fee").getAsString());
      assertThat(writer.getByTestId("order-money-preview")).containsText("1.234");
      assertThat(writer.getByTestId("order-money-history-entry")).hasCount(0);
      var saved =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions"),
              () -> writer.getByTestId("order-money-save").click());
      assertEquals(200, saved.status());
      assertEquals(holder, json(saved.request().postData()).get("holderName").getAsString());

      assertThat(writer.getByTestId("order-money-history-entry")).containsText(holder);
      assertThat(reader.getByTestId("order-money-history-entry")).containsText(holder);
      assertThat(reader.getByTestId("order-money-amount")).hasValue("78");
      assertThat(row.locator(".status-in_progress")).isVisible();
      assertThat(writer.getByTestId("order-money-holdings")).containsText("1.234");
      assertThat(writer.getByTestId("order-money-remaining")).containsText(GROUPED_AMOUNT);
    }
  }

  /** Adds an existing order to an open workspace only after the writer selects a real account. */
  @Test
  void linksAnExplicitAccountAndRefreshesItsOpenAllQueue() {
    assumeTrue(STACK.managesStack(), "requires the isolated holder fixture");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    assignReaderHome(seeder, UUID.randomUUID().toString().substring(0, 8));
    JsonObject member = json(seeder.getBody(READER, READER_PASSWORD, "/api/v1/users/me"));
    String memberId = member.get("id").getAsString();
    String memberName = member.get("effectiveName").getAsString();

    String baseUrl = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext writerContext = browser.newContext(options);
        BrowserContext readerContext = browser.newContext(options)) {
      Page writer = writerContext.newPage();
      Page queue = readerContext.newPage();
      E2eSupport.login(writer, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(writer, baseUrl + "/orders/create?lang=de");
      String orderPath = createOrder(writer, baseUrl, IRIDIUM);
      String orderId = orderPath.substring("/orders/".length());
      prepareSelfProvision(writer, "10");
      writer.getByTestId("order-money-save").click();
      assertThat(writer.locator("#order-status-badge.status-active")).isVisible();

      E2eSupport.login(queue, baseUrl, READER, READER_PASSWORD);
      E2eSupport.navigate(queue, baseUrl + "/orders?type=AUEC");
      assertThat(queue.locator("input[name='scope'][value='ALL']")).isChecked();
      waitForRoom(queue, "orders");
      var row = queue.locator("[data-testid='order-row'][data-id='" + orderId + "']");
      assertThat(row).hasCount(0);
      queue.evaluate("window.__moneyQueueKept = true");

      writer.getByTestId("order-money-provide").click();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-amount").fill("25");
      writer.getByTestId("order-money-bank-free").check();
      var account = writer.getByTestId("order-money-holder-choice");
      var candidate = writer.locator("li[role='option'][data-value='" + memberId + "']");
      String partialName = memberName.substring(0, memberName.length() - 1);
      account.fill(partialName);
      assertThat(candidate).isVisible();
      account.fill(memberName);
      writer.getByTestId("order-money-amount").click();
      assertThat(account).hasValue("");
      assertThat(writer.getByTestId("order-money-save")).isDisabled();

      account.fill(memberName);
      candidate.click();
      var saved =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions"),
              () -> writer.getByTestId("order-money-save").click());
      assertEquals(200, saved.status());
      assertEquals(memberId, json(saved.request().postData()).get("holderUserId").getAsString());
      String holderId = json(saved.text()).get("holderId").getAsString();
      assertThat(row.locator(".status-in_progress")).isVisible();
      assertEquals(Boolean.TRUE, queue.evaluate("window.__moneyQueueKept === true"));
      row.getByTestId("row-link").click();
      waitForRoom(queue, "order:" + orderId);
      assertThat(queue.getByTestId("order-money-provided")).containsText("35");

      writer.getByTestId("order-money-provide").click();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-holder-choice").fill(memberName);
      writer.locator("li[role='option'][data-value='accountless']").click();
      writer.getByTestId("order-money-amount").fill("2");
      writer.getByTestId("order-money-bank-free").check();
      var named =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions"),
              () -> writer.getByTestId("order-money-save").click());
      assertEquals(200, named.status());
      String accountlessId = json(named.text()).get("holderId").getAsString();
      assertNotEquals(holderId, accountlessId);

      writer.getByTestId("order-money-provide").click();
      writer.getByTestId("order-money-holder-mode").selectOption("existing");
      var existing = writer.getByTestId("order-money-existing-holder");
      assertThat(existing.locator("option[value='" + holderId + "']"))
          .hasText(memberName + " (mit Basetool-Konto)");
      assertThat(existing.locator("option[value='" + accountlessId + "']"))
          .hasText(memberName + " (ohne Basetool-Konto)");
      existing.selectOption(holderId);
      writer.getByTestId("order-money-amount").fill("5");
      writer.getByTestId("order-money-bank-free").check();
      var appended =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions"),
              () -> writer.getByTestId("order-money-save").click());
      assertEquals(200, appended.status());
      JsonObject appendedTerms = json(appended.request().postData());
      assertEquals(holderId, appendedTerms.get("holderId").getAsString());
      assertEquals(1, appendedTerms.get("holderVersion").getAsInt());
      assertEquals(holderId, json(appended.text()).get("holderId").getAsString());
      assertThat(queue.getByTestId("order-money-provided")).containsText("42");
      assertThat(queue.locator("[data-money-holder='" + accountlessId + "']"))
          .containsText("2 aUEC");
    }
  }

  /** Restores a refused stale draft after resolving its originally uncertain operation. */
  @Test
  void resolvesAnUnknownStaleAttemptBeforeBookingWithFreshTerms() {
    assumeTrue(STACK.managesStack(), "requires the isolated provision fixture");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext actorContext = browser.newContext(options);
        BrowserContext peerContext = browser.newContext(options)) {
      Page actor = actorContext.newPage();
      Page peer = peerContext.newPage();
      E2eSupport.login(actor, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(actor, baseUrl + "/orders/create?lang=de");
      String orderPath = createOrder(actor, baseUrl, IRIDIUM);
      String provisions = orderPath + "/money/provisions";
      E2eSupport.login(peer, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(peer, baseUrl + orderPath + "?lang=de");
      waitForRoom(actor, "order:" + orderPath.substring("/orders/".length()));

      AtomicReference<JsonObject> original = new AtomicReference<>();
      AtomicInteger attempts = new AtomicInteger();
      actor.route(
          "**" + provisions,
          route -> {
            if (attempts.incrementAndGet() == 1) {
              original.set(json(route.request().postData()));
              route.abort();
            } else {
              route.resume();
            }
          });
      prepareSelfProvision(actor, "37");
      actor.getByTestId("order-money-save").click();
      assertThat(actor.getByTestId("order-money-unknown")).isVisible();
      assertNotNull(original.get());
      String operation = original.get().get("operationId").getAsString();

      prepareSelfProvision(peer, "11");
      var peerBooked =
          peer.waitForResponse(
              response -> response.url().endsWith(provisions),
              () -> peer.getByTestId("order-money-save").click());
      assertEquals(200, peerBooked.status());
      assertThat(peer.getByTestId("order-money-provided")).containsText("11");
      assertThat(peer.getByTestId("order-money-history-entry")).hasCount(1);
      var unknown =
          actor.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/operations/" + operation),
              () -> actor.locator("[data-money-check]").click());
      assertEquals(404, unknown.status());

      var refused =
          actor.waitForResponse(
              response -> response.url().endsWith(provisions),
              () -> actor.locator("[data-money-retry]").click());
      assertEquals(original.get(), json(refused.request().postData()));
      assertEquals(409, refused.status());
      assertThat(actor.getByTestId("order-money-amount")).isVisible();
      assertThat(actor.getByTestId("order-money-amount")).hasValue("37");
      assertThat(actor.getByTestId("order-money-bank-free")).not().isChecked();
      assertThat(actor.getByTestId("order-money-save")).isDisabled();
      assertEquals(2, attempts.get());
      assertEquals("MONEY_PROVISION_REFUSED", json(refused.text()).get("code").getAsString());

      actor.getByTestId("order-money-bank-free").check();
      var booked =
          actor.waitForResponse(
              response -> response.url().endsWith(provisions),
              () -> actor.getByTestId("order-money-save").click());
      assertEquals(200, booked.status());
      JsonObject newTerms = json(booked.request().postData());
      assertNotEquals(operation, newTerms.get("operationId").getAsString());
      assertEquals("37", newTerms.get("amount").getAsString());
      assertEquals(1, newTerms.get("holderVersion").getAsInt());
      assertThat(actor.getByTestId("order-money-provided")).containsText("48");
      assertThat(actor.getByTestId("order-money-history-entry")).hasCount(2);
      assertEquals(3, attempts.get());
    }
  }

  /**
   * Collects separate provisions, recovers a committed partial handover and completes on target.
   */
  @Test
  void collectsAndHandsOverTheTargetOnceAfterResponseLoss() {
    assumeTrue(STACK.managesStack(), "requires the isolated monetary order stack");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(page, baseUrl + "/orders/create?lang=de");
      String orderPath = createOrder(page, baseUrl, IRIDIUM, "10");
      String provisions = orderPath + "/money/provisions";
      String movements = orderPath + "/money/movements";

      prepareSelfProvision(page, "4");
      String firstHolder =
          json(awaitPost(page, provisions, "order-money-save")).get("holderId").getAsString();
      assertThat(page.getByTestId("order-money-provided")).containsText("4 aUEC");

      page.getByTestId("order-money-provide").click();
      page.getByTestId("order-money-holder-mode").selectOption("new");
      chooseAccountless(page, "order-money-holder-choice", "Second holder");
      page.getByTestId("order-money-amount").fill("6");
      page.getByTestId("order-money-bank-free").check();
      String secondHolder =
          json(awaitPost(page, provisions, "order-money-save")).get("holderId").getAsString();
      assertThat(page.getByTestId("order-money-provided")).containsText("10 aUEC");

      beginMovement(page, "COLLECTION", firstHolder, "4");
      page.getByTestId("order-money-destination-mode").selectOption("new");
      chooseAccountless(page, "order-money-destination-choice", "Collector");
      page.getByTestId("order-money-movement-bank-free").check();
      assertThat(page.getByTestId("order-money-movement-preview")).containsText("Collector");
      String collector =
          json(awaitPost(page, movements, "order-money-movement-save"))
              .get("destinationHolderId")
              .getAsString();
      assertThat(page.locator("[data-money-holder='" + collector + "']")).containsText("4 aUEC");

      beginMovement(page, "COLLECTION", secondHolder, "6");
      page.getByTestId("order-money-destination-mode").selectOption("existing");
      page.getByTestId("order-money-destination-holder").selectOption(collector);
      page.getByTestId("order-money-movement-bank-free").check();
      awaitPost(page, movements, "order-money-movement-save");
      assertThat(page.locator("[data-money-holder='" + collector + "']")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-provided")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-handed-over")).containsText("0 aUEC");

      page.getByTestId("order-money-designate-recipient").click();
      page.getByTestId("order-money-recipient-mode").selectOption("new");
      chooseAccountless(page, "order-money-recipient-choice", "Requester recipient");
      awaitPost(page, orderPath + "/money/recipients", "order-money-recipient-save");
      Locator recipient = page.locator("[data-money-recipient]");
      assertThat(recipient).containsText("Requester recipient");
      String recipientId = recipient.getAttribute("data-money-recipient");

      AtomicReference<String> accepted = new AtomicReference<>();
      page.route(
          "**" + movements,
          route -> {
            var response = route.fetch();
            assertEquals(200, response.status());
            accepted.set(json(response.text()).get("operationId").getAsString());
            route.abort();
          });
      prepareHandover(page, collector, recipientId, "3");
      page.getByTestId("order-money-movement-save").click();
      assertThat(page.getByTestId("order-money-unknown")).isVisible();
      assertNotNull(accepted.get());
      String outcome = orderPath + "/money/operations/" + accepted.get();
      var recovered =
          page.waitForResponse(response -> response.url().endsWith(outcome), page::reload);
      assertEquals(200, recovered.status());
      assertEquals("HANDOVER", json(recovered.text()).get("kind").getAsString());
      assertThat(page.getByTestId("order-money-handed-over")).containsText("3 aUEC");
      assertThat(page.getByTestId("order-money-holdings")).containsText("7 aUEC");
      assertThat(page.locator("#order-status-badge.status-active")).isVisible();
      page.unroute("**" + movements);

      prepareHandover(page, collector, recipientId, "7");
      awaitPost(page, movements, "order-money-movement-save");
      assertThat(page.locator("#order-status-badge.status-completed")).isVisible();
      assertThat(page.getByTestId("order-money-provided")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-holdings")).containsText("0 aUEC");
      assertThat(page.getByTestId("order-money-handed-over")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-remaining")).containsText("0 aUEC");
      assertThat(page.getByTestId("order-money-history-entry")).hasCount(6);

      page.getByTestId("order-edit").click();
      Locator target = page.getByTestId("order-money-edit-target");
      target.fill("12");
      page.getByTestId("order-money-edit-handle").fill("trace-contact");
      awaitPatch(page, orderPath + "/money/information", "order-money-save-information");
      assertThat(page.locator("#order-header-results")).containsText("trace-contact");
      assertEquals("12", shownAmount(target));
      awaitPatch(page, orderPath + "/money/target", "order-money-save-target");
      assertThat(page.locator("#order-status-badge.status-active")).isVisible();
      assertThat(page.getByTestId("order-money-handed-over")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-remaining")).containsText("2 aUEC");
      assertThat(page.getByTestId("order-money-target-change")).containsText("10 auf 12 aUEC");
      assertThat(page.locator("#order-header-results")).containsText("trace-contact");
    }
  }

  private static void awaitPatch(Page page, String path, String saveTestId) {
    awaitWrite(page, path, saveTestId, "PATCH");
  }

  private static String awaitPost(Page page, String path, String saveTestId) {
    return awaitWrite(page, path, saveTestId, "POST");
  }

  private static String awaitWrite(Page page, String path, String saveTestId, String method) {
    var saved =
        page.waitForResponse(
            response -> response.url().endsWith(path) && method.equals(response.request().method()),
            () -> page.getByTestId(saveTestId).click());
    assertTrue(saved.ok(), () -> saved.status() + " " + saved.text());
    return saved.text();
  }

  private static void beginMovement(Page page, String kind, String source, String amount) {
    page.getByTestId("order-money-move").click();
    page.getByTestId("order-money-movement-kind").selectOption(kind);
    page.getByTestId("order-money-source-holder").selectOption(source);
    page.getByTestId("order-money-movement-amount").fill(amount);
  }

  private static void prepareHandover(Page page, String source, String recipient, String amount) {
    beginMovement(page, "HANDOVER", source, amount);
    page.getByTestId("order-money-handover-recipient").selectOption(recipient);
    page.getByTestId("order-money-movement-bank-free").check();
  }

  private static void chooseAccountless(Page page, String field, String name) {
    page.getByTestId(field).fill(name);
    page.locator("li[role='option'][data-value='accountless']:visible").click();
  }

  private static void prepareSelfProvision(Page page, String amount) {
    page.getByTestId("order-money-provide").click();
    page.getByTestId("order-money-amount").fill(amount);
    page.getByTestId("order-money-bank-free").check();
  }

  private static Object shownAmount(Locator input) {
    return input.evaluate(
        "field => Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"
            + " 'value').get.call(field)");
  }

  private static String assignReaderHome(BackendSeeder seeder, String suffix) {
    String unit =
        seeder.createSquadron(USERNAME, PASSWORD, "Money requester " + suffix, "M" + suffix);
    String readerId = seeder.getUserId(READER, READER_PASSWORD);
    seeder.grantStoredRoles(READER, READER_PASSWORD, List.of("KRT_MEMBER"));
    seeder.assignStaffelMembership(USERNAME, PASSWORD, readerId, unit, false, false);
    return unit;
  }

  private static void waitForRoom(Page page, String room) {
    page.waitForFunction("topic => window.krtLiveSync?.subscribedTopics().includes(topic)", room);
  }

  private static String createOrder(Page page, String baseUrl, String requestingUnit) {
    return createOrder(page, baseUrl, requestingUnit, GROUPED_AMOUNT);
  }

  private static String createOrder(
      Page page, String baseUrl, String requestingUnit, String target) {
    page.getByTestId("order-mode-auec").click();
    page.locator("#auec-responsible-org-unit").selectOption(IRIDIUM);
    page.locator("#auec-requesting-org-unit").selectOption(requestingUnit);
    page.getByTestId("order-auec-handle").fill("E2E Money " + UUID.randomUUID());
    page.getByTestId("order-auec-target").fill(target);
    page.getByTestId("order-auec-submit").click();
    page.waitForURL(url ->
        url.startsWith(baseUrl + "/orders/")
            && URI.create(url).getPath().matches("/orders/[0-9a-f-]{36}"));
    return URI.create(page.url()).getPath();
  }

  private static JsonObject json(String body) {
    return JsonParser.parseString(body).getAsJsonObject();
  }
}
