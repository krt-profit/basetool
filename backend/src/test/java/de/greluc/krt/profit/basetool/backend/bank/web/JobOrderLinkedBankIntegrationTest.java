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
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Pin;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises coupled JobOrder and Bank outcomes through HTTP and PostgreSQL. */
@SpringBootTest
@Transactional
class JobOrderLinkedBankIntegrationTest {
  private static final String MONEY = "/api/v1/orders/{id}/money";
  private static final String OWN_REQUESTS = "/api/v1/bank/org-units/requests";
  private static final String STAFF_REQUESTS = "/api/v1/bank/requests";
  private static final String ACCOUNTS = "/api/v1/bank/accounts";
  private static final String FEE_SETTING = "operation.transfer_fee_rate";

  @Autowired private ConfigurableWebApplicationContext context;
  @Autowired private CustomJwtGrantedAuthoritiesConverter authorities;
  @Autowired private OrgUnitRepository units;
  @Autowired private UserRepository users;
  @Autowired private JsonMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private BankAccessWorld world;
  private MockMvc mvc;
  private UUID profit;
  private UUID responsible;
  private UUID committedOrder;
  private boolean ownsProfit;
  private boolean committedWorld;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      world = new BankAccessSeeder(context).seed();
      profit = units.findActiveProfitRootIds().getFirst();
      responsible = profit;
      return;
    }
    transactions.executeWithoutResult(_ -> {
      ownsProfit = units.findActiveProfitRootIds().isEmpty();
      world = new BankAccessSeeder(context).seedLinkedOrder();
      profit = units.findActiveProfitRootIds().getFirst();
      var processingUnit = new Squadron();
      String tag = UUID.randomUUID().toString().substring(0, 8);
      processingUnit.setName("Linked order processing " + tag);
      processingUnit.setShorthand("L" + tag);
      processingUnit.setParent(units.findById(profit).orElseThrow());
      responsible = units.saveAndFlush(processingUnit).getId();
    });
    committedWorld = true;
  }

  @AfterEach
  void removeCommittedFixture() {
    if (!committedWorld) {
      return;
    }
    transactions.executeWithoutResult(_ -> {
      if (committedOrder != null) {
        jdbc.update("DELETE FROM job_order_money_operation WHERE order_id = ?", committedOrder);
        jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", committedOrder);
        jdbc.update("DELETE FROM job_order WHERE id = ?", committedOrder);
      }
      removeBankRows();
      users.deleteAllById(
          List.of(world.user(Actor.MEMBER), world.user(Actor.MANAGEMENT), world.user(Actor.ADMIN)));
      users.flush();
      jdbc.update("DELETE FROM org_unit WHERE id = ?", world.pinned(Pin.OWN_STAFFEL));
      jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", responsible);
      jdbc.update("DELETE FROM org_unit WHERE id = ?", responsible);
      if (ownsProfit) {
        jdbc.update("DELETE FROM org_unit WHERE id = ?", profit);
      }
    });
  }

  private void removeBankRows() {
    UUID account = world.account(Account.OWN_ORG_UNIT);
    List<UUID> attempts =
        jdbc.queryForList(
            """
            SELECT linked_attempt_id FROM bank_booking_request
            WHERE account_id = ? AND linked_attempt_id IS NOT NULL
            """,
            UUID.class,
            account);
    jdbc.update("DELETE FROM bank_booking_request WHERE account_id = ?", account);
    for (UUID attempt : attempts) {
      jdbc.update("DELETE FROM bank_authorisation WHERE id = ?", attempt);
      jdbc.update("DELETE FROM bank_linked_attempt WHERE id = ?", attempt);
    }
    List<UUID> postings =
        jdbc.queryForList(
            "SELECT DISTINCT transaction_id FROM bank_posting WHERE account_id = ?",
            UUID.class,
            account);
    for (UUID posting : postings) {
      jdbc.update("DELETE FROM bank_holder_posting WHERE transaction_id = ?", posting);
      jdbc.update("DELETE FROM bank_posting WHERE transaction_id = ?", posting);
      jdbc.update("DELETE FROM bank_transaction WHERE id = ?", posting);
    }
    jdbc.update("DELETE FROM bank_audit_event WHERE account_id = ?", account);
    jdbc.update("DELETE FROM bank_holder WHERE id = ?", world.holder(Holder.MANAGEMENT_HOLDER));
    jdbc.update("DELETE FROM bank_account WHERE id = ?", account);
  }

  @Test
  void pendingAuthorisationCreditsProvisionOnceWithoutBankPosting() throws Exception {
    UUID order = createOrder("1000000");
    UUID operation = UUID.randomUUID();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    long bookings = accountBookingCount(account);
    Map<String, ?> terms = authorisation(operation, account, custodian, "600000");

    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("0"))
        .andExpect(jsonPath("$.holdings").value("0"));
    JsonNode request = onlyOwnRequest();
    assertThat(request.path("type").asString()).isEqualTo("AUTHORISATION");
    assertThat(request.path("status").asString()).isEqualTo("PENDING");

    confirm(request, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.resultingTransactionId").doesNotExist())
        .andExpect(jsonPath("$.linked.authorisationId").isNotEmpty());

    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("AUTHORISED"))
        .andExpect(jsonPath("$.amount").value("600000"))
        .andExpect(jsonPath("$.staffNote").doesNotExist());
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("AUTHORISED"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.holdings").value("600000"));
    assertThat(onlyOwnRequest().path("id").asString()).isEqualTo(request.path("id").asString());
    assertThat(accountBookingCount(account)).isEqualTo(bookings);
  }

  @Test
  void accountCollectionAndRequesterTransferNeverNeedAnOrderPlayerHolder() throws Exception {
    UUID order = createOrder("800000");
    UUID collection = world.account(Account.OWN_ORG_UNIT);
    UUID destination = world.account(Account.SPECIAL_SHARED);
    assertThat(accountBalance(collection)).isEqualTo(1000000);
    assertThat(accountBalance(destination)).isEqualTo(1000000);
    reportDepositToAccount(order, collection, "400000", "First contributor");
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("0"))
        .andExpect(jsonPath("$.holders").isEmpty());
    confirmDepositToAccount("First contributor", world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER));
    reportDepositToAccount(order, collection, "300000", "Second contributor");
    confirmDepositToAccount("Second contributor", world.holder(Holder.MANAGEMENT_HOLDER));
    assertThat(accountBalance(collection)).isEqualTo(1700000);

    long bookingsBeforeAuthorisation = accountBookingCount(collection, Actor.MANAGEMENT);
    UUID authorisation = UUID.randomUUID();
    provide(
            order,
            Map.of(
                "operationId",
                authorisation,
                "amount",
                "100000",
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type",
                    "AUTHORISATION",
                    "accountOnly",
                    true,
                    "accountId",
                    collection,
                    "justification",
                    "Attribute existing funds to this order")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("700000"));
    confirm(ownRequestOfType("AUTHORISATION"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resultingTransactionId").doesNotExist())
        .andExpect(jsonPath("$.linked.authorisationId").isNotEmpty());
    assertThat(accountBookingCount(collection, Actor.MANAGEMENT))
        .isEqualTo(bookingsBeforeAuthorisation);
    assertThat(accountBalance(collection)).isEqualTo(1700000);
    JsonNode money = response(money(order).andExpect(status().isOk()));
    assertThat(money.path("provided").asString()).isEqualTo("800000");
    assertThat(money.path("holders").size()).isZero();
    assertThat(money.path("accountHoldings").size()).isOne();
    JsonNode holding = money.path("accountHoldings").path(0);
    assertThat(holding.path("bankAccountId").asString()).isEqualTo(collection.toString());
    assertThat(holding.path("amount").asString()).isEqualTo("800000");
    assertThat(holding.path("availableAmount").asString()).isEqualTo("800000");

    JsonNode recipient =
        response(
            perform(
                    post(MONEY + "/recipients", order).with(writer()),
                    Map.of("bankAccountId", destination))
                .andExpect(status().isCreated()));
    UUID transfer = UUID.randomUUID();
    perform(
            post(MONEY + "/movements", order).with(writer()),
            Map.of(
                "operationId",
                transfer,
                "kind",
                "PURPOSE_HANDOVER",
                "sourceAccountHoldingId",
                holding.path("id").asString(),
                "sourceVersion",
                holding.path("version").asLong(),
                "targetVersion",
                money.path("targetVersion").asLong(),
                "recipientId",
                recipient.path("id").asString(),
                "amount",
                "800000",
                "rate",
                "0",
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type",
                    "TRANSFER",
                    "accountOnly",
                    true,
                    "accountId",
                    collection,
                    "targetAccountId",
                    destination,
                    "justification",
                    "Handover of collected funds")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.netHandedOver").value("0"))
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("0"));

    JsonNode posted =
        response(
            confirm(ownRequestOfType("TRANSFER"), Actor.MANAGEMENT, null, null)
                .andExpect(status().isOk()));
    UUID transaction = UUID.fromString(posted.path("resultingTransactionId").asString());
    assertFigures(order, "0", "800000", "0", "COMPLETED");
    assertThat(accountBalance(collection)).isEqualTo(900000);
    assertThat(accountBalance(destination)).isEqualTo(1800000);
    assertAccountOnlyPosting(transaction);

    JsonNode reversed =
        response(
            perform(
                    post("/api/v1/bank/transactions/{id}/reversal", transaction)
                        .with(as(Actor.MANAGEMENT)),
                    Map.of())
                .andExpect(status().isCreated()));
    assertFigures(order, "800000", "0", "0", "IN_PROGRESS");
    outcome(order, transfer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REVERSED"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holders").isEmpty())
        .andExpect(jsonPath("$.accountHoldings[0].id").value(holding.path("id").asString()))
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("800000"));
    assertThat(accountBalance(collection)).isEqualTo(1700000);
    assertThat(accountBalance(destination)).isEqualTo(1000000);
    assertAccountOnlyPosting(UUID.fromString(reversed.path("id").asString()));

    JsonNode export =
        response(
            mvc.perform(
                    get("/api/v1/users/me/export")
                        .with(writer())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()));
    for (String section : List.of("jobOrderMoneyOperations", "jobOrderLinkedMoneyAttempts")) {
      assertThat(exportedMoneyRow(export, section, transfer).toString())
          .doesNotContain(collection.toString(), destination.toString());
    }
  }

  private static JsonNode exportedMoneyRow(JsonNode export, String sectionKey, UUID operation) {
    for (JsonNode section : export.path("sections")) {
      if (sectionKey.equals(section.path("key").asString())) {
        for (JsonNode row : section.path("rows")) {
          if (operation.toString().equals(row.path("operation_id").asString())) {
            return row;
          }
        }
      }
    }
    throw new AssertionError("Missing own operation in " + sectionKey);
  }

  private void reportDepositToAccount(UUID order, UUID account, String amount, String depositor)
      throws Exception {
    provide(
            order,
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "amount",
                amount,
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type",
                    "DEPOSIT",
                    "accountOnly",
                    true,
                    "accountId",
                    account,
                    "counterpartyName",
                    depositor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
  }

  private void confirmDepositToAccount(String depositor, UUID custodian) throws Exception {
    JsonNode request = ownRequestOfType("DEPOSIT");
    assertThat(request.path("counterpartyHandle").asString()).isEqualTo(depositor);
    confirm(request, Actor.MANAGEMENT, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(custodian.toString()));
  }

  private void assertAccountOnlyPosting(UUID transaction) {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bank_posting WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bank_holder_posting WHERE transaction_id = ?",
                Integer.class,
                transaction))
        .isZero();
  }

  @Test
  @Timeout(45)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void staffConfirmationWaitsForOrderBeforeLockingRequestOrAccount() throws Exception {
    UUID order = createOrder("1000000");
    UUID operation = UUID.randomUUID();
    UUID custodian = world.holder(Holder.MANAGEMENT_HOLDER);
    UUID account = world.account(Account.OWN_ORG_UNIT);
    provide(order, deposit(operation, account, custodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = onlyOwnRequest();
    UUID requestId = UUID.fromString(request.path("id").asString());
    JsonNode result =
        confirmBehind(
            request,
            custodian,
            () ->
                jdbc.queryForObject(
                    "SELECT id FROM job_order WHERE id = ? FOR NO KEY UPDATE", UUID.class, order),
            () -> {
              assertThat(
                      jdbc.queryForObject(
                          "SELECT id FROM bank_booking_request WHERE id = ? FOR NO KEY UPDATE"
                              + " NOWAIT",
                          UUID.class,
                          requestId))
                  .isEqualTo(requestId);
              lockUnheldAccount(account);
            },
            status().isOk());
    assertThat(result.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(result.path("resultingTransactionId").asString()).isNotEmpty();
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("POSTED"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.holdings").value("600000"));
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isOne();
  }

  @ParameterizedTest
  @EnumSource(DecisionWinner.class)
  @Timeout(45)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void staffConfirmationRefusesAfterWaitingBehindCancellationOrHierarchyChange(
      DecisionWinner winner) throws Exception {
    UUID order = createOrder("1000000");
    UUID operation = UUID.randomUUID();
    UUID custodian = world.holder(Holder.MANAGEMENT_HOLDER);
    UUID account = world.account(Account.OWN_ORG_UNIT);
    provide(order, deposit(operation, account, custodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = onlyOwnRequest();
    Callable<ResultActions> preceding =
        switch (winner) {
          case CANCELLATION ->
              () ->
                  perform(
                          post(OWN_REQUESTS + "/{request}/cancel", request.path("id").asString())
                              .with(writer()),
                          Map.of("version", request.path("version").asLong()))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.status").value("CANCELLED"));
          case HIERARCHY ->
              () ->
                  mvc.perform(delete("/api/v1/squadrons/{id}", responsible).with(as(Actor.ADMIN)))
                      .andExpect(status().isOk());
        };
    confirmBehind(
        request,
        custodian,
        preceding,
        () -> lockUnheldAccount(account),
        winner == DecisionWinner.CANCELLATION ? status().isConflict() : status().isBadRequest());
    String bankState = winner == DecisionWinner.CANCELLATION ? "CANCELLED" : "PENDING";
    String orderState = winner == DecisionWinner.CANCELLATION ? "CANCELLED" : "WAITING";
    assertThat(onlyOwnRequest().path("status").asString()).isEqualTo(bankState);
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value(orderState));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("0"))
        .andExpect(jsonPath("$.responsibilityValid").value(winner != DecisionWinner.HIERARCHY));
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isZero();
  }

  /** The earlier public mutation whose transaction a Bank confirmation must wait for. */
  private enum DecisionWinner {
    CANCELLATION,
    HIERARCHY
  }

  private JsonNode confirmBehind(
      JsonNode request,
      UUID custodian,
      Callable<?> preceding,
      Runnable whileBlocked,
      ResultMatcher expected)
      throws Exception {
    AtomicInteger confirmationPid = new AtomicInteger();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var confirmation =
          transactions.execute(_ -> {
            try {
              preceding.call();
            } catch (Exception failure) {
              throw new IllegalStateException("Preceding mutation failed", failure);
            }
            int blocker = pid();
            var waiting =
                worker.submit(() ->
                    transactions.execute(transaction -> {
                      jdbc.execute("SET LOCAL lock_timeout = '15s'");
                      confirmationPid.set(pid());
                      try {
                        var result =
                            confirm(request, Actor.MANAGEMENT, custodian)
                                .andExpect(expected)
                                .andReturn()
                                .getResponse();
                        if (HttpStatusCode.valueOf(result.getStatus()).isError()) {
                          transaction.setRollbackOnly();
                        }
                        return json.readTree(result.getContentAsByteArray());
                      } catch (Exception failure) {
                        throw new IllegalStateException("Bank confirmation failed", failure);
                      }
                    }));
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                    confirmationPid.get() != 0
                        && Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?))",
                                Boolean.class,
                                blocker,
                                confirmationPid.get())));
            whileBlocked.run();
            return waiting;
          });
      return Objects.requireNonNull(confirmation).get(15, TimeUnit.SECONDS);
    }
  }

  private void lockUnheldAccount(UUID account) {
    assertThat(
            jdbc.queryForObject(
                "SELECT id FROM bank_account WHERE id = ? FOR NO KEY UPDATE NOWAIT",
                UUID.class,
                account))
        .isEqualTo(account);
  }

  @ParameterizedTest
  @EnumSource(AuditFailure.class)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void refusalByEitherAuditRollsBackBankPostingAndOrderProvision(AuditFailure failure)
      throws Exception {
    UUID order = createOrder("1000000");
    UUID operation = UUID.randomUUID();
    UUID custodian = world.holder(Holder.MANAGEMENT_HOLDER);
    UUID account = world.account(Account.OWN_ORG_UNIT);
    provide(order, deposit(operation, account, custodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = onlyOwnRequest();
    assertThat(request.path("counterpartyHandle").asString())
        .isEqualTo("Actual accountless depositor");
    assertThat(request.path("requesterHandle").asString()).isEqualTo(world.username(Actor.MEMBER));
    AuditTarget target =
        switch (failure) {
          case ORDER ->
              new AuditTarget("audit_event", "subject_id", order, "JOB_ORDER_MONEY_PROVIDED");
          case BANK ->
              new AuditTarget(
                  "bank_audit_event", "account_id", account, "BOOKING_REQUEST_CONFIRMED");
        };
    String constraint = "linked_audit_" + target.id().toString().replace("-", "");
    jdbc.execute(
        """
        ALTER TABLE %s ADD CONSTRAINT %s
        CHECK (%s IS DISTINCT FROM '%s'::uuid OR event_type <> '%s') NOT VALID
        """
            .formatted(target.table(), constraint, target.column(), target.id(), target.event()));
    try {
      confirm(request, Actor.MANAGEMENT, custodian).andExpect(status().isConflict());
    } finally {
      jdbc.execute("ALTER TABLE %s DROP CONSTRAINT %s".formatted(target.table(), constraint));
    }

    assertThat(onlyOwnRequest()).isEqualTo(request);
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("0"));
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isZero();
    assertConfirmedAudit(account, order, 0);

    confirm(request, Actor.MANAGEMENT, custodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("POSTED"))
        .andExpect(jsonPath("$.amount").value("600000"));
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("600000"));
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isOne();
    assertConfirmedAudit(account, order, 1);
  }

  private void assertConfirmedAudit(UUID account, UUID order, int count) throws Exception {
    String decider = world.user(Actor.MANAGEMENT).toString();
    mvc.perform(
            get("/api/v1/bank/admin/audit")
                .with(as(Actor.ADMIN))
                .param("accountId", account.toString())
                .param("eventType", "BOOKING_REQUEST_CONFIRMED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(count));
    ResultActions orderAudit =
        mvc.perform(
                get("/api/v1/audit/JOB_ORDER")
                    .with(as(Actor.ADMIN))
                    .param("actorUserId", decider)
                    .param("eventType", "JOB_ORDER_MONEY_PROVIDED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(count));
    if (count > 0) {
      orderAudit.andExpect(jsonPath("$.content[0].subjectId").value(order.toString()));
    }
  }

  /** The two owning audit trails that can refuse a posting. */
  private enum AuditFailure {
    ORDER,
    BANK
  }

  /** The audit insert that the database refuses during one confirmation. */
  private record AuditTarget(String table, String column, UUID id, String event) {}

  @Test
  void recordsAReportedDepositOutsideBankOnceWhenTheRejectedRequestWasUnnecessary()
      throws Exception {
    UUID order = createOrder("1000000");
    UUID operation = UUID.randomUUID();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID custodian = world.holder(Holder.MANAGEMENT_HOLDER);
    long bookings = accountBookingCount(account, Actor.MANAGEMENT);
    provide(order, deposit(operation, account, custodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = onlyOwnRequest();
    reject(request, "Payment was made outside Bank custody")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    Map<String, ?> repair = Map.of("revision", 1, "amount", "600000", "outsideBank", true);
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            repair)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operation.toString()))
        .andExpect(jsonPath("$.bankHandling").value(false))
        .andExpect(jsonPath("$.state").value("POSTED"));
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            repair)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bankHandling").value(false));
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("600000"))
        .andExpect(jsonPath("$.bankHandling").value(false));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.holdings").value("600000"))
        .andExpect(jsonPath("$.history.length()").value(1))
        .andExpect(jsonPath("$.linkedAttempts.length()").value(1))
        .andExpect(jsonPath("$.linkedAttempts[0].state").value("REJECTED"))
        .andExpect(jsonPath("$.linkedAttempts[0].activeLinkedRevision").value(false));
    assertThat(onlyOwnRequest().path("id").asString()).isEqualTo(request.path("id").asString());
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isEqualTo(bookings);
  }

  @Test
  void cancellingOrderClosesUnexecutedAuthorisationAndRetainsPaidDeposit() throws Exception {
    UUID order = createOrder("1000000");
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID unpaid = UUID.randomUUID();
    UUID paid = UUID.randomUUID();
    UUID receivingCustodian = world.holder(Holder.MANAGEMENT_HOLDER);
    provide(
            order,
            authorisation(unpaid, account, world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER), "200000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    provide(order, deposit(paid, account, receivingCustodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode beforeCancel =
        response(
            mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
                .andExpect(status().isOk()));
    perform(
            put("/api/v1/orders/{id}/status", order).with(writer()),
            Map.of(
                "status", "CANCELLED", "version", beforeCancel.path("lifecycleVersion").asLong()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(ownRequestOfType("AUTHORISATION", "CANCELLED").path("amount").asInt())
        .isEqualTo(200000);
    outcome(order, unpaid)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"));
    outcome(order, paid).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING"));
    confirm(ownRequestOfType("DEPOSIT"), Actor.MANAGEMENT, receivingCustodian)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    outcome(order, paid).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("POSTED"));
    assertFigures(order, "600000", "0", "0", "CANCELLED");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bankReversalRestoresPureHandoverButPreservesActualPayment(boolean gamePayment)
      throws Exception {
    setFeeRate("0.005");
    UUID order = createOrder("400000");
    UUID sourceAccount = world.account(Account.OWN_ORG_UNIT);
    UUID targetAccount = world.account(Account.SPECIAL_SHARED);
    UUID sourceCustodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID targetCustodian = gamePayment ? world.holder(Holder.MANAGEMENT_HOLDER) : sourceCustodian;
    provide(order, authorisation(UUID.randomUUID(), sourceAccount, sourceCustodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(onlyOwnRequest(), sourceCustodian).andExpect(status().isOk());
    JsonNode basis = response(money(order).andExpect(status().isOk()));
    JsonNode holder = basis.path("holders").path(0);
    JsonNode recipient =
        response(
            perform(
                    post(MONEY + "/recipients", order).with(writer()),
                    Map.of("bankAccountId", targetAccount))
                .andExpect(status().isCreated()));
    UUID operation = UUID.randomUUID();
    perform(
            post(MONEY + "/movements", order).with(writer()),
            Map.of(
                "operationId",
                operation,
                "kind",
                gamePayment ? "HANDOVER" : "PURPOSE_HANDOVER",
                "sourceHolderId",
                holder.path("id").asString(),
                "sourceVersion",
                holder.path("version").asLong(),
                "targetVersion",
                basis.path("targetVersion").asLong(),
                "recipientId",
                recipient.path("id").asString(),
                "amount",
                "400000",
                "rate",
                gamePayment ? "0.005" : "0",
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type", "TRANSFER",
                    "accountId", sourceAccount,
                    "custodianId", sourceCustodian,
                    "targetAccountId", targetAccount,
                    "targetCustodianId", targetCustodian,
                    "paymentReported", gamePayment,
                    "justification", "Handover to requester account")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    assertFigures(order, "600000", "0", "0", "IN_PROGRESS");

    setFeeRate("0.01");
    JsonNode booked =
        response(
            confirm(
                    ownRequestOfType("TRANSFER"),
                    Actor.MANAGEMENT,
                    sourceCustodian,
                    targetCustodian)
                .andExpect(status().isOk()));
    String transaction = booked.path("resultingTransactionId").asString();
    assertThat(transaction).isNotEmpty();
    String heldAfterHandover = gamePayment ? "198000" : "200000";
    String fee = gamePayment ? "2000" : "0";
    assertFigures(order, heldAfterHandover, "400000", fee, "COMPLETED");

    perform(
            post("/api/v1/bank/transactions/{id}/reversal", transaction).with(as(Actor.MANAGEMENT)),
            Map.of())
        .andExpect(status().isCreated());
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value(gamePayment ? "CLARIFICATION" : "REVERSED"));
    if (gamePayment) {
      assertFigures(order, heldAfterHandover, "400000", fee, "COMPLETED");
    } else {
      assertFigures(order, "600000", "0", "0", "IN_PROGRESS");
    }
  }

  @Test
  void rejectedPaidHandoverRetainsItsBindingUntilWholeRequestReplacement() throws Exception {
    setFeeRate("0.005");
    UUID order = createOrder("500000");
    UUID wrongAccount = world.account(Account.OWN_ORG_UNIT);
    UUID correctAccount = world.account(Account.CARTEL);
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    long wrongBookings = accountBookingCount(wrongAccount, Actor.MANAGEMENT);
    long correctBookings = accountBookingCount(correctAccount, Actor.MANAGEMENT);
    provide(order, authorisation(UUID.randomUUID(), correctAccount, custodian, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(onlyOwnRequest(), Actor.MANAGEMENT, custodian).andExpect(status().isOk());
    JsonNode basis = response(money(order).andExpect(status().isOk()));
    JsonNode holder = basis.path("holders").path(0);
    JsonNode recipient =
        response(
            perform(
                    post(MONEY + "/recipients", order).with(writer()),
                    Map.of("name", "Requester who was paid"))
                .andExpect(status().isCreated()));
    UUID operation = UUID.randomUUID();
    perform(
            post(MONEY + "/movements", order).with(writer()),
            Map.of(
                "operationId",
                operation,
                "kind",
                "HANDOVER",
                "sourceHolderId",
                holder.path("id").asString(),
                "sourceVersion",
                holder.path("version").asLong(),
                "targetVersion",
                basis.path("targetVersion").asLong(),
                "recipientId",
                recipient.path("id").asString(),
                "amount",
                "500000",
                "rate",
                "0.005",
                "bankHandling",
                true,
                "bank",
                withdrawal(wrongAccount, custodian)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"))
        .andExpect(jsonPath("$.linkedRevision").value(1));
    JsonNode original = ownRequestOfType("WITHDRAWAL", "PENDING");
    assertReleaseRefused(order);

    reject(original, "Wrong debit account")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REJECTED"))
        .andExpect(jsonPath("$.amount").value("500000"))
        .andExpect(jsonPath("$.rejectReason").doesNotExist());
    assertReleaseRefused(order);
    assertFigures(order, "600000", "0", "0", "IN_PROGRESS");

    setFeeRate("0.01");
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            Map.of(
                "revision", 1, "amount", "300000", "bank", withdrawal(correctAccount, custodian)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"))
        .andExpect(jsonPath("$.linkedRevision").value(2));
    JsonNode old = ownRequestOfType("WITHDRAWAL", "REJECTED");
    JsonNode replacement = ownRequestOfType("WITHDRAWAL", "PENDING");
    assertThat(old.path("id").asString()).isEqualTo(original.path("id").asString());
    assertThat(old.path("amount").asInt()).isEqualTo(500000);
    assertThat(replacement.path("id").asString()).isNotEqualTo(old.path("id").asString());
    assertThat(replacement.path("amount").asInt()).isEqualTo(300000);
    confirm(old, Actor.MANAGEMENT, custodian).andExpect(status().isConflict());
    confirm(replacement, Actor.MANAGEMENT, custodian).andExpect(status().isOk());

    outcome(order, operation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("POSTED"))
        .andExpect(jsonPath("$.amount").value("300000"))
        .andExpect(jsonPath("$.fee").value("1500"));
    assertFigures(order, "298500", "300000", "1500", "IN_PROGRESS");
    assertThat(accountBookingCount(wrongAccount, Actor.MANAGEMENT)).isEqualTo(wrongBookings);
    assertThat(accountBookingCount(correctAccount, Actor.MANAGEMENT))
        .isEqualTo(correctBookings + 1);
  }

  private Map<String, ?> withdrawal(UUID account, UUID custodian) {
    return Map.of(
        "type",
        "WITHDRAWAL",
        "accountId",
        account,
        "custodianId",
        custodian,
        "paymentReported",
        true,
        "justification",
        "Handover to named requester");
  }

  private void assertReleaseRefused(UUID order) throws Exception {
    JsonNode holder = response(money(order).andExpect(status().isOk())).path("holders").path(0);
    assertThat(holder.path("amount").asString()).isEqualTo("600000");
    assertThat(holder.path("availableAmount").asString()).isEqualTo("97500");
    perform(
            post(MONEY + "/movements/settlement", order).with(writer()),
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "kind",
                "RELEASE",
                "sourceHolderId",
                holder.path("id").asString(),
                "sourceVersion",
                holder.path("version").asLong(),
                "amount",
                "100000",
                "rate",
                "0",
                "bankHandling",
                false))
        .andExpect(status().isConflict());
  }

  private void setFeeRate(String rate) {
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", rate, FEE_SETTING);
  }

  private void assertFigures(UUID order, String held, String delivered, String fee, String state)
      throws Exception {
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdings").value(held))
        .andExpect(jsonPath("$.netHandedOver").value(delivered))
        .andExpect(jsonPath("$.fees").value(fee));
    mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(state));
  }

  private int pid() {
    return Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
  }

  private UUID createOrder(String target) throws Exception {
    JsonNode created =
        response(
            perform(
                    post("/api/v1/orders/auec").with(writer()),
                    Map.of(
                        "responsibleOrgUnitId",
                        responsible,
                        "requestingOrgUnitId",
                        world.pinned(Pin.OWN_STAFFEL),
                        "handle",
                        "Linked order contact",
                        "target",
                        target))
                .andExpect(status().isCreated()));
    UUID id = UUID.fromString(created.path("id").asString());
    if (committedWorld) {
      committedOrder = id;
    }
    return id;
  }

  private Map<String, ?> authorisation(
      UUID operation, UUID account, UUID custodian, String amount) {
    return Map.of(
        "operationId",
        operation,
        "amount",
        amount,
        "bankHandling",
        true,
        "holderVersion",
        0,
        "holderUserId",
        world.user(Actor.EMPLOYEE_DEPOSIT),
        "bank",
        Map.of(
            "type",
            "AUTHORISATION",
            "accountId",
            account,
            "custodianId",
            custodian,
            "justification",
            "Provide order funds"));
  }

  private Map<String, ?> deposit(UUID operation, UUID account, UUID custodian, String amount) {
    return Map.of(
        "operationId",
        operation,
        "amount",
        amount,
        "bankHandling",
        true,
        "holderVersion",
        0,
        "holderUserId",
        world.user(Actor.MANAGEMENT),
        "bank",
        Map.of(
            "type",
            "DEPOSIT",
            "accountId",
            account,
            "custodianId",
            custodian,
            "counterpartyName",
            "Actual accountless depositor",
            "paymentReported",
            true));
  }

  private ResultActions provide(UUID order, Map<String, ?> terms) throws Exception {
    return perform(post(MONEY + "/provisions", order).with(writer()), terms);
  }

  private ResultActions money(UUID order) throws Exception {
    return mvc.perform(get(MONEY, order).with(writer()));
  }

  private ResultActions outcome(UUID order, UUID operation) throws Exception {
    return mvc.perform(get(MONEY + "/operations/{operation}", order, operation).with(writer()));
  }

  private JsonNode onlyOwnRequest() throws Exception {
    List<JsonNode> requests = ownLinkedRequests();
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  private JsonNode ownRequestOfType(String type) throws Exception {
    return ownRequestOfType(type, "PENDING");
  }

  private JsonNode ownRequestOfType(String type, String state) throws Exception {
    var matches = new ArrayList<JsonNode>();
    for (JsonNode request : ownLinkedRequests()) {
      if (request.path("type").asString().equals(type)
          && request.path("status").asString().equals(state)) {
        matches.add(request);
      }
    }
    assertThat(matches).hasSize(1);
    return matches.getFirst();
  }

  private List<JsonNode> ownLinkedRequests() throws Exception {
    JsonNode requests =
        response(mvc.perform(get(OWN_REQUESTS).with(writer())).andExpect(status().isOk()));
    List<JsonNode> linked = new ArrayList<>();
    for (JsonNode request : requests) {
      if ("AUEC_ORDER".equals(request.path("purposeKind").asString())) {
        linked.add(request);
      }
    }
    return linked;
  }

  private ResultActions confirm(JsonNode request, UUID custodian) throws Exception {
    return confirm(request, Actor.EMPLOYEE_WITHDRAW, custodian);
  }

  private ResultActions confirm(JsonNode request, Actor actor, UUID custodian) throws Exception {
    return confirm(request, actor, custodian, null);
  }

  private ResultActions reject(JsonNode request, String reason) throws Exception {
    return perform(
        post(STAFF_REQUESTS + "/{request}/reject", request.path("id").asString())
            .with(as(Actor.MANAGEMENT)),
        Map.of("version", request.path("version").asLong(), "reason", reason));
  }

  private ResultActions confirm(
      JsonNode request, Actor actor, @Nullable UUID custodian, @Nullable UUID destination)
      throws Exception {
    Map<String, Object> decision =
        new HashMap<>(
            Map.of(
                "version",
                request.path("version").asLong(),
                "ownerApprovalConfirmed",
                true,
                "staffNote",
                "Approval checked"));
    if (custodian != null) {
      decision.put("holderId", custodian);
    }
    if (destination != null) {
      decision.put("destinationHolderId", destination);
    }
    return perform(
        post(STAFF_REQUESTS + "/{request}/confirm", request.path("id").asString()).with(as(actor)),
        decision);
  }

  private long accountBookingCount(UUID account) throws Exception {
    return accountBookingCount(account, Actor.EMPLOYEE_WITHDRAW);
  }

  private long accountBookingCount(UUID account, Actor actor) throws Exception {
    return accountDetails(account, actor).path("bookingCount").asLong();
  }

  private long accountBalance(UUID account) throws Exception {
    return accountDetails(account, Actor.MANAGEMENT).path("account").path("balance").asLong();
  }

  private JsonNode accountDetails(UUID account, Actor actor) throws Exception {
    return response(
        mvc.perform(get(ACCOUNTS + "/{account}", account).with(as(actor)))
            .andExpect(status().isOk()));
  }

  private ResultActions perform(MockHttpServletRequestBuilder request, Map<String, ?> body)
      throws Exception {
    return mvc.perform(
        request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
  }

  private JsonNode response(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
  }

  private RequestPostProcessor writer() {
    return jwt()
        .jwt(token(Actor.MEMBER))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private RequestPostProcessor as(Actor actor) {
    return jwt().jwt(token(actor)).authorities(authorities::convert);
  }

  private Jwt token(Actor actor) {
    return Jwt.withTokenValue("linked-order-bank-test")
        .header("alg", "none")
        .subject(world.user(actor).toString())
        .claim("preferred_username", world.username(actor))
        .build();
  }
}
