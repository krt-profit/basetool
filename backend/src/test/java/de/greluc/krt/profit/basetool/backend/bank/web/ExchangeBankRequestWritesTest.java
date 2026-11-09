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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Raising, correcting and cancelling the member's own booking requests through the exchange
 * (REQ-BXCH-004): the web's rules through the bank policy, the terse answer, the origin column, the
 * shared {@code 404}, the bank's {@code 409} codes and the open-request cap, against a seeded
 * {@link BankAccessWorld}.
 */
@SpringBootTest
@Transactional
class ExchangeBankRequestWritesTest {

  private static final Set<String> TERSE = Set.of("id", "status", "version", "amount");

  private static final String REQUESTS = "/requests";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private MeterRegistry meterRegistry;

  private BankAccessWorld world;
  private BankExchangeRelay relay;

  @BeforeEach
  void setUp() {
    world = new BankAccessSeeder(context).seed();
    relay = new BankExchangeRelay(context);
  }

  @Test
  void raisesADepositOnAnAccountTheMemberDoesNotViewWithTheTerseAnswer() throws Exception {
    MockHttpServletResponse response =
        raise(Actor.STAFFELLEITER, deposit(world.account(Account.FOREIGN_ORG_UNIT), 500));

    JsonNode created = body(response, 201);
    assertThat(new TreeSet<>(created.propertyNames())).isEqualTo(new TreeSet<>(TERSE));
    assertThat(created.path("status").asString()).isEqualTo("PENDING");
    assertThat(created.path("amount").isIntegralNumber()).isTrue();
    assertThat(created.path("amount").asLong()).isEqualTo(500);
    assertThat(created.path("version").isIntegralNumber()).isTrue();
  }

  @Test
  void stampsTheClientOnTheRequestAndAnswersItInTheOwnRequests() throws Exception {
    String id =
        body(raise(Actor.STAFFELLEITER, deposit(world.account(Account.OWN_ORG_UNIT), 500)), 201)
            .path("id")
            .asString();
    entityManager.flush();

    JsonNode own = ownRequest(Actor.STAFFELLEITER, id);

    assertThat(own.path("client").asString()).isEqualTo(relay.client());
    assertThat(
            jdbc.queryForObject(
                "SELECT client_id FROM bank_booking_request WHERE id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo(relay.client());
  }

  @Test
  void routesAWithdrawalThroughThePolicyAsTheWebDoes() throws Exception {
    String id =
        body(
                raise(
                    Actor.STAFFELLEITER,
                    debit("WITHDRAWAL", world.account(Account.CARTEL), 5000, null)),
                201)
            .path("id")
            .asString();
    entityManager.flush();

    JsonNode own = ownRequest(Actor.STAFFELLEITER, id);

    assertThat(own.path("requiresOwnerApproval").asBoolean()).isTrue();
    assertThat(own.path("requiredApprover").asString()).isEqualTo("BANK_MANAGEMENT");
  }

  @Test
  void aHiddenAClosedAndAnUnknownAccountAnswerTheSameCountedNotFound() throws Exception {
    double before = refused("not_found");

    assertNotFound(
        raise(
            Actor.STAFFELLEITER,
            debit("WITHDRAWAL", world.account(Account.SK_ORG_UNIT), 500, null)));
    assertNotFound(raise(Actor.STAFFELLEITER, deposit(world.account(Account.CLOSED), 500)));
    assertNotFound(raise(Actor.STAFFELLEITER, deposit(UUID.randomUUID(), 500)));
    assertNotFound(
        raise(
            Actor.STAFFELLEITER,
            debit(
                "TRANSFER",
                world.account(Account.OWN_ORG_UNIT),
                500,
                world.account(Account.CLOSED))));

    assertThat(refused("not_found")).isEqualTo(before + 4);
  }

  @Test
  void keepsTheBanksOwnRefusals() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    Map<String, Object> selfTransfer = debit("TRANSFER", own, 500, own);
    Map<String, Object> unjustified = debit("WITHDRAWAL", world.account(Account.CARTEL), 500, null);
    unjustified.remove("justification");

    assertCode(raise(Actor.STAFFELLEITER, selfTransfer), 409, "BANK_SELF_TRANSFER");
    assertCode(raise(Actor.STAFFELLEITER, unjustified), 409, "BANK_JUSTIFICATION_REQUIRED");
    assertCode(raise(Actor.STAFFELLEITER, debit("TRANSFER", own, 500, null)), 400, "BAD_REQUEST");
  }

  @Test
  void validatesTheBodyByTheWebsRules() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    Map<String, Object> splitWithdrawal = debit("WITHDRAWAL", own, 500, null);
    splitWithdrawal.put("splitEnabled", true);
    splitWithdrawal.put("splitPercent", 10);

    assertCode(raise(Actor.STAFFELLEITER, deposit(own, 0)), 400, "CONSTRAINT_VIOLATION");
    assertCode(raise(Actor.STAFFELLEITER, splitWithdrawal), 400, "CONSTRAINT_VIOLATION");
  }

