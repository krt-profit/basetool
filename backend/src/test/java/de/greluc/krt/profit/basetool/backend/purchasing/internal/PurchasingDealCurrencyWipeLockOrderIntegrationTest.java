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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccount;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A manual deal cancellation and a currency wipe on the same deal against PostgreSQL (REQ-PUR-026,
 * REQ-BANK-063, REQ-PATCH-042): both lock the deal before its Bank request, so the wipe waits for
 * the cancellation instead of holding the request it needs.
 *
 * <p>Its live time lies in 2000, before every other test's requests; every row it writes is removed
 * afterwards.
 */
@SpringBootTest
class PurchasingDealCurrencyWipeLockOrderIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");
  private static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);

  @Autowired private PurchasingDealService dealService;
  @Autowired private PurchasingDealRepository dealRepository;
  @Autowired private PurchasingDealTrancheRepository trancheRepository;
  @Autowired private PurchasingPartnerRepository partnerRepository;
  @Autowired private BankCurrencyWipe bankWipe;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;

  private final UUID patchChange = UUID.randomUUID();
  private UUID delegate;
  private UUID material;
  private UUID partner;
  private UUID account;
  private UUID deal;
  private UUID request;

  @BeforeEach
  void seed() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("pur-wipe-" + suffix);
    delegate = userRepository.save(user).getId();
    Material ore = new Material();
    ore.setName("Pur Wipe Ore " + suffix);
    ore.setType(MaterialType.RAW);
    ore.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(ore).getId();
    PurchasingPartner counterparty = new PurchasingPartner();
    counterparty.setKind(PartnerKind.ORG);
    counterparty.setName("Pur Wipe Partner " + suffix);
    counterparty.setChannel(PartnerChannel.OTHER);
    counterparty.setContactVia(PartnerContact.OTHER);
    counterparty.setStatus(PartnerStatus.ACTIVE);
    partner = partnerRepository.save(counterparty).getId();
    account = newAccount("Pur Wipe Konto " + suffix);
    deal = submittedDeal(ore, counterparty);
    request = pendingRequestBeforeTheLiveTime();
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setDealId(deal);
    tranche.setKind(TrancheKind.ON_DELIVERY);
    tranche.setAmount(500);
    tranche.setBankRequestId(request);
    tranche.setState(TrancheState.REQUESTED);
    trancheRepository.saveAndFlush(tranche);
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", deal);
    jdbc.update(
        "DELETE FROM bank_audit_event WHERE details LIKE ?", "patchChange=" + patchChange + "%");
    jdbc.update("DELETE FROM bank_audit_event WHERE account_id = ?", account);
    jdbc.update("DELETE FROM purchasing_deal_tranche WHERE deal_id = ?", deal);
    jdbc.update("DELETE FROM bank_booking_request WHERE id = ?", request);
    jdbc.update("DELETE FROM purchasing_deal WHERE id = ?", deal);
    jdbc.update("DELETE FROM purchasing_partner WHERE id = ?", partner);
    jdbc.update("DELETE FROM bank_account WHERE id = ?", account);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    jdbc.update("DELETE FROM app_user WHERE id = ?", delegate);
  }

  @Test
  void aDealCancellationAndTheWipeOnTheSameDealBothFinish() throws Exception {
    CountDownLatch dealLocked = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try (LockRace race = new LockRace(transactions, jdbc)) {
      Future<DealStatus> cancellation =
          race.start(
              null,
              () ->
                  transactions.execute(_ -> {
                    PurchasingDeal locked = dealService.lock(deal);
                    dealLocked.countDown();
                    await(proceed);
                    dealService.cancelWithinTransaction(locked, "MANUAL");
                    return locked.getStatus();
                  }));
      await(dealLocked);
      Future<BankCurrencyWipe.Effect> wipe =
          race.startWaiting(
              null, () -> transactions.execute(_ -> bankWipe.apply(LIVE_AT, patchChange)));
      proceed.countDown();

      assertThat(LockRace.finish(cancellation)).isEqualTo(DealStatus.CANCELLED);
      LockRace.finish(wipe);
    }

    Map<String, Object> cancelled =
        jdbc.queryForMap(
            "SELECT status, cancelled_by_patch_change FROM bank_booking_request WHERE id = ?",
            request);
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    assertThat(cancelled.get("cancelled_by_patch_change")).isNull();
    assertThat(dealRepository.findById(deal).orElseThrow().getStatus())
        .isEqualTo(DealStatus.CANCELLED);
  }

  /**
   * Persists a submitted deal of the delegate.
   *
   * @param ore the material it buys
   * @param counterparty the partner it buys from
   * @return the deal's id
   */
  private @NotNull UUID submittedDeal(
      @NotNull Material ore, @NotNull PurchasingPartner counterparty) {
    PurchasingDeal created = new PurchasingDeal();
    created.setNeedId(UUID.randomUUID());
    created.setRaisingOrgUnitId(Squadron.IRIDIUM_ID);
    created.setPartnerId(counterparty.getId());
    created.setDelegateUserId(delegate);
    created.setMaterial(ore);
    created.setAmount(5);
    created.setUnitPrice(100);
    created.setTotal(500);
    created.setPaymentModel(PaymentModel.ON_DELIVERY);
    created.setRiskClass(RiskClassCode.A);
    created.setStatus(DealStatus.SUBMITTED);
    return dealRepository.saveAndFlush(created).getId();
  }

  /**
   * Inserts the deal's pending purpose-bound request, submitted before the live time.
   *
   * @return the request's id
   */
  private @NotNull UUID pendingRequestBeforeTheLiveTime() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO bank_booking_request
            (id, account_id, type, amount, requester_handle, purpose_kind, purpose_ref,
             created_at, updated_at)
        VALUES (?, ?, 'DEPOSIT', 500, 'pur-wipe-requester', 'PURCHASING_DEAL', ?, ?, ?)
        """,
        id,
        account,
        deal,
        Timestamp.from(BEFORE),
        Timestamp.from(BEFORE));
    return id;
  }

  /**
   * Persists an active area account.
   *
   * @param name the account's name
   * @return its id
   */
  private @NotNull UUID newAccount(@NotNull String name) {
    BankAccount created = new BankAccount();
    created.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    created.setName(name);
    created.setType(BankAccountType.AREA);
    created.setAreaName(name);
    created.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.save(created).getId();
  }

  /**
   * Waits for a latch, failing the test when it is not counted down in time.
   *
   * @param latch the latch
   */
  private static void await(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("step reached in time")
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
