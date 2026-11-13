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
import com.microsoft.playwright.Route;
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
  private static final String ORDER_WRITER = "test-order-writer";
  private static final String ORDER_WRITER_PASSWORD = ORDER_WRITER + "-pw";
  private static final String ORDER_READER = "test-order-reader";
  private static final String ORDER_READER_PASSWORD = ORDER_READER + "-pw";
  private static final String AMOUNT = "9007199254740993";
  private static final String GROUPED_AMOUNT = "9.007.199.254.740.993";

  /** Submits the localized target through the browser's native form navigation. */
  @Test
  void nativeCreateSubmitsCanonicalTargetAndRedirects() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(page, baseUrl + "/orders/create?lang=de");
      fillCreateForm(page, IRIDIUM, IRIDIUM, "6000000");
      assertEquals("6.000.000", shownAmount(page.getByTestId("order-auec-target")));
      var request =
          page.waitForRequest(
              candidate ->
                  candidate.url().endsWith("/orders/auec") && candidate.isNavigationRequest(),
              () -> page.locator("#mode-auec form").evaluate("form => form.submit()"));
      assertEquals("POST", request.method());
      assertTrue(
          request.headers().get("content-type").startsWith("application/x-www-form-urlencoded"));
      assertTrue(List.of(request.postData().split("&")).contains("target=6000000"));
      awaitCreatedOrder(page, baseUrl);
      assertThat(page.getByTestId("order-money-target")).containsText("6.000.000");
    }
  }

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
        fillCreateForm(page, IRIDIUM, IRIDIUM, GROUPED_AMOUNT);
        Locator summary = page.getByTestId("order-auec-summary");
        String requester =
            page.locator("#auec-requesting-org-unit option:checked").innerText().strip();
        assertThat(summary).hasText(GROUPED_AMOUNT + " aUEC · für " + requester);
        Locator target = page.getByTestId("order-auec-target");
        target.fill("");
        assertThat(summary).hasText("für " + requester);
        target.fill(GROUPED_AMOUNT);

        Locator comment = page.locator("#auec-comment");
        Locator counter = page.locator("#auec-comment-count");
        assertThat(counter).hasText("0 / 1000");
        comment.fill("Test");
        assertThat(counter).hasText("4 / 1000");
        page.getByTestId("order-auec-submit").click();
        String orderPath = awaitCreatedOrder(page, baseUrl);
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
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-bank-free").check();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-holder-choice").fill(holder);
      writer.locator("li[role='option'][data-value='accountless']").click();
      writer.getByTestId("order-money-amount").fill("1.234,5");
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

      String correctedHolder = "E2E Corrected Holder " + suffix;
      writer.getByTestId("order-money-holder-maintain").click();
      writer.getByTestId("order-money-holder-identity-name").fill(correctedHolder);
      var correction =
          writer.waitForResponse(
              response ->
                  response.url().endsWith("/identity")
                      && "PATCH".equals(response.request().method()),
              () -> writer.getByTestId("order-money-holder-identity-save").click());
      assertEquals(204, correction.status());
      assertThat(writer.getByTestId("order-money-holder-identity-history"))
          .containsText(correctedHolder);
      assertThat(reader.getByTestId("order-money-history-entry")).containsText(correctedHolder);
      assertThat(reader.getByTestId("order-money-amount")).hasValue("78");
      assertThat(writer.getByTestId("order-money-holdings")).containsText("1.234");
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
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-bank-free").check();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-amount").fill("25");
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
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-bank-free").check();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      writer.getByTestId("order-money-holder-choice").fill(memberName);
      writer.locator("li[role='option'][data-value='accountless']").click();
      writer.getByTestId("order-money-amount").fill("2");
      var named =
          writer.waitForResponse(
              response -> response.url().endsWith(orderPath + "/money/provisions"),
              () -> writer.getByTestId("order-money-save").click());
      assertEquals(200, named.status());
      String accountlessId = json(named.text()).get("holderId").getAsString();
      assertNotEquals(holderId, accountlessId);

      writer.getByTestId("order-money-provide").click();
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-bank-free").check();
      writer.getByTestId("order-money-holder-mode").selectOption("existing");
      var existing = writer.getByTestId("order-money-existing-holder");
      assertThat(existing.locator("option[value='" + holderId + "']"))
          .hasText(memberName + " (mit Basetool-Konto)");
      assertThat(existing.locator("option[value='" + accountlessId + "']"))
          .hasText(memberName + " (ohne Basetool-Konto)");
      existing.selectOption(holderId);
      writer.getByTestId("order-money-amount").fill("5");
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
      assertThat(actor.getByTestId("order-money-bank-free")).isChecked();
      assertEquals(2, attempts.get());
      assertEquals("MONEY_PROVISION_REFUSED", json(refused.text()).get("code").getAsString());

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
      assertThat(page.getByTestId("order-money-bank")).isChecked();
      page.getByTestId("order-money-bank-free").check();
      page.getByTestId("order-money-holder-mode").selectOption("new");
      chooseAccountless(page, "order-money-holder-choice", "Second holder");
      page.getByTestId("order-money-amount").fill("6");
      String secondHolder =
          json(awaitPost(page, provisions, "order-money-save")).get("holderId").getAsString();
      assertThat(page.getByTestId("order-money-provided")).containsText("10 aUEC");

      beginMovement(page, "COLLECTION", firstHolder, "4");
      page.getByTestId("order-money-destination-mode").selectOption("new");
      chooseAccountless(page, "order-money-destination-choice", "Collector");
      assertThat(page.getByTestId("order-money-movement-preview")).containsText("Collector");
      String collector =
          json(awaitPost(page, movements, "order-money-movement-save"))
              .get("destinationHolderId")
              .getAsString();
      assertThat(page.locator("[data-money-holder='" + collector + "']")).containsText("4 aUEC");

      beginMovement(page, "COLLECTION", secondHolder, "6");
      page.getByTestId("order-money-destination-mode").selectOption("existing");
      page.getByTestId("order-money-destination-holder").selectOption(collector);
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

      recoverCommittedHandover(page, orderPath, collector, recipientId, "3");
      assertThat(page.getByTestId("order-money-handed-over")).containsText("3 aUEC");
      assertThat(page.getByTestId("order-money-holdings")).containsText("7 aUEC");
      assertThat(page.locator("#order-status-badge.status-active")).isVisible();
      fillHandover(page, collector, recipientId, "7");
      awaitPost(page, movements, "order-money-movement-save");
      assertThat(page.locator("#order-status-badge.status-completed")).isVisible();
      assertThat(page.getByTestId("order-money-provided")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-holdings")).containsText("0 aUEC");
      assertThat(page.getByTestId("order-money-handed-over")).containsText("10 aUEC");
      assertThat(page.getByTestId("order-money-remaining")).containsText("0 aUEC");
      assertThat(page.getByTestId("order-money-history-entry")).hasCount(6);

      page.getByTestId("order-money-recipient-correct").click();
      chooseAccountless(page, "order-money-recipient-identity-party", "Corrected requester");
      awaitPatch(
          page,
          orderPath + "/money/recipients/" + recipientId + "/identity",
          "order-money-recipient-identity-save");
      assertThat(page.getByTestId("order-money-recipient-identity-history"))
          .containsText("Corrected requester");
      assertThat(page.locator("#order-status-badge.status-completed")).isVisible();
      assertThat(page.getByTestId("order-money-handed-over")).containsText("10 aUEC");

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

  /** Hands over from separate holders while an ordinary requester-side peer keeps a draft. */
  @Test
  void contextualWriterCompletesWithoutBankOrCentralCollection() {
    assumeTrue(STACK.managesStack(), "requires the isolated monetary order stack");
    var seeder = new BackendSeeder();
    String tag = UUID.randomUUID().toString().substring(0, 8);
    String unit = seeder.createSquadron(USERNAME, PASSWORD, "Bank-free orders " + tag, "BF" + tag);
    seeder.moveOrgUnitToProfit(USERNAME, PASSWORD, unit);
    String writerId = seeder.getUserId(ORDER_WRITER, ORDER_WRITER_PASSWORD);
    JsonObject recipientAccount =
        json(seeder.getBody(ORDER_READER, ORDER_READER_PASSWORD, "/api/v1/users/me"));
    String readerId = recipientAccount.get("id").getAsString();
    seeder.assignStaffelMembership(USERNAME, PASSWORD, writerId, unit, true, false);
    seeder.assignStaffelMembership(USERNAME, PASSWORD, readerId, unit, false, false);
    assertNoBankStaffAuthority(seeder, ORDER_WRITER, ORDER_WRITER_PASSWORD);
    assertNoBankStaffAuthority(seeder, ORDER_READER, ORDER_READER_PASSWORD);

    String baseUrl = STACK.baseUrl();
    var options = new Browser.NewContextOptions().setIgnoreHTTPSErrors(true);
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext writerContext = browser.newContext(options);
        BrowserContext readerContext = browser.newContext(options)) {
      Page writer = writerContext.newPage();
      Page reader = readerContext.newPage();
      E2eSupport.login(writer, baseUrl, ORDER_WRITER, ORDER_WRITER_PASSWORD);
      E2eSupport.navigate(writer, baseUrl + "/orders/create?lang=de");
      String orderPath = createOrder(writer, baseUrl, unit, unit, "10");
      String orderId = orderPath.substring("/orders/".length());
      String provisions = orderPath + "/money/provisions";
      String movements = orderPath + "/money/movements";

      E2eSupport.login(reader, baseUrl, ORDER_READER, ORDER_READER_PASSWORD);
      E2eSupport.navigate(reader, baseUrl + orderPath + "?lang=de");
      waitForRoom(reader, "order:" + orderId);
      reader.getByTestId("order-money-provide").click();
      Locator draft = reader.getByTestId("order-money-amount");
      draft.fill("78");

      writer.getByTestId("order-money-provide").click();
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-amount").fill("4");
      assertThat(writer.getByTestId("order-money-save")).isDisabled();
      writer.getByTestId("order-money-bank-free").check();
      String firstHolder =
          json(awaitPost(writer, provisions, "order-money-save")).get("holderId").getAsString();
      writer.getByTestId("order-money-provide").click();
      assertThat(writer.getByTestId("order-money-bank")).isChecked();
      writer.getByTestId("order-money-bank-free").check();
      writer.getByTestId("order-money-holder-mode").selectOption("new");
      chooseAccountless(writer, "order-money-holder-choice", "Accountless " + tag);
      writer.getByTestId("order-money-amount").fill("6");
      String secondHolder =
          json(awaitPost(writer, provisions, "order-money-save")).get("holderId").getAsString();
      assertNotEquals(firstHolder, secondHolder);
      assertThat(reader.getByTestId("order-money-designate-recipient")).hasCount(0);
      assertMoneyFigure(reader, "order-money-provided", "10");
      assertThat(draft).hasValue("78");

      writer.getByTestId("order-money-designate-recipient").click();
      writer.getByTestId("order-money-recipient-mode").selectOption("new");
      writer
          .getByTestId("order-money-recipient-choice")
          .fill(recipientAccount.get("effectiveName").getAsString());
      writer.locator("li[role='option'][data-value='" + readerId + "']:visible").click();
      awaitPost(writer, orderPath + "/money/recipients", "order-money-recipient-save");
      String recipient =
          writer.locator("[data-money-recipient]").getAttribute("data-money-recipient");
      assertNotNull(recipient);

      String recoveredOperation =
          recoverCommittedHandover(writer, orderPath, firstHolder, recipient, "4");
      assertMoneyFigure(reader, "order-money-handed-over", "4");
      assertMoneyFigure(reader, "order-money-remaining", "6");
      assertThat(draft).hasValue("78");
      reader.waitForCondition(
          () -> movementNotices(seeder, ORDER_READER, ORDER_READER_PASSWORD, orderId) > 0);
      assertEquals(1, movementNotices(seeder, ORDER_READER, ORDER_READER_PASSWORD, orderId));
      assertEquals(0, movementNotices(seeder, ORDER_WRITER, ORDER_WRITER_PASSWORD, orderId));
      assertOneMovementAudit(seeder, writerId, orderId, recoveredOperation);

      fillHandover(writer, secondHolder, recipient, "6");
      awaitPost(writer, movements, "order-money-movement-save");
      assertThat(writer.locator("#order-status-badge.status-completed")).isVisible();
      assertThat(reader.locator("#order-status-badge.status-completed")).hasText("Abgeschlossen");
      assertThat(draft).isVisible();
      assertThat(draft).hasValue("78");
      assertMoneyFigure(reader, "order-money-provided", "10");
      assertMoneyFigure(reader, "order-money-handed-over", "10");
      assertMoneyFigure(reader, "order-money-holdings", "0");
      assertMoneyFigure(reader, "order-money-remaining", "0");
      assertThat(reader.getByTestId("order-money-history-entry")).hasCount(4);
    }
  }

  private static void assertMoneyFigure(Page page, String testId, String amount) {
    assertThat(page.getByTestId(testId).locator(".kpi-value")).hasText(amount + " aUEC");
  }

  private static long movementNotices(
      BackendSeeder seeder, String user, String password, String orderId) {
    return json(seeder.getBody(user, password, "/api/v1/notifications"))
        .getAsJsonArray("content")
        .asList()
        .stream()
        .map(entry -> entry.getAsJsonObject())
        .filter(entry -> "JOB_ORDER_MONEY_MOVED".equals(entry.get("type").getAsString()))
        .filter(entry -> orderId.equals(entry.get("entityId").getAsString()))
        .count();
  }

  private static void assertNoBankStaffAuthority(
      BackendSeeder seeder, String user, String password) {
    JsonObject capabilities = json(seeder.getBody(user, password, "/api/v1/me/capabilities"));
    assertFalse(capabilities.get("isAdmin").getAsBoolean());
    assertFalse(capabilities.get("canViewBankStaff").getAsBoolean());
    assertFalse(capabilities.get("canManageBank").getAsBoolean());
  }

  private static void assertOneMovementAudit(
      BackendSeeder seeder, String actorId, String orderId, String operationId) {
    String path = "/api/v1/audit/JOB_ORDER?eventType=JOB_ORDER_MONEY_MOVED&actorUserId=" + actorId;
    JsonObject audit = json(seeder.getBody(USERNAME, PASSWORD, path));
    assertEquals(1, audit.get("totalElements").getAsInt());
    JsonObject movement = audit.getAsJsonArray("content").get(0).getAsJsonObject();
    assertEquals(orderId, movement.get("subjectId").getAsString());
    assertTrue(movement.get("details").getAsString().contains(operationId));
  }

  private static String recoverCommittedHandover(
      Page page, String orderPath, String holder, String recipient, String amount) {
    String movements = orderPath + "/money/movements";
    AtomicReference<String> accepted = new AtomicReference<>();
    page.route(
        "**" + movements,
        route -> {
          assertFalse(json(route.request().postData()).get("bankHandling").getAsBoolean());
          var response = route.fetch();
          assertEquals(200, response.status());
          accepted.set(json(response.text()).get("operationId").getAsString());
          route.abort();
        });
    prepareHandover(page, holder, recipient, amount);
    page.getByTestId("order-money-movement-save").click();
    assertThat(page.getByTestId("order-money-unknown")).isVisible();
    assertNotNull(accepted.get());
    String outcome = orderPath + "/money/operations/" + accepted.get();
    AtomicReference<Route> pendingOutcome = new AtomicReference<>();
    AtomicInteger outcomeLookups = new AtomicInteger();
    page.route(
        "**" + outcome,
        route -> {
          outcomeLookups.incrementAndGet();
          pendingOutcome.set(route);
        });
    page.reload();
    page.waitForCondition(() -> pendingOutcome.get() != null);
    assertThat(page.locator("[data-money-check]")).isDisabled();
    page.getByTestId("order-money-move").click();
    assertThat(page.getByTestId("order-money-movement-kind")).isHidden();
    var recovered =
        page.waitForResponse(
            response -> response.url().endsWith(outcome), pendingOutcome.get()::resume);
    assertEquals(200, recovered.status());
    JsonObject recorded = json(recovered.text());
    assertEquals("HANDOVER", recorded.get("kind").getAsString());
    assertEquals(accepted.get(), recorded.get("operationId").getAsString());
    assertEquals(amount, recorded.get("amount").getAsString());
    assertThat(page.getByTestId("order-money-movement-kind")).isVisible();
    assertEquals(1, outcomeLookups.get());
    page.unroute("**" + movements);
    page.unroute("**" + outcome);
    return accepted.get();
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
    if (!"REASSIGNMENT".equals(kind)) {
      assertThat(page.getByTestId("order-money-movement-bank")).isChecked();
      page.getByTestId("order-money-movement-bank-free").check();
    }
    fillMovement(page, kind, source, amount);
  }

  private static void fillMovement(Page page, String kind, String source, String amount) {
    page.getByTestId("order-money-movement-kind").selectOption(kind);
    page.getByTestId("order-money-source-holder").selectOption(source);
    page.getByTestId("order-money-movement-amount").fill(amount);
  }

  private static void prepareHandover(Page page, String source, String recipient, String amount) {
    page.getByTestId("order-money-move").click();
    fillHandover(page, source, recipient, amount);
  }

  private static void fillHandover(Page page, String source, String recipient, String amount) {
    assertThat(page.getByTestId("order-money-movement-bank")).isChecked();
    page.getByTestId("order-money-movement-bank-free").check();
    fillMovement(page, "HANDOVER", source, amount);
    page.getByTestId("order-money-handover-recipient").selectOption(recipient);
  }

  private static void chooseAccountless(Page page, String field, String name) {
    page.getByTestId(field).fill(name);
    page.locator("li[role='option'][data-value='accountless']:visible").click();
  }

  /** Credits an explicit contribution and updates a peer without replacing its open draft. */
  @Test
  void creditsAUnitCommitmentWhilePreservingAnotherEditorsDraft() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    var seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    String otherUnit =
        seeder.createSquadron(USERNAME, PASSWORD, "Commitment witness " + tag, "C" + tag);
    seeder.moveOrgUnitToProfit(USERNAME, PASSWORD, otherUnit);
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

      actor.getByTestId("order-money-commitment-add").click();
      actor.getByTestId("order-money-commitment-unit").selectOption(IRIDIUM);
      actor.getByTestId("order-money-commitment-amount").fill("6000000");
      actor.getByTestId("order-money-commitment-save").click();
      assertThat(actor.getByTestId("order-money-commitment-outstanding")).containsText("6.000.000");

      E2eSupport.login(peer, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(peer, baseUrl + orderPath + "?lang=de");
      waitForRoom(peer, "order:" + orderPath.substring("/orders/".length()));
      peer.getByTestId("order-money-commitment-edit").click();
      Locator draft = peer.getByTestId("order-money-commitment-amount");
      draft.fill("5000000");

      prepareSelfProvision(actor, "4000000");
      String commitmentId =
          actor.getByTestId("order-money-commitment").getAttribute("data-commitment");
      assertNotNull(commitmentId);
      actor.getByTestId("order-money-provision-commitment").selectOption(commitmentId);
      actor.getByTestId("order-money-save").click();

      assertThat(actor.getByTestId("order-money-provided")).containsText("4.000.000");
      assertThat(actor.getByTestId("order-money-commitment-outstanding")).containsText("2.000.000");
      Locator history = actor.getByTestId("order-money-commitment").locator("details");
      history.locator("summary").click();
      var source = actor.getByTestId("order-money-commitment-operation");
      assertThat(source).isVisible();
      var previousHistory = history.elementHandle();
      actor.evaluate(
          """
          url => window.krtFetch.swap({
            url,
            container: '#order-participations-results',
            fragmentValue: 'participations',
            history: false
          })
          """,
          orderPath);
      actor.waitForFunction("previous => !previous.isConnected", previousHistory);
      assertThat(source).isVisible();
      String operationAnchor = source.getAttribute("href");
      assertNotNull(operationAnchor);
      assertThat(actor.locator(operationAnchor)).containsText("4.000.000");
      assertThat(peer.locator("[data-commitment-changed]")).isVisible();
      assertThat(peer.getByTestId("order-money-commitment-outstanding")).containsText("2.000.000");
      assertThat(peer.getByTestId("order-money-commitment-save")).isDisabled();
      assertEquals("5.000.000", shownAmount(draft));

      peer.getByTestId("order-money-commitment-unit").selectOption(otherUnit);
      assertThat(draft).hasValue("");
      assertThat(peer.locator("[data-commitment-changed]")).isHidden();
    }
  }

  /** Refreshes both order views and preserves a correction draft through a peer-only change. */
  @Test
  void reassignsHeldMoneyAndRefreshesTheOtherOrder() {
    assumeTrue(STACK.managesStack(), "requires the isolated managed order stack");
    var seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page source = context.newPage();
      Page destination = context.newPage();
      E2eSupport.login(source, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(source, baseUrl + "/orders/create?lang=de");
      String sourcePath = createOrder(source, baseUrl, IRIDIUM, "10000000");
      E2eSupport.navigate(destination, baseUrl + "/orders/create?lang=de");
      String destinationPath = createOrder(destination, baseUrl, IRIDIUM, "10000000");
      String destinationNumber =
          json(seeder.getBody(USERNAME, PASSWORD, "/api/v1" + destinationPath))
              .get("displayId")
              .getAsString();
      waitForRoom(destination, "order:" + destinationPath.substring("/orders/".length()));

      prepareSelfProvision(source, "10000000");
      String holder =
          json(awaitPost(source, sourcePath + "/money/provisions", "order-money-save"))
              .get("holderId")
              .getAsString();
      assertThat(source.getByTestId("order-money-holdings")).containsText("10.000.000");

      beginMovement(source, "REASSIGNMENT", holder, "8000000");
      String reassignment = sourcePath + "/money/movements/reassignment";
      var preview =
          source.waitForResponse(
              response -> response.url().endsWith(reassignment + "/preview"),
              () -> source.getByTestId("order-money-destination-number").fill(destinationNumber));
      assertTrue(preview.ok(), () -> preview.status() + " " + preview.text());
      JsonObject terms = json(preview.text());
      assertEquals("2000000", terms.get("sourceAfter").getAsString());
      assertEquals("8000000", terms.get("destinationAfter").getAsString());
      assertEquals("0", terms.get("fee").getAsString());
      assertEquals("0", terms.get("provision").getAsString());
      waitForRoom(source, "order:" + destinationPath.substring("/orders/".length()));

      prepareSelfProvision(destination, "1");
      awaitPost(destination, destinationPath + "/money/provisions", "order-money-save");
      assertThat(
              source
                  .getByTestId("order-money-movement-preview")
                  .locator("[data-preview-value='destinationAfter']"))
          .hasText("8.000.001");
      assertEquals("8.000.000", shownAmount(source.getByTestId("order-money-movement-amount")));
      assertThat(source.locator("#order-money-movement-form [data-money-basis-changed]"))
          .isVisible();

      awaitPost(source, reassignment, "order-money-movement-save");
      assertThat(source.getByTestId("order-money-holdings")).containsText("2.000.000");
      assertThat(source.getByTestId("order-money-provided")).containsText("10.000.000");
      assertThat(destination.getByTestId("order-money-holdings")).containsText("8.000.001");
      assertThat(destination.getByTestId("order-money-provided")).containsText("1 aUEC");
      assertThat(destination.getByTestId("order-money-history-entry")).hasCount(2);
      assertThat(destination.locator("#order-status-badge.status-active")).isVisible();

      source.locator("[data-original-kind='PURPOSE_OUTFLOW']").click();
      Locator correction = source.locator("#order-money-correction-modal");
      Locator revised = correction.locator("input[name='amount']");
      revised.fill("7000000");
      Locator peerHoldings = correction.locator("[data-preview-value='linkedHoldings']");
      assertThat(peerHoldings).hasText("7.000.001");
      waitForRoom(source, "order:" + destinationPath.substring("/orders/".length()));

      prepareSelfProvision(destination, "1");
      awaitPost(destination, destinationPath + "/money/provisions", "order-money-save");
      assertThat(peerHoldings).hasText("7.000.002");
      assertThat(correction.locator("[data-money-correction-basis]")).isVisible();
      assertEquals("7.000.000", shownAmount(revised));
      assertThat(source.getByTestId("order-money-history-entry")).hasCount(2);
      assertThat(source.getByTestId("order-money-holdings")).containsText("2.000.000");

      awaitPost(source, sourcePath + "/money/corrections", "order-money-correction-save");
      assertThat(source.getByTestId("order-money-holdings")).containsText("3.000.000");
      assertThat(destination.getByTestId("order-money-holdings")).containsText("7.000.002");
      assertThat(destination.getByTestId("order-money-provided")).containsText("2 aUEC");
      assertThat(source.getByTestId("order-money-history-entry")).hasCount(3);
      assertThat(destination.getByTestId("order-money-history-entry")).hasCount(4);
    }
  }

  /** Corrects a provision and its dependent collection through one history action. */
  @Test
  void correctsConnectedAmountsWithoutRewritingTheOriginalHistory() {
    assumeTrue(STACK.managesStack(), "requires the isolated monetary order stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    String baseUrl = STACK.baseUrl();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, true);
        BrowserContext context =
            browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
      E2eSupport.navigate(page, baseUrl + "/orders/create?lang=de");
      String path = createOrder(page, baseUrl, IRIDIUM, "10");

      prepareSelfProvision(page, "10");
      String holder =
          json(awaitPost(page, path + "/money/provisions", "order-money-save"))
              .get("holderId")
              .getAsString();
      beginMovement(page, "COLLECTION", holder, "9");
      page.getByTestId("order-money-destination-mode").selectOption("new");
      chooseAccountless(page, "order-money-destination-choice", "Correction collector");
      String collector =
          json(awaitPost(page, path + "/money/movements", "order-money-movement-save"))
              .get("destinationHolderId")
              .getAsString();

      page.getByTestId("order-money-correct").first().click();
      Locator dialog = page.locator("#order-money-correction-modal");
      Locator amounts = dialog.locator("input[name='amount']");
      amounts.first().fill("8");
      assertThat(dialog.locator("[data-money-preview-error]")).isVisible();
      assertThat(page.getByTestId("order-money-correction-save")).isDisabled();

      dialog.locator("[data-money-correction-add]").click();
      amounts.nth(1).fill("7");
      assertThat(dialog.locator("[data-preview-value='holdings']")).containsText("8");
      awaitPost(page, path + "/money/corrections", "order-money-correction-save");

      assertThat(dialog).isHidden();
      assertThat(page.getByTestId("order-money-provided")).containsText("8 aUEC");
      assertThat(page.locator("[data-money-holder='" + holder + "']")).containsText("1 aUEC");
      assertThat(page.locator("[data-money-holder='" + collector + "']")).containsText("7 aUEC");
      Locator history = page.getByTestId("order-money-history-entry");
      assertThat(history).hasCount(3);
      assertThat(history.first()).containsText("10 aUEC");
      assertThat(history.nth(1)).containsText("9 aUEC");
      assertThat(history.last()).containsText("10 auf 8 aUEC");
    }
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
    return createOrder(page, baseUrl, IRIDIUM, requestingUnit, target);
  }

  static String createOrder(
      Page page, String baseUrl, String responsibleUnit, String requestingUnit, String target) {
    fillCreateForm(page, responsibleUnit, requestingUnit, target);
    page.getByTestId("order-auec-submit").click();
    return awaitCreatedOrder(page, baseUrl);
  }

  private static void fillCreateForm(
      Page page, String responsibleUnit, String requestingUnit, String target) {
    page.getByTestId("order-mode-auec").click();
    page.locator("#auec-responsible-org-unit").selectOption(responsibleUnit);
    page.locator("#auec-requesting-org-unit").selectOption(requestingUnit);
    page.getByTestId("order-auec-handle").fill("E2E Money " + UUID.randomUUID());
    page.getByTestId("order-auec-target").fill(target);
  }

  private static String awaitCreatedOrder(Page page, String baseUrl) {
    page.waitForURL(url ->
        url.startsWith(baseUrl + "/orders/")
            && URI.create(url).getPath().matches("/orders/[0-9a-f-]{36}"));
    return URI.create(page.url()).getPath();
  }

  private static JsonObject json(String body) {
    return JsonParser.parseString(body).getAsJsonObject();
  }
}