  @Test
  void refusesTheRequestOverTheOpenRequestCapAndCountsIt() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    long pending =
        jdbc.queryForObject(
            "SELECT count(*) FROM bank_booking_request WHERE requested_by = ? AND status ="
                + " 'PENDING'",
            Long.class,
            world.user(Actor.STAFFELLEITER));
    for (long held = pending; held < 20; held++) {
      body(raise(Actor.STAFFELLEITER, deposit(own, 500)), 201);
    }
    double before = refused("open_request_cap");

    assertCode(raise(Actor.STAFFELLEITER, deposit(own, 500)), 409, "BANK_OPEN_REQUEST_CAP");
    assertThat(refused("open_request_cap")).isEqualTo(before + 1);
  }

  @Test
  void correctsAnOwnPendingRequestAndReRoutesIt() throws Exception {
    UUID id = world.request(Request.CORRECT_OWN);
    long before = version(id);

    JsonNode corrected = body(correct(Actor.MEMBER, id, 5000, before), 200);

    assertThat(new TreeSet<>(corrected.propertyNames())).isEqualTo(new TreeSet<>(TERSE));
    assertThat(corrected.path("amount").asLong()).isEqualTo(5000);
    assertThat(corrected.path("version").asLong()).isEqualTo(before + 1);
    entityManager.flush();
    JsonNode own = ownRequest(Actor.MEMBER, id.toString());
    assertThat(own.path("amount").asLong()).isEqualTo(5000);
    assertThat(own.path("requiredApprover").asString()).isEqualTo("RESPONSIBLE_HOLDER");
    assertThat(own.path("version").asLong()).isEqualTo(corrected.path("version").asLong());
  }

  @Test
  void aStaleVersionConflictsAndAnApprovedRequestStaysAsItIs() throws Exception {
    UUID id = world.request(Request.CORRECT_OWN);
    long version = version(id);

    assertCode(correct(Actor.MEMBER, id, 600, version + 7), 409, "OPTIMISTIC_LOCK");
    jdbc.update(
        "UPDATE bank_booking_request SET owner_approval_granted = TRUE WHERE id = ?", id);
    entityManager.clear();
    assertCode(correct(Actor.MEMBER, id, 600, version), 409, "BANK_REQUEST_ALREADY_APPROVED");
  }

  @Test
  void cancelsAnOwnPendingRequestOnceOnly() throws Exception {
    UUID id = world.request(Request.CANCEL_OWN);

    JsonNode cancelled = body(cancel(Actor.MEMBER, id, version(id)), 200);

    assertThat(new TreeSet<>(cancelled.propertyNames()))
        .isEqualTo(new TreeSet<>(Set.of("id", "status", "version")));
    assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
    entityManager.flush();
    assertCode(cancel(Actor.MEMBER, id, version(id)), 409, "BANK_REQUEST_NOT_PENDING");
  }

  @Test
  void anotherMembersRequestIsNotFoundForCorrectionAndCancel() throws Exception {
    UUID correctable = world.request(Request.CORRECT_OWN);
    UUID cancellable = world.request(Request.CANCEL_OWN);

    assertNotFound(correct(Actor.STAFFELLEITER, correctable, 600, version(correctable)));
    assertNotFound(cancel(Actor.STAFFELLEITER, cancellable, version(cancellable)));
  }

  @Test
  void countsEveryWriteOnTheWriteCounter() throws Exception {
    double before = writes("applied");

    body(raise(Actor.STAFFELLEITER, deposit(world.account(Account.OWN_ORG_UNIT), 500)), 201);

    assertThat(writes("applied")).isEqualTo(before + 1);
  }

  /**
   * A deposit body.
   *
   * @param account the account
   * @param amount the amount
   * @return the body
   */
  @NotNull
  private static Map<String, Object> deposit(@NotNull UUID account, long amount) {
    Map<String, Object> body = new HashMap<>();
    body.put("type", "DEPOSIT");
    body.put("sourceAccountId", account);
    body.put("amount", amount);
    body.put("note", "Missionsertrag");
    return body;
  }

  /**
   * A withdrawal or transfer body with the seeded justification.
   *
   * @param type {@code WITHDRAWAL} or {@code TRANSFER}
   * @param account the source account
   * @param amount the amount
   * @param target the destination, or {@code null}
   * @return the body
   */
  @NotNull
  private static Map<String, Object> debit(
      @NotNull String type, @NotNull UUID account, long amount, @Nullable UUID target) {
    Map<String, Object> body = new HashMap<>();
    body.put("type", type);
    body.put("sourceAccountId", account);
    body.put("amount", amount);
    body.put("justification", BankAccessSeeder.JUSTIFICATION);
    if (target != null) {
      body.put("targetAccountId", target);
    }
    return body;
  }

  /**
   * Raises a request as an actor.
   *
   * @param actor the actor
   * @param body the body
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse raise(@NotNull Actor actor, @NotNull Map<String, Object> body)
      throws Exception {
    return relay.write(HttpMethod.POST, world.user(actor), REQUESTS, body);
  }

  /**
   * Corrects a request's amount as an actor, keeping the seeded justification.
   *
   * @param actor the actor
   * @param id the request
   * @param amount the corrected amount
   * @param version the echoed version
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse correct(
      @NotNull Actor actor, @NotNull UUID id, long amount, long version) throws Exception {
    return relay.write(
        HttpMethod.PUT,
        world.user(actor),
        REQUESTS + "/" + id,
        Map.of(
            "amount", amount, "justification", BankAccessSeeder.JUSTIFICATION, "version", version));
  }

  /**
   * Cancels a request as an actor.
   *
   * @param actor the actor
   * @param id the request
   * @param version the echoed version
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse cancel(@NotNull Actor actor, @NotNull UUID id, long version)
      throws Exception {
    return relay.write(
        HttpMethod.POST, world.user(actor), REQUESTS + "/" + id + "/cancel", Map.of("version", version));
  }

  /**
   * The stored version of a request.
   *
   * @param id the request
   * @return its version
   */
  private long version(@NotNull UUID id) {
    entityManager.flush();
    Long version =
        jdbc.queryForObject(
            "SELECT version FROM bank_booking_request WHERE id = ?", Long.class, id);
    return version == null ? 0 : version;
  }

  /**
   * One request of the actor's own-request snapshot.
   *
   * @param actor the actor
   * @param id the request
   * @return its item
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private JsonNode ownRequest(@NotNull Actor actor, @NotNull String id) throws Exception {
    JsonNode page = body(relay.read(world.user(actor), REQUESTS, Map.of("limit", "1000")), 200);
    for (JsonNode item : page.path("items")) {
      if (id.equals(item.path("id").asString())) {
        return item;
      }
    }
    throw new AssertionError(id + " is not among the own requests");
  }

  /**
   * Asserts a status and parses the body.
   *
   * @param response the response
   * @param status the expected status
   * @return the body
   * @throws Exception when the body is no JSON
   */
  @NotNull
  private JsonNode body(@NotNull MockHttpServletResponse response, int status) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return relay.body(response);
  }

  /**
   * Asserts a refusal's status and code.
   *
   * @param response the response
   * @param status the expected status
   * @param code the expected code
   * @throws Exception when the body is no JSON
   */
  private void assertCode(@NotNull MockHttpServletResponse response, int status, @NotNull String code)
      throws Exception {
    assertThat(body(response, status).path("code").asString()).isEqualTo(code);
  }

  /**
   * Asserts the shared {@code 404 NOT_FOUND}.
   *
   * @param response the response
   * @throws Exception when the body is no JSON
   */
  private void assertNotFound(@NotNull MockHttpServletResponse response) throws Exception {
    assertCode(response, 404, "NOT_FOUND");
  }

  /**
   * The bank refusals of one reason.
   *
   * @param reason the reason label
   * @return the counter's count
   */
  private double refused(@NotNull String reason) {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_REFUSED)
            .tag(MetricNames.TAG_REASON, reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * The bank request writes of the relay's client with one outcome.
   *
   * @param outcome the outcome label
   * @return the counter's count
   */
  private double writes(@NotNull String outcome) {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_WRITES)
            .tag(MetricNames.TAG_CLIENT_ID, relay.client())
            .tag(ExchangeMetricNames.TAG_RESOURCE, "bank_request")
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
