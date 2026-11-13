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

import static de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessSeeder.LinkedOrderAccount;
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
import org.springframework.http.HttpStatus;
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

  private final List<LinkedOrderAccount> supplementalAccounts = new ArrayList<>();
  private BankAccessWorld world;
  private MockMvc mvc;
  private UUID profit;
  private UUID responsible;
  private UUID committedOrder;
  private UUID noticeApprover;
  private UUID noticeCustodian;
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
      if (noticeApprover != null) {
        removeNoticeRows();
      }
      removeBankRows();
      if (noticeCustodian != null) {
        jdbc.update("DELETE FROM bank_holder WHERE id = ?", noticeCustodian);
        jdbc.update(
            "DELETE FROM bank_audit_event WHERE event_type = 'HOLDER_REGISTERED' AND target_user_id"
                + " = ?",
            world.user(Actor.MEMBER));
      }
      if (noticeApprover != null) {
        users.deleteById(noticeApprover);
      }
      for (var supplemental : supplementalAccounts) {
        jdbc.update("DELETE FROM org_unit WHERE id = ?", supplemental.ownerId());
      }
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

  private void removeNoticeRows() {
    UUID account = world.account(Account.OWN_ORG_UNIT);
    jdbc.update(
        """
        DELETE FROM notification WHERE entity_id = ?
          OR entity_id IN (SELECT id FROM bank_booking_request WHERE account_id = ?)
        """,
        committedOrder,
        account);
    jdbc.update(
        """
        DELETE FROM notification_cause_receipt
        WHERE id IN (SELECT authorisation_id FROM bank_booking_request WHERE account_id = ?)
        """,
        account);
  }

  private void removeBankRows() {
    List<UUID> accounts = new ArrayList<>();
    accounts.add(world.account(Account.OWN_ORG_UNIT));
    supplementalAccounts.stream().map(LinkedOrderAccount::accountId).forEach(accounts::add);
    for (UUID account : accounts) {
      removeBankRequests(account);
    }
    for (UUID account : accounts) {
      removeBankTransactions(account);
      jdbc.update("DELETE FROM bank_audit_event WHERE account_id = ?", account);
    }
    jdbc.update("DELETE FROM bank_holder WHERE id = ?", world.holder(Holder.MANAGEMENT_HOLDER));
    for (UUID account : accounts) {
      jdbc.update("DELETE FROM bank_account WHERE id = ?", account);
    }
  }

  private void removeBankRequests(UUID account) {
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
  }

  private void removeBankTransactions(UUID account) {
    List<UUID> transactions =
        jdbc.queryForList(
            """
            SELECT t.id FROM bank_transaction t
            WHERE EXISTS (
                SELECT 1 FROM bank_posting p WHERE p.transaction_id = t.id AND p.account_id = ?
            )
            ORDER BY t.reversed_transaction_id NULLS LAST
            """,
            UUID.class,
            account);
    for (UUID transaction : transactions) {
      jdbc.update("DELETE FROM bank_holder_posting WHERE transaction_id = ?", transaction);
      jdbc.update("DELETE FROM bank_posting WHERE transaction_id = ?", transaction);
      jdbc.update("DELETE FROM bank_transaction WHERE id = ?", transaction);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void linkedAuthorisationGroupsOutcomeAndRetainsApprovalTasks(boolean muted) throws Exception {
    var approver =
        Objects.requireNonNull(
            transactions.execute(_ ->
                new BankAccessSeeder(context)
                    .seedLinkedOrderApprover(world.pinned(Pin.OWN_STAFFEL))));
    noticeApprover = approver.getId();
    RequestPostProcessor approverIdentity =
        jwt()
            .jwt(token(approver.getId(), approver.getUsername()))
            .authorities(authorities::convert);
    UUID applicant = world.user(Actor.MEMBER);
    UUID account = world.account(Account.OWN_ORG_UNIT);
    noticeCustodian = registerNoticeCustodian(applicant);
    perform(
            post("/api/v1/bank/deposits").with(as(Actor.MANAGEMENT)),
            Map.of(
                "accountId",
                account,
                "holderId",
                world.holder(Holder.MANAGEMENT_HOLDER),
                "amount",
                5000,
                "counterpartyExternalName",
                "Fixture depositor"))
        .andExpect(status().isCreated());
    long bookings = accountBookingCount(account, Actor.MANAGEMENT);
    String confirmation = "BANK_BOOKING_REQUEST_CONFIRMED";
    perform(
            put("/api/v1/notifications/preferences/{type}", confirmation).with(writer()),
            Map.of("muted", muted))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.muted").value(muted));

    UUID order = createOrder("10000");
    UUID operation = UUID.randomUUID();
    Map<String, ?> terms =
        Map.of(
            "operationId",
            operation,
            "amount",
            "4000",
            "holderVersion",
            0,
            "holderUserId",
            applicant,
            "bankHandling",
            true,
            "bank",
            Map.of("type", "AUTHORISATION", "accountId", account, "custodianId", noticeCustodian));
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    JsonNode request = ownRequestOfType("AUTHORISATION");
    assertThat(request.path("requiredApprover").asString()).isEqualTo("RESPONSIBLE_HOLDER");
    UUID requestId = UUID.fromString(request.path("id").asString());
    String approvalNeeded = "BANK_BOOKING_REQUEST_APPROVAL_NEEDED";
    String approvedForStaff = "BANK_BOOKING_REQUEST_APPROVED";
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("0"));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(noticeTypes(approverIdentity, requestId)).containsExactly(approvalNeeded));

    perform(
            post(OWN_REQUESTS + "/{request}/owner-approval", requestId).with(approverIdentity),
            Map.of("version", request.path("version").asLong()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerApprovalGranted").value(true));
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("0"));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          assertThat(noticeTypes(approverIdentity, requestId)).doesNotContain(approvalNeeded);
          assertThat(noticeTypes(as(Actor.MANAGEMENT), requestId)).contains(approvedForStaff);
        });

    JsonNode ready = pendingStaffRequest(requestId);
    assertThat(ready.path("ownerApprovalGranted").asBoolean()).isTrue();
    assertThat(ready.path("version").asLong()).isGreaterThan(request.path("version").asLong());
    confirm(ready, Actor.MANAGEMENT, noticeCustodian)
        .andExpect(result -> {
          var response = result.getResponse();
          assertThat(response.getStatus())
              .as("Bank confirmation response: %s", response.getContentAsString())
              .isEqualTo(HttpStatus.OK.value());
        })
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          assertThat(noticeTypes(as(Actor.MANAGEMENT), requestId)).doesNotContain(approvedForStaff);
          assertApplicantOutcomeNotices(order, requestId, confirmation, muted);
        });
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operation.toString()))
        .andExpect(jsonPath("$.state").value("AUTHORISED"));
    assertApplicantOutcomeNotices(order, requestId, confirmation, muted);
    assertThat(ownLinkedRequests()).hasSize(1);
    money(order).andExpect(status().isOk()).andExpect(jsonPath("$.provided").value("4000"));
    assertThat(accountBookingCount(account, Actor.MANAGEMENT)).isEqualTo(bookings);
    assertThat(accountBalance(account)).isEqualTo(5000);
    assertConfirmedAudit(account, order, 1);
  }

  private JsonNode pendingStaffRequest(UUID requestId) throws Exception {
    JsonNode queue =
        response(
            mvc.perform(get(STAFF_REQUESTS).with(as(Actor.MANAGEMENT))).andExpect(status().isOk()));
    for (JsonNode request : queue.path("content")) {
      if (requestId.toString().equals(request.path("id").asString())) {
        return request;
      }
    }
    throw new AssertionError("The Bank staff queue lacks pending request " + requestId);
  }

  private UUID registerNoticeCustodian(UUID applicant) throws Exception {
    JsonNode holder =
        response(
            perform(
                    post("/api/v1/bank/holders").with(as(Actor.MANAGEMENT)),
                    Map.of("userId", applicant))
                .andExpect(status().isCreated()));
    return UUID.fromString(holder.path("id").asString());
  }

  private List<String> noticeTypes(RequestPostProcessor identity, UUID entityId) throws Exception {
    JsonNode inbox =
        response(
            mvc.perform(get("/api/v1/notifications").with(identity)).andExpect(status().isOk()));
    List<String> types = new ArrayList<>();
    for (JsonNode entry : inbox.path("content")) {
      if (entityId.toString().equals(entry.path("entityId").asString())) {
        types.add(entry.path("type").asString());
      }
    }
    return types;
  }

  private void assertApplicantOutcomeNotices(
      UUID order, UUID requestId, String confirmation, boolean muted) throws Exception {
    assertThat(noticeTypes(writer(), requestId))
        .filteredOn(confirmation::equals)
        .hasSize(muted ? 0 : 1);
    assertThat(noticeTypes(writer(), order)).doesNotContain("JOB_ORDER_MONEY_PROVISIONED");
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
  void accountProvisionRequiresCurrentPositionVersionButMatchingReplayDoesNot() throws Exception {
    UUID order = createOrder("600000");
    UUID account = world.account(Account.OWN_ORG_UNIT);
    Map<String, Object> terms =
        new HashMap<>(accountAuthorisationTerms(UUID.randomUUID(), account, "200000"));
    terms.remove("holderVersion");
    provide(order, terms).andExpect(status().isConflict());
    assertThat(ownLinkedRequests()).isEmpty();

    terms.put("holderVersion", 0L);
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(ownRequestOfType("AUTHORISATION"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isOk());
    terms.remove("holderVersion");
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("AUTHORISED"));

    terms.put("operationId", UUID.randomUUID());
    provide(order, terms).andExpect(status().isConflict());
    terms.put("holderVersion", 0L);
    provide(order, terms).andExpect(status().isConflict());
    assertThat(ownLinkedRequests()).hasSize(1);

    terms.put("holderVersion", accountProvisionVersion(order, account));
    provide(order, terms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
  }

  @Test
  void linkedBankFundingHonoursTheActiveOrgUnit() throws Exception {
    var seeder = new BankAccessSeeder(context);
    UUID member = world.user(Actor.MEMBER);
    seeder.seatLinkedOrderMember(member, responsible);
    UUID local = seeder.seedLinkedOrderAccount(member, units.findById(responsible).orElseThrow());
    UUID other = world.account(Account.OWN_ORG_UNIT);
    UUID order = createOrder("600000");
    String pin = responsible.toString();

    mvc.perform(
            get(MONEY + "/bank-accounts", order).with(writer()).header(ACTIVE_ORG_UNIT_HEADER, pin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withdrawal[*].id").value(hasItem(local.toString())))
        .andExpect(jsonPath("$.withdrawal[*].id").value(not(hasItem(other.toString()))))
        .andExpect(jsonPath("$.transfer[*].id").value(not(hasItem(other.toString()))))
        .andExpect(jsonPath("$.collection[*].id").value(hasItem(other.toString())))
        .andExpect(jsonPath("$.designation[*].id").value(hasItem(other.toString())));

    Map<String, ?> otherTerms = accountAuthorisationTerms(UUID.randomUUID(), other, "200000");
    perform(
            post(MONEY + "/provisions/preview", order)
                .with(writer())
                .header(ACTIVE_ORG_UNIT_HEADER, pin),
            otherTerms)
        .andExpect(status().isNotFound());
    perform(
            post(MONEY + "/provisions", order).with(writer()).header(ACTIVE_ORG_UNIT_HEADER, pin),
            otherTerms)
        .andExpect(status().isNotFound());
    assertThat(ownLinkedRequests()).isEmpty();

    var localTerms = accountAuthorisationTerms(UUID.randomUUID(), local, "200000");
    perform(
            post(MONEY + "/provisions/preview", order)
                .with(writer())
                .header(ACTIVE_ORG_UNIT_HEADER, pin),
            localTerms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bank.disposition").value("PENDING"));
    perform(
            post(MONEY + "/provisions", order).with(writer()).header(ACTIVE_ORG_UNIT_HEADER, pin),
            localTerms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));

    perform(
            post(MONEY + "/recipients", order).with(writer()).header(ACTIVE_ORG_UNIT_HEADER, pin),
            Map.of("bankAccountId", other))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.bankAccountId").doesNotExist());

    reportDepositToAccount(order, other, "200000", "Order contributor");
    confirmDepositToAccount("Order contributor", world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER));
    mvc.perform(get(MONEY, order).with(writer()).header(ACTIVE_ORG_UNIT_HEADER, pin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountHoldings[?(@.amount == '200000')].id").isNotEmpty())
        .andExpect(
            jsonPath("$.accountHoldings[*].bankAccountId").value(not(hasItem(other.toString()))));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountHoldings[*].bankAccountId").value(hasItem(other.toString())));
  }

  @Test
  void requesterAccountReturnsFundsToTheirOriginalCollectionPosition() throws Exception {
    setFeeRate("0");
    UUID order = createOrder("600000");
    UUID collection = world.account(Account.OWN_ORG_UNIT);
    UUID requester = world.account(Account.CARTEL);
    provide(order, accountAuthorisationTerms(UUID.randomUUID(), collection, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(ownRequestOfType("AUTHORISATION"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isOk());
    String designation = designateBankRequester(order, requester);
    handOverAccountFunds(order, collection, requester, designation, "600000");
    assertFigures(order, "0", "600000", "0", "COMPLETED");

    JsonNode delivered =
        response(
            money(order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnableRecipientIds").isEmpty()));
    perform(
            post(MONEY + "/movements/settlement", order).with(writer()),
            Map.of(
                "operationId",
                UUID.randomUUID(),
                "kind",
                "REQUESTER_RETURN",
                "recipientId",
                designation,
                "destinationName",
                "Actual accountless recipient",
                "destinationVersion",
                0,
                "targetVersion",
                delivered.path("targetVersion").asLong(),
                "amount",
                "200000",
                "rate",
                "0",
                "bankHandling",
                true,
                "bank",
                withdrawal(requester, world.holder(Holder.MANAGEMENT_HOLDER))))
        .andExpect(status().isConflict());

    UUID returned = UUID.randomUUID();
    requestAccountReturn(order, collection, requester, designation, returned, "200000");
    assertFigures(order, "0", "600000", "0", "COMPLETED");
    UUID transaction = confirmAccountTransfer();
    assertAccountOnlyPosting(transaction);
    assertFigures(order, "200000", "400000", "0", "IN_PROGRESS");
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.holders").isEmpty())
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("200000"));
    outcome(order, returned).andExpect(jsonPath("$.state").value("POSTED"));

    JsonNode reversal =
        response(
            perform(
                    post("/api/v1/bank/transactions/{id}/reversal", transaction)
                        .with(as(Actor.MANAGEMENT)),
                    Map.of())
                .andExpect(status().isCreated()));
    assertAccountOnlyPosting(UUID.fromString(reversal.path("id").asString()));
    assertFigures(order, "0", "600000", "0", "COMPLETED");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void aPostedReturnPreventsReversingItsOriginalCollectionHandover() throws Exception {
    UUID order = createOrder("1400000");
    UUID firstCollection = world.account(Account.OWN_ORG_UNIT);
    UUID secondCollection = addLinkedOrderAccount();
    UUID requester = addLinkedOrderAccount();
    UUID custodian = world.holder(Holder.MANAGEMENT_HOLDER);
    reportDepositToAccount(order, firstCollection, "600000", "First contributor");
    confirmDepositToAccount("First contributor", custodian);
    reportDepositToAccount(order, secondCollection, "800000", "Second contributor");
    confirmDepositToAccount("Second contributor", custodian);
    String designation = designateBankRequester(order, requester);

    UUID firstTransfer =
        handOverAccountFunds(order, firstCollection, requester, designation, "600000");
    handOverAccountFunds(order, secondCollection, requester, designation, "800000");
    assertFigures(order, "0", "1400000", "0", "COMPLETED");
    requestAccountReturn(
        order, firstCollection, requester, designation, UUID.randomUUID(), "200000");
    confirmAccountTransfer();
    assertFigures(order, "200000", "1200000", "0", "IN_PROGRESS");

    long collectionBookings = accountBookingCount(firstCollection, Actor.MANAGEMENT);
    long requesterBookings = accountBookingCount(requester, Actor.MANAGEMENT);
    perform(
            post("/api/v1/bank/transactions/{id}/reversal", firstTransfer)
                .with(as(Actor.MANAGEMENT)),
            Map.of())
        .andExpect(status().isConflict());
    assertThat(accountBalance(firstCollection)).isEqualTo(200000);
    assertThat(accountBalance(requester)).isEqualTo(1200000);
    assertThat(accountBookingCount(firstCollection, Actor.MANAGEMENT))
        .isEqualTo(collectionBookings);
    assertThat(accountBookingCount(requester, Actor.MANAGEMENT)).isEqualTo(requesterBookings);
    assertFigures(order, "200000", "1200000", "0", "IN_PROGRESS");
  }

  private UUID addLinkedOrderAccount() {
    var account =
        Objects.requireNonNull(
            transactions.execute(_ ->
                new BankAccessSeeder(context).seedLinkedOrderAccount(world.user(Actor.MEMBER))));
    supplementalAccounts.add(account);
    return account.accountId();
  }

  private String designateBankRequester(UUID order, UUID requester) throws Exception {
    return response(
            perform(
                    post(MONEY + "/recipients", order).with(writer()),
                    Map.of("bankAccountId", requester))
                .andExpect(status().isCreated()))
        .path("id")
        .asString();
  }

  private UUID handOverAccountFunds(
      UUID order, UUID collection, UUID requester, String designation, String amount)
      throws Exception {
    JsonNode money = response(money(order).andExpect(status().isOk()));
    JsonNode position = accountPosition(money, collection);
    perform(
            post(MONEY + "/movements", order).with(writer()),
            Map.of(
                "operationId", UUID.randomUUID(),
                "kind", "PURPOSE_HANDOVER",
                "sourceAccountHoldingId", position.path("id").asString(),
                "sourceVersion", position.path("version").asLong(),
                "targetVersion", money.path("targetVersion").asLong(),
                "recipientId", designation,
                "amount", amount,
                "bankHandling", true,
                "bank", accountTransferTerms(collection, requester)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    return confirmAccountTransfer();
  }

  private void requestAccountReturn(
      UUID order,
      UUID collection,
      UUID requester,
      String designation,
      UUID operation,
      String amount)
      throws Exception {
    JsonNode money = response(money(order).andExpect(status().isOk()));
    JsonNode position = accountPosition(money, collection);
    perform(
            post(MONEY + "/movements/settlement", order).with(writer()),
            Map.of(
                "operationId",
                operation,
                "kind",
                "REQUESTER_RETURN",
                "destinationAccountHoldingId",
                position.path("id").asString(),
                "destinationVersion",
                position.path("version").asLong(),
                "targetVersion",
                money.path("targetVersion").asLong(),
                "recipientId",
                designation,
                "amount",
                amount,
                "bankHandling",
                true,
                "bank",
                accountTransferTerms(requester, collection)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
  }

  private UUID confirmAccountTransfer() throws Exception {
    return UUID.fromString(
        response(
                confirm(ownRequestOfType("TRANSFER"), Actor.MANAGEMENT, null, null)
                    .andExpect(status().isOk()))
            .path("resultingTransactionId")
            .asString());
  }

  private static JsonNode accountPosition(JsonNode money, UUID account) {
    for (JsonNode position : money.path("accountHoldings")) {
      if (account.toString().equals(position.path("bankAccountId").asString())) {
        return position;
      }
    }
    throw new AssertionError("Missing order account position " + account);
  }

  private static Map<String, ?> accountAuthorisationTerms(
      UUID operation, UUID account, String amount) {
    return Map.of(
        "operationId",
        operation,
        "amount",
        amount,
        "holderVersion",
        0,
        "bankHandling",
        true,
        "bank",
        Map.of(
            "type",
            "AUTHORISATION",
            "accountOnly",
            true,
            "accountId",
            account,
            "justification",
            "Attribute existing funds to this order"));
  }

  private static Map<String, ?> accountTransferTerms(UUID source, UUID target) {
    return Map.of(
        "type",
        "TRANSFER",
        "accountOnly",
        true,
        "accountId",
        source,
        "targetAccountId",
        target,
        "justification",
        "Transfer the attributed funds");
  }

  @Test
  void aCorrectionCannotMakeMoreThanTheAuthorisedBankPrincipalAvailable() throws Exception {
    UUID order = createOrder("800000");
    UUID collection = world.account(Account.OWN_ORG_UNIT);
    UUID original = UUID.randomUUID();
    long initialBookings = accountBookingCount(collection, Actor.MANAGEMENT);
    provide(order, accountAuthorisationTerms(original, collection, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(ownRequestOfType("AUTHORISATION"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isOk());
    JsonNode holding =
        response(money(order).andExpect(status().isOk())).path("accountHoldings").path(0);
    perform(
            post(MONEY + "/corrections", order).with(writer()),
            Map.of(
                "operationId", UUID.randomUUID(),
                "entries", List.of(Map.of("originalOperationId", original, "amount", "700000")),
                "holderVersions",
                    Map.of(holding.path("id").asString(), holding.path("version").asLong())))
        .andExpect(status().isBadRequest());
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("600000"));
    assertThat(accountBookingCount(collection, Actor.MANAGEMENT)).isEqualTo(initialBookings);
  }

  @Test
  void accountCollectionAndRequesterTransferNeverNeedAnOrderPlayerHolder() throws Exception {
    UUID order = createOrder("800000");
    UUID collection = world.account(Account.OWN_ORG_UNIT);
    UUID destination = world.account(Account.SPECIAL_SHARED);
    assertThat(accountBalance(collection)).isEqualTo(1000000);
    assertThat(accountBalance(destination)).isEqualTo(1000000);
    mvc.perform(get(MONEY + "/bank-accounts", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.collection[*].id").isNotEmpty())
        .andExpect(jsonPath("$.collection[*].balance").doesNotExist())
        .andExpect(jsonPath("$.custodians").isEmpty());
    perform(
            post(MONEY + "/provisions/preview", order).with(writer()),
            Map.of(
                "amount",
                "400000",
                "holderVersion",
                0,
                "bankHandling",
                true,
                "bank",
                Map.of(
                    "type",
                    "DEPOSIT",
                    "accountOnly",
                    true,
                    "accountId",
                    collection,
                    "counterpartyName",
                    "First contributor")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("400000"))
        .andExpect(jsonPath("$.resultingHolding").value("400000"))
        .andExpect(jsonPath("$.bank.disposition").value("PENDING"))
        .andExpect(jsonPath("$.bank.feeFunding").value("DEPOSITOR"))
        .andExpect(jsonPath("$.bank.bankSourceDebit").value("0"))
        .andExpect(jsonPath("$.bank.orderSourceCharge").value("0"));
    assertThat(ownLinkedRequests()).isEmpty();
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
                "holderVersion",
                accountProvisionVersion(order, collection),
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
    var transferTerms =
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
                "type", "TRANSFER",
                "accountOnly", true,
                "justification", "Handover of collected funds"));
    perform(post(MONEY + "/movements/preview", order).with(writer()), transferTerms)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completes").value(true))
        .andExpect(jsonPath("$.bank.disposition").value("PENDING"))
        .andExpect(jsonPath("$.bank.actualFee").value("0"))
        .andExpect(jsonPath("$.bank.feeFunding").value("NONE"))
        .andExpect(jsonPath("$.bank.bankSourceDebit").value("800000"))
        .andExpect(jsonPath("$.bank.orderSourceCharge").value("800000"));
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("800000"));
    perform(post(MONEY + "/movements", order).with(writer()), transferTerms)
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

  @Test
  void independentBankDebitPreventsAnUncoveredOrderHandover() throws Exception {
    UUID order = createOrder("1000000");
    UUID collection = authoriseOrderAccountFunds(order);
    UUID destination = world.account(Account.SPECIAL_SHARED);
    JsonNode money = response(money(order).andExpect(status().isOk()));
    JsonNode holding = money.path("accountHoldings").path(0);
    assertThat(holding.path("availableAmount").asString()).isEqualTo("600000");
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
                "600000",
                "rate",
                "0",
                "bankHandling",
                true,
                "bank",
                accountTransferTerms(collection, destination)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));

    withdrawIndependently(collection);
    long bookings = accountBookingCount(collection, Actor.MANAGEMENT);
    confirm(ownRequestOfType("TRANSFER"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_OVERDRAFT"));
    assertThat(accountBalance(collection)).isEqualTo(300000);
    assertThat(accountBookingCount(collection, Actor.MANAGEMENT)).isEqualTo(bookings);
    assertFigures(order, "600000", "0", "0", "IN_PROGRESS");
  }

  @Test
  void accountShortageAndCancelledRestReleasePreserveProvisionWithoutBankPostings()
      throws Exception {
    UUID order = createOrder("1000000");
    UUID collection = authoriseOrderAccountFunds(order);
    withdrawIndependently(collection);
    long bookings = accountBookingCount(collection, Actor.MANAGEMENT);
    JsonNode holding =
        response(money(order).andExpect(status().isOk())).path("accountHoldings").path(0);
    Map<String, ?> shortage = accountAdjustment(holding, "SHORTAGE", "300000");

    perform(post(MONEY + "/movements/settlement/preview", order).with(writer()), shortage)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceAfter").value("300000"))
        .andExpect(jsonPath("$.fee").value("0"));
    perform(post(MONEY + "/movements/settlement", order).with(writer()), shortage)
        .andExpect(status().isOk());
    perform(post(MONEY + "/movements/settlement", order).with(writer()), shortage)
        .andExpect(status().isOk());
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.shortages").value("300000"))
        .andExpect(jsonPath("$.holders").isEmpty())
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("300000"));

    JsonNode lifecycle =
        response(
            mvc.perform(get("/api/v1/orders/{id}", order).with(writer()))
                .andExpect(status().isOk()));
    perform(
            put("/api/v1/orders/{id}/status", order).with(writer()),
            Map.of("status", "CANCELLED", "version", lifecycle.path("lifecycleVersion").asLong()))
        .andExpect(status().isOk());

    JsonNode remaining =
        response(money(order).andExpect(status().isOk())).path("accountHoldings").path(0);
    perform(
            post(MONEY + "/movements/settlement", order).with(writer()),
            accountAdjustment(remaining, "RELEASE", "100000"))
        .andExpect(status().isOk());
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provided").value("600000"))
        .andExpect(jsonPath("$.shortages").value("300000"))
        .andExpect(jsonPath("$.released").value("100000"))
        .andExpect(jsonPath("$.accountHoldings[0].availableAmount").value("200000"));
    assertFigures(order, "200000", "0", "0", "CANCELLED");
    assertThat(accountBalance(collection)).isEqualTo(300000);
    assertThat(accountBookingCount(collection, Actor.MANAGEMENT)).isEqualTo(bookings);
  }

  private UUID authoriseOrderAccountFunds(UUID order) throws Exception {
    setFeeRate("0");
    UUID collection = world.account(Account.OWN_ORG_UNIT);
    provide(order, accountAuthorisationTerms(UUID.randomUUID(), collection, "600000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    confirm(ownRequestOfType("AUTHORISATION"), Actor.MANAGEMENT, null, null)
        .andExpect(status().isOk());
    return collection;
  }

  private void withdrawIndependently(UUID collection) throws Exception {
    perform(
            post("/api/v1/bank/withdrawals").with(as(Actor.MANAGEMENT)),
            Map.of(
                "accountId",
                collection,
                "holderId",
                world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER),
                "amount",
                "700000",
                "justification",
                "Independent expenditure"))
        .andExpect(status().isCreated());
    assertThat(accountBalance(collection)).isEqualTo(300000);
  }

  private static Map<String, ?> accountAdjustment(JsonNode holding, String kind, String amount) {
    return Map.of(
        "operationId",
        UUID.randomUUID(),
        "kind",
        kind,
        "sourceAccountHoldingId",
        holding.path("id").asString(),
        "sourceVersion",
        holding.path("version").asLong(),
        "amount",
        amount,
        "bankHandling",
        false);
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
                "holderVersion",
                accountProvisionVersion(order, account),
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

  private long accountProvisionVersion(UUID order, UUID account) throws Exception {
    JsonNode money = response(money(order).andExpect(status().isOk()));
    for (JsonNode position : money.path("accountHoldings")) {
      if (account.toString().equals(position.path("bankAccountId").asString())) {
        return position.path("version").asLong();
      }
    }
    return 0;
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
    Map<String, Object> bank =
        new HashMap<>(
            Map.of(
                "type", "TRANSFER",
                "accountId", sourceAccount,
                "custodianId", sourceCustodian,
                "targetCustodianId", targetCustodian,
                "paymentReported", gamePayment,
                "justification", "Handover to requester account"));
    if (!gamePayment) {
      bank.put("targetAccountId", targetAccount);
    }
    Map<String, Object> movement =
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
            bank);
    perform(post(MONEY + "/movements/preview", order).with(writer()), movement)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bank.actualFee").value(gamePayment ? "2000" : "0"))
        .andExpect(jsonPath("$.bank.disposition").value("PENDING"));
    perform(post(MONEY + "/movements", order).with(writer()), movement)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"));
    assertFigures(order, "600000", "0", "0", "IN_PROGRESS");
    mvc.perform(get(MONEY + "/bank-accounts", order).with(as(Actor.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personalReturnAccounts").isEmpty());

    setFeeRate("0.01");
    if (gamePayment) {
      perform(post(MONEY + "/movements", order).with(writer()), movement)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.operationId").value(operation.toString()))
          .andExpect(jsonPath("$.state").value("WAITING"));
      bank.put("targetAccountId", world.account(Account.CARTEL));
      perform(post(MONEY + "/movements", order).with(writer()), movement)
          .andExpect(status().isConflict());
      bank.remove("targetAccountId");
    }
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
    money(order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.returnableRecipientIds[0]").value(recipient.path("id").asString()))
        .andExpect(jsonPath("$.recipients[0].bankAccountId").value(targetAccount.toString()));
    String designation = "$.personalReturnAccounts['" + recipient.path("id").asString() + "']";
    mvc.perform(get(MONEY + "/bank-accounts", order).with(as(Actor.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath(designation).value(targetAccount.toString()));
    mvc.perform(get(MONEY + "/bank-accounts", order).with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.personalReturnAccounts").isEmpty());

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
      money(order)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.returnableRecipientIds").isEmpty());
      mvc.perform(get(MONEY + "/bank-accounts", order).with(as(Actor.ADMIN)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.personalReturnAccounts").isEmpty());
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
    mvc.perform(
            get(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1))
        .andExpect(jsonPath("$.amount").value("500000"))
        .andExpect(jsonPath("$.rate").value("0.005"))
        .andExpect(jsonPath("$.actualFee").value("2500"))
        .andExpect(jsonPath("$.accountChangeAllowed").value(true))
        .andExpect(jsonPath("$.custodianId").doesNotExist())
        .andExpect(jsonPath("$.rejectReason").doesNotExist());
    assertReleaseRefused(order);
    assertFigures(order, "600000", "0", "0", "IN_PROGRESS");

    setFeeRate("0.01");
    Map<String, ?> repair =
        Map.of(
            "revision",
            1,
            "amount",
            "300000",
            "accountId",
            correctAccount,
            "justification",
            "Handover to named requester");
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement/preview", order, operation)
                .with(writer()),
            repair)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rate").value("0.005"))
        .andExpect(jsonPath("$.actualFee").value("1500"))
        .andExpect(jsonPath("$.bank.disposition").value("PENDING"))
        .andExpect(jsonPath("$.bank.bankSourceDebit").value("301500"));
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            repair)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("WAITING"))
        .andExpect(jsonPath("$.linkedRevision").value(2));
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            repair)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedRevision").value(2));
    perform(
            post(MONEY + "/operations/{operation}/bank-replacement", order, operation)
                .with(writer()),
            Map.of(
                "revision", 1,
                "amount", "300000",
                "justification", "Handover to named requester"))
        .andExpect(status().isConflict());
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
    return token(world.user(actor), world.username(actor));
  }

  private static Jwt token(UUID userId, String username) {
    return Jwt.withTokenValue("linked-order-bank-test")
        .header("alg", "none")
        .subject(userId.toString())
        .claim("preferred_username", username)
        .build();
  }
}
