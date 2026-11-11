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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.AccountAuthorisation;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.AccountDeposit;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.AccountTransfer;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Authorisation;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Counterparty;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Deposit;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.GamePayment;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.HolderTransfer;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Terms;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Withdrawal;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayments;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Exercises linked Bank commands and staff decisions with the real Bank policy and PostgreSQL. */
@SpringBootTest
@Transactional
class BankLinkedPaymentsIntegrationTest {
  private static final String REQUESTS = "/api/v1/bank/requests";
  private static final String FEE_SETTING = "operation.transfer_fee_rate";

  @Autowired private ConfigurableWebApplicationContext context;
  @Autowired private CustomJwtGrantedAuthoritiesConverter authorities;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcClient jdbc;
  @Autowired private BankLinkedPayments payments;
  @Autowired private BankLedgerService ledger;
  @Autowired private TransactionTemplate transactions;
  @Autowired private SystemSettings settings;
  @Autowired private TransferFeePolicy feePolicy;
  @Autowired private DataExportService exports;

  private final JsonMapper json = JsonMapper.builder().build();
  private final List<String> registeredHandlers = new ArrayList<>();
  private final List<BeanDefinition> excludedHandlers = new ArrayList<>();
  private BankAccessWorld world;
  private MockMvc mvc;

  /** Seeds the ordinary Bank access matrix and opens its real HTTP surfaces. */
  @BeforeEach
  void seed() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      world = new BankAccessSeeder(context).seed();
    }
    var factory = context.getBeanFactory();
    for (String name : factory.getBeanNamesForType(BankLinkedEffectHandler.class)) {
      BeanDefinition definition = factory.getMergedBeanDefinition(name);
      if (definition.isAutowireCandidate()) {
        definition.setAutowireCandidate(false);
        excludedHandlers.add(definition);
      }
    }
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Restores the shared application context's production purpose handlers. */
  @AfterEach
  void removeHandlers() {
    DefaultListableBeanFactory factory = (DefaultListableBeanFactory) context.getBeanFactory();
    registeredHandlers.forEach(factory::destroySingleton);
    excludedHandlers.forEach(definition -> definition.setAutowireCandidate(true));
  }

  @Test
  void directAuthorisationPersistsEvidenceAndLeavesBothLedgerDimensionsUnchanged() {
    addHandler();
    UUID account = fundSixMillion();
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BigDecimal custody = balance("bank_holder_posting", "holder_id", holder);
    BankLinkedAttempt attempt = attempt();
    BankLinkedOutcome result =
        as(
            Actor.EMPLOYEE_WITHDRAW,
            () -> payments.submit(attempt, new Authorisation(account, holder, terms("6000000"))));
    assertThat(result).isEqualTo(new BankLinkedOutcome.Authorised(attempt.id()));
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("6000000");
    assertThat(balance("bank_holder_posting", "holder_id", holder)).isEqualByComparingTo(custody);
    assertThat(
            jdbc.sql(
                    """
                    SELECT amount FROM bank_authorisation
                    WHERE id = :id AND account_id = :account AND holder_id = :holder
                      AND authorised_by = :authoriser
                    """)
                .param("id", attempt.id())
                .param("account", account)
                .param("holder", holder)
                .param("authoriser", world.user(Actor.EMPLOYEE_WITHDRAW))
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("6000000");
  }

  @Test
  void finalAuthorisationOfTenMillionCannotUseSixMillionOfBankCoverage() throws Exception {
    addHandler();
    UUID account = fundSixMillion();
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedAttempt attempt = attempt();
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(
                            attempt, new Authorisation(account, holder, terms("10000000")))))
            .requestId();

    confirm(request, Actor.EMPLOYEE_WITHDRAW, holder, null, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_OWNER_APPROVAL_REQUIRED"));
    confirm(request, Actor.EMPLOYEE_WITHDRAW, holder, null, true)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_OVERDRAFT"));
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("6000000");
    assertThat(
            jdbc.sql("SELECT status FROM bank_booking_request WHERE id = :id")
                .param("id", request)
                .query(String.class)
                .single())
        .isEqualTo("PENDING");
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  void krtCeilingAndFourEyesApplyBeforeAuthorisingWithoutAPosting() throws Exception {
    addHandler();
    UUID account = world.account(Account.CARTEL);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BigDecimal original = balance("bank_posting", "account_id", account);
    BankLinkedAttempt attempt = attempt();
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.EMPLOYEE_WITHDRAW,
                    () ->
                        payments.submit(
                            attempt, new Authorisation(account, holder, terms("4000")))))
            .requestId();

    assertThat(authorisationCount(attempt)).isZero();
    confirm(request, Actor.EMPLOYEE_WITHDRAW, holder, null, true)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_FOUR_EYES_REQUIRED"));
    confirm(request, Actor.MANAGEMENT, holder, null, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linked.authorisationId").value(attempt.id().toString()))
        .andExpect(jsonPath("$.resultingTransactionId").doesNotExist());
    assertThat(authorisationCount(attempt)).isOne();
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo(original);
  }

  @Test
  void applicantAndAccountlessDepositorStayDistinctThroughStaffConfirmation() throws Exception {
    FixtureHandler handler = addHandler();
    UUID account = world.account(Account.FOREIGN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedAttempt attempt = attempt();
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(
                            attempt,
                            new Deposit(
                                account,
                                holder,
                                new Counterparty.Accountless("Unnamed Tool Account", null),
                                terms("400")))))
            .requestId();
    confirm(request, Actor.MANAGEMENT, holder, null, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requesterHandle").value(world.username(Actor.MEMBER)))
        .andExpect(jsonPath("$.counterpartyHandle").value("Unnamed Tool Account"))
        .andExpect(jsonPath("$.counterpartyUserId").doesNotExist());
    entityManager.flush();
    BankLinkedOutcome.Posted posted = (BankLinkedOutcome.Posted) handler.outcomes.getLast();
    assertThat(
            jdbc.sql(
                    """
                    SELECT counterparty_handle FROM bank_transaction
                    WHERE id = :id AND counterparty_user_id IS NULL AND initiated_by = :staff
                    """)
                .param("id", posted.transactionId())
                .param("staff", world.user(Actor.MANAGEMENT))
                .query(String.class)
                .single())
        .isEqualTo("Unnamed Tool Account");
  }

  @Test
  void aNewLinkedPaymentCannotOverrideTheConfiguredFee() {
    addHandler();
    settings.putSettingValue(FEE_SETTING, "0.005");
    UUID source = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID target = world.holder(Holder.MANAGEMENT_HOLDER);
    Terms mismatched =
        new Terms(
            new BigDecimal("4000000"), new RateSnapshot(new BigDecimal("0.0075")), null, null);

    assertThatThrownBy(() ->
            as(
                Actor.MEMBER,
                () -> payments.submit(attempt(), new HolderTransfer(source, target, mismatched))))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("fee rate");
  }

  @Test
  void holderTransferUsesTheStoredKrtFeeAndNeedsNoAccountGrant() throws Exception {
    addHandler();
    UUID source = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID target = world.holder(Holder.MANAGEMENT_HOLDER);
    UUID cartel = world.account(Account.CARTEL);
    BigDecimal original = balance("bank_posting", "account_id", cartel);
    settings.putSettingValue(FEE_SETTING, "0.005");
    Terms submitted = new Terms(new BigDecimal("4000000"), feePolicy.snapshot(), null, null);
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(attempt(), new HolderTransfer(source, target, submitted))))
            .requestId();
    settings.putSettingValue(FEE_SETTING, "0.01");
    assertThat(feePolicy.snapshot().rate()).isEqualByComparingTo("0.01");
    mvc.perform(
            get(REQUESTS).with(jwt().jwt(token(Actor.EMPLOYEE)).authorities(authorities::convert)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id").value(hasItem(request.toString())));
    confirm(request, Actor.EMPLOYEE, source, target, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").doesNotExist())
        .andExpect(jsonPath("$.requiresOwnerApproval").value(false));
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", cartel))
        .isEqualByComparingTo(original.subtract(new BigDecimal("20000")));
    assertThat(balance("bank_holder_posting", "holder_id", target)).isEqualByComparingTo("4000000");
  }

  @Test
  void replacementUsesTheFailedAttemptRateAndOnlyTheRevisedWholeAmount() throws Exception {
    addHandler();
    UUID source = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID target = world.holder(Holder.MANAGEMENT_HOLDER);
    UUID cartel = world.account(Account.CARTEL);
    BigDecimal original = balance("bank_posting", "account_id", cartel);
    settings.putSettingValue(FEE_SETTING, "0.005");
    RateSnapshot originalRate = feePolicy.snapshot();
    BankLinkedAttempt first = attempt();
    UUID failed =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(
                            first,
                            new HolderTransfer(
                                source,
                                target,
                                new Terms(new BigDecimal("4000000"), originalRate, null, null)))))
            .requestId();
    as(
        Actor.MEMBER,
        () -> {
          payments.cancel(failed, first);
          return null;
        });
    settings.putSettingValue(FEE_SETTING, "0.01");
    BankLinkedAttempt next = new BankLinkedAttempt(UUID.randomUUID(), first.purpose());
    UUID replacement =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.replace(
                            failed,
                            first,
                            next,
                            new HolderTransfer(
                                source,
                                target,
                                new Terms(new BigDecimal("3000000"), originalRate, null, null)))))
            .requestId();

    confirm(failed, Actor.EMPLOYEE, source, target, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_REQUEST_NOT_PENDING"));
    confirm(replacement, Actor.EMPLOYEE, source, target, false).andExpect(status().isOk());
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", cartel))
        .isEqualByComparingTo(original.subtract(new BigDecimal("15000")));
    assertThat(balance("bank_holder_posting", "holder_id", target)).isEqualByComparingTo("3000000");
  }

  @Test
  void guardedTerminalCancellationPreventsLaterBankConfirmation() throws Exception {
    FixtureHandler handler = addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedAttempt attempt = attempt();
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(attempt, new Authorisation(account, holder, terms("100")))))
            .requestId();
    handler.preparation =
        action -> {
          if (action == BankLinkedEffectHandler.Action.CANCEL) {
            throw new IllegalStateException("The order guard is already held");
          }
        };
    as(
        Actor.EMPLOYEE_WITHDRAW,
        () -> {
          payments.cancelUnexecuted(request, attempt);
          return null;
        });

    confirm(request, Actor.EMPLOYEE_WITHDRAW, holder, null, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BANK_REQUEST_NOT_PENDING"));
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  void selectedCustodianRequiresTheExpectedRegisteredMember() {
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID otherUser = world.user(Actor.MEMBER);
    payments.requireCustodianUser(holder, world.user(Actor.EMPLOYEE_DEPOSIT));

    assertThatThrownBy(() -> payments.requireCustodianUser(holder, otherUser))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void accountTransferConfirmsWithoutCustodianAndPostsOnlyBalancedAccountLegs() throws Exception {
    addHandler();
    UUID source = world.account(Account.OWN_ORG_UNIT);
    UUID destination = world.account(Account.AREA);
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(
                            attempt(), new AccountTransfer(source, destination, terms("400000")))))
            .requestId();

    var result =
        confirm(request, Actor.ADMIN, null, null, true)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.holderId").doesNotExist())
            .andExpect(jsonPath("$.linked.fee").value(0))
            .andReturn();
    UUID transaction =
        UUID.fromString(
            json.readTree(result.getResponse().getContentAsByteArray())
                .path("resultingTransactionId")
                .asString());
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", source)).isEqualByComparingTo("600000");
    assertThat(balance("bank_posting", "account_id", destination)).isEqualByComparingTo("1400000");
    assertThat(holderPostingCount(transaction)).isZero();

    var reversed =
        mvc.perform(
                post("/api/v1/bank/transactions/{id}/reversal", transaction)
                    .with(jwt().jwt(token(Actor.ADMIN)).authorities(authorities::convert))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isCreated())
            .andReturn();
    UUID reversal =
        UUID.fromString(
            json.readTree(reversed.getResponse().getContentAsByteArray()).path("id").asString());
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", source)).isEqualByComparingTo("1000000");
    assertThat(balance("bank_posting", "account_id", destination)).isEqualByComparingTo("1000000");
    assertThat(holderPostingCount(reversal)).isZero();
  }

  @Test
  void accountAuthorisationRequiresCoverageWithoutRecordingCustody() {
    addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    BankLinkedAttempt attempt = attempt();
    assertThat(
            as(
                Actor.ADMIN,
                () ->
                    payments.submit(attempt, new AccountAuthorisation(account, terms("1000000")))))
        .isEqualTo(new BankLinkedOutcome.Authorised(attempt.id()));
    assertThat(
            jdbc.sql("SELECT count(*) FROM bank_authorisation WHERE id = :id AND holder_id IS NULL")
                .param("id", attempt.id())
                .query(Integer.class)
                .single())
        .isOne();
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("1000000");
    var decisionMaker = as(Actor.ADMIN, () -> exports.export(world.user(Actor.ADMIN)));
    var custodyHolder =
        as(Actor.EMPLOYEE_DEPOSIT, () -> exports.export(world.user(Actor.EMPLOYEE_DEPOSIT)));
    assertThat(ownAuthorisations(decisionMaker))
        .singleElement()
        .satisfies(row -> {
          assertThat(row).containsEntry("member_authorised", true);
          assertThat(row).containsEntry("member_is_custodian", false);
        });
    assertThat(ownAuthorisations(custodyHolder)).isEmpty();
  }

  private static List<Map<String, Object>> ownAuthorisations(DataExportService.DataExport export) {
    return export.sections().stream()
        .filter(section -> section.key().equals("bankFundsAuthorisations"))
        .flatMap(section -> section.rows().stream())
        .toList();
  }

  private int holderPostingCount(UUID transaction) {
    return jdbc.sql("SELECT count(*) FROM bank_holder_posting WHERE transaction_id = :id")
        .param("id", transaction)
        .query(Integer.class)
        .single();
  }

  @Test
  void accountDepositWaitsForStaffToSelectARegisteredReceivingCustodian() throws Exception {
    addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID custodian = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MANAGEMENT,
                    () ->
                        payments.submit(
                            attempt(),
                            new AccountDeposit(
                                account,
                                new Counterparty.Accountless("Actual account depositor", null),
                                terms("400")))))
            .requestId();

    confirm(request, Actor.ADMIN, custodian, null, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holderId").value(custodian.toString()))
        .andExpect(jsonPath("$.counterpartyHandle").value("Actual account depositor"))
        .andExpect(jsonPath("$.resultingTransactionId").isNotEmpty());
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("1000400");
  }

  @Test
  void confirmationUsesTheGrantAfterTheOwningEffectPreparation() throws Exception {
    FixtureHandler handler = addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedAttempt attempt = attempt();
    UUID request =
        ((BankLinkedOutcome.Pending)
                as(
                    Actor.MEMBER,
                    () ->
                        payments.submit(attempt, new Authorisation(account, holder, terms("100")))))
            .requestId();
    handler.preparation =
        action -> {
          if (action == BankLinkedEffectHandler.Action.CONFIRM) {
            jdbc.sql(
                    """
                    UPDATE bank_account_grant SET can_withdraw = FALSE
                    WHERE account_id = :account AND user_id = :employee
                    """)
                .param("account", account)
                .param("employee", world.user(Actor.EMPLOYEE_WITHDRAW))
                .update();
          }
        };

    confirm(request, Actor.EMPLOYEE_WITHDRAW, holder, null, false)
        .andExpect(status().isForbidden());
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  void reversalUsesTheManagementRoleAfterTheOwningEffectPreparation() throws Exception {
    FixtureHandler handler = addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedOutcome.Posted posted =
        (BankLinkedOutcome.Posted)
            as(
                Actor.MANAGEMENT,
                () ->
                    payments.submit(
                        attempt(),
                        new Deposit(
                            account,
                            holder,
                            new Counterparty.Accountless("Reversal Depositor", null),
                            terms("500"))));
    entityManager.flush();
    handler.preparation =
        action -> {
          if (action == BankLinkedEffectHandler.Action.REVERSE) {
            jdbc.sql("DELETE FROM user_roles WHERE user_id = :manager")
                .param("manager", world.user(Actor.MANAGEMENT))
                .update();
          }
        };
    mvc.perform(
            post("/api/v1/bank/transactions/" + posted.transactionId() + "/reversal")
                .with(jwt().jwt(token(Actor.MANAGEMENT)).authorities(authorities::convert))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    assertThat(handler.outcomes).containsExactly(posted);
  }

  @Test
  void hiddenLinkedRequestDoesNotConsumeTheVisibleExchangeCap() throws Exception {
    addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    as(
        Actor.STAFFELLEITER,
        () ->
            payments.submit(
                attempt(),
                new Deposit(
                    account,
                    holder,
                    new Counterparty.Accountless("Cap Depositor", null),
                    terms("500"))));
    entityManager.flush();
    BankExchangeRelay relay = new BankExchangeRelay(context);
    UUID member = world.user(Actor.STAFFELLEITER);
    Map<String, Object> deposit =
        Map.of("type", "DEPOSIT", "sourceAccountId", account, "amount", 500);
    for (int request = 0; request < 20; request++) {
      assertThat(relay.write(HttpMethod.POST, member, "/requests", deposit).getStatus())
          .isEqualTo(201);
    }
    assertThat(relay.write(HttpMethod.POST, member, "/requests", deposit).getStatus())
        .isEqualTo(409);
  }

  @Test
  void genuineSamePlayerWithdrawalChargesOnlyPrincipalAndReversalReportsItsOriginal()
      throws Exception {
    FixtureHandler handler = addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedOutcome.Posted posted =
        (BankLinkedOutcome.Posted)
            as(
                Actor.EMPLOYEE_WITHDRAW,
                () ->
                    payments.submit(
                        attempt(),
                        new Withdrawal(
                            account,
                            holder,
                            new Counterparty.Member(world.user(Actor.EMPLOYEE_DEPOSIT), null),
                            GamePayment.SAME_PLAYER,
                            terms("400"))));
    entityManager.flush();
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("999600");
    mvc.perform(
            post("/api/v1/bank/transactions/" + posted.transactionId() + "/reversal")
                .with(jwt().jwt(token(Actor.ADMIN)).authorities(authorities::convert))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated());
    assertThat(handler.outcomes.getLast())
        .isInstanceOfSatisfying(
            BankLinkedOutcome.Reversed.class,
            reversal ->
                assertThat(reversal.originalTransactionId()).isEqualTo(posted.transactionId()));
  }

  @Test
  void ordinaryMembershipCannotAuthoriseFundsFromAHiddenAccount() {
    addHandler();
    UUID account = world.account(Account.FOREIGN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    BankLinkedAttempt attempt = attempt();
    assertThatThrownBy(() ->
            as(
                Actor.MEMBER,
                () -> payments.submit(attempt, new Authorisation(account, holder, terms("100")))))
        .isInstanceOf(NotFoundException.class);
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  void aMatchingAccountlessNameDoesNotProveAWithdrawalIsFeeFree() {
    addHandler();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    UUID holder = world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
    assertThatThrownBy(() ->
            as(
                Actor.EMPLOYEE_WITHDRAW,
                () ->
                    payments.submit(
                        attempt(),
                        new Withdrawal(
                            account,
                            holder,
                            new Counterparty.Accountless(
                                world.username(Actor.EMPLOYEE_DEPOSIT), null),
                            GamePayment.SAME_PLAYER,
                            terms("100")))))
        .isInstanceOf(BadRequestException.class);
    assertThat(balance("bank_posting", "account_id", account)).isEqualByComparingTo("1000000");
  }

  @Test
  void absentHandlerRefusesBeforeTheBankChangesAnything() {
    BankLinkedAttempt attempt = attempt();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    assertThatThrownBy(() ->
            as(
                Actor.EMPLOYEE_WITHDRAW,
                () ->
                    payments.submit(
                        attempt,
                        new Authorisation(
                            account, world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER), terms("100")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one");
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  void twoMatchingHandlersCannotAdmitTheAttempt() {
    addHandler();
    addHandler();
    BankLinkedAttempt attempt = attempt();
    UUID account = world.account(Account.OWN_ORG_UNIT);
    assertThatThrownBy(() ->
            as(
                Actor.EMPLOYEE_WITHDRAW,
                () ->
                    payments.submit(
                        attempt,
                        new Authorisation(
                            account, world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER), terms("100")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exactly one");
    assertThat(authorisationCount(attempt)).isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void refusalByTheOnlyHandlerRollsBackPostingAndAudit() {
    FixtureHandler handler = addHandler();
    handler.refusePosting = true;
    BankLinkedAttempt attempt = attempt();

    assertThatThrownBy(() ->
            transactions.executeWithoutResult(_ -> {
              world = new BankAccessSeeder(context).seed();
              as(
                  Actor.EMPLOYEE_DEPOSIT,
                  () ->
                      payments.submit(
                          attempt,
                          new Deposit(
                              world.account(Account.OWN_ORG_UNIT),
                              world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER),
                              new Counterparty.Accountless("Other Depositor", null),
                              terms("500"))));
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Fixture owner declined the posting");

    UUID transactionId = ((BankLinkedOutcome.Posted) handler.outcomes.getLast()).transactionId();
    assertThat(
            jdbc.sql("SELECT count(*) FROM bank_linked_attempt WHERE id = :id")
                .param("id", attempt.id())
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM bank_transaction WHERE id = :id")
                .param("id", transactionId)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM bank_audit_event WHERE transaction_id = :id")
                .param("id", transactionId)
                .query(Integer.class)
                .single())
        .isZero();
  }

  private UUID fundSixMillion() {
    UUID account = world.account(Account.OWN_ORG_UNIT);
    as(
        Actor.ADMIN,
        () ->
            ledger.bookDeposit(
                new BankDepositRequest(
                    account,
                    world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER),
                    new BigDecimal("5000000"),
                    null)));
    entityManager.flush();
    return account;
  }

  private int authorisationCount(BankLinkedAttempt attempt) {
    return jdbc.sql("SELECT count(*) FROM bank_authorisation WHERE id = :id")
        .param("id", attempt.id())
        .query(Integer.class)
        .single();
  }

  private BigDecimal balance(String table, String column, UUID id) {
    return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM " + table + " WHERE " + column + " = :id")
        .param("id", id)
        .query(BigDecimal.class)
        .single();
  }

  private FixtureHandler addHandler() {
    String beanName = "bankLinkedTestOwner-" + registeredHandlers.size();
    FixtureHandler handler = new FixtureHandler();
    context.getBeanFactory().registerSingleton(beanName, handler);
    registeredHandlers.add(beanName);
    return handler;
  }

  private ResultActions confirm(
      UUID request,
      Actor staff,
      @Nullable UUID holder,
      @Nullable UUID destination,
      boolean attestation)
      throws Exception {
    entityManager.flush();
    Long version =
        jdbc.sql("SELECT version FROM bank_booking_request WHERE id = :id")
            .param("id", request)
            .query(Long.class)
            .single();
    var body =
        json.createObjectNode().put("version", version).put("ownerApprovalConfirmed", attestation);
    if (holder != null) {
      body.put("holderId", holder.toString());
    }
    if (destination != null) {
      body.put("destinationHolderId", destination.toString());
    }
    if (attestation) {
      body.put("staffNote", "Approver identity recorded");
    }
    return mvc.perform(
        post(REQUESTS + "/" + request + "/confirm")
            .with(jwt().jwt(token(staff)).authorities(authorities::convert))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private <T> T as(Actor actor, Supplier<T> operation) {
    Jwt token = token(actor);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(token, Objects.requireNonNull(authorities.convert(token))));
    try {
      return operation.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Jwt token(Actor actor) {
    return Jwt.withTokenValue("linked-bank-test")
        .header("alg", "none")
        .subject(world.user(actor).toString())
        .claim("preferred_username", world.username(actor))
        .build();
  }

  private static BankLinkedAttempt attempt() {
    return new BankLinkedAttempt(
        UUID.randomUUID(), new BankPurpose(BankPurposeKind.AUEC_ORDER, UUID.randomUUID()));
  }

  private Terms terms(String amount) {
    return new Terms(new BigDecimal(amount), feePolicy.snapshot(), null, "Linked effect evidence");
  }

  private static final class FixtureHandler implements BankLinkedEffectHandler {
    private final List<BankLinkedOutcome> outcomes = new ArrayList<>();
    private boolean refusePosting;
    private Consumer<Action> preparation = _ -> {};

    @Override
    public @NotNull BankPurposeKind kind() {
      return BankPurposeKind.AUEC_ORDER;
    }

    @Override
    public void beforeEffect(
        @NotNull BankLinkedAttempt attempt, @NotNull Action action, @NotNull UUID actorId) {
      preparation.accept(action);
    }

    @Override
    public void onOutcome(
        @NotNull BankLinkedAttempt attempt,
        @NotNull BankLinkedOutcome outcome,
        @NotNull UUID actorId) {
      outcomes.add(outcome);
      if (refusePosting && outcome instanceof BankLinkedOutcome.Posted) {
        throw new IllegalStateException("Fixture owner declined the posting");
      }
    }
  }
}
