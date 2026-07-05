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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated integration tests for the fee-bearing holder→holder Umbuchung (REQ-BANK-031, #998): the
 * in-game transfer fee is borne by, and directly debited from, the KRT ({@code CARTEL}) account,
 * while the source holder's custody is reduced by the fee. Unlike the shared-state {@link
 * BankLedgerServiceTest}, this class is {@link Transactional} (rolled back per test) so each test
 * can create the <strong>singleton</strong> CARTEL account in a clean slate without colliding with
 * the {@code uq_bank_account_singleton_cartel} index across tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BankHolderTransferFeeTest {

  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankLedgerIntegrityService integrityService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankTransactionRepository transactionRepository;
  @Autowired private BankPostingRepository postingRepository;
  @Autowired private BankHolderPostingRepository holderPostingRepository;

  private BankHolder holderA;
  private BankHolder holderB;

  /** Seeds the two Umbuchung holders per test; the CARTEL account is created per test as needed. */
  @BeforeEach
  void seed() {
    holderA = newHolder("umb-a-" + UUID.randomUUID());
    holderB = newHolder("umb-b-" + UUID.randomUUID());
  }

  @Test
  void feeBearingUmbuchung_debitsSourceGrossCreditsDestAndDebitsCartel() {
    // Given (REQ-BANK-031, #998): a CARTEL account funded with 100 to bear the fee
    BankAccount cartel = newCartelAccount();
    fund(cartel, "100");

    // When: A hands 1000 of custody to B — fee = round(1000 * 0.005) = 5
    BankTransactionDto tx =
        bankLedgerService.bookHolderTransfer(
            new BankHolderTransferRequest(
                holderA.getId(), holderB.getId(), new BigDecimal("1000"), "Schichtwechsel"));

    // Then: source holder debited the gross 1005, destination credited 1000, the CARTEL account
    // debited exactly the fee 5 (100 -> 95); transfer_fee = 5.
    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    assertEquals(
        0, holderTotal(holderA).compareTo(new BigDecimal("-1005")), "source bears the fee");
    assertEquals(
        0, holderTotal(holderB).compareTo(new BigDecimal("1000")), "destination gets 1000");
    assertEquals(0, balance(cartel).compareTo(new BigDecimal("95")), "CARTEL debited the fee 5");
    // The single CARTEL account leg nets to -fee; the two holder legs net to -fee (integrity
    // holds).
    List<BankCounterLeg> accountLegs = postingRepository.findLegsByTransactionIds(List.of(tx.id()));
    assertEquals(1, accountLegs.size(), "one CARTEL account leg");
    assertEquals(
        0,
        sum(accountLegs.stream().map(BankCounterLeg::amount).toList())
            .compareTo(new BigDecimal("-5")));
    List<BankHolderLeg> holderLegs =
        holderPostingRepository.findHolderLegsByTransactionIds(List.of(tx.id()));
    assertEquals(
        0,
        sum(holderLegs.stream().map(BankHolderLeg::amount).toList())
            .compareTo(new BigDecimal("-5")));
    // The integrity sweep accepts the fee-bearing Umbuchung (no false-fire).
    assertTrue(integrityService.verify().isSound(), "integrity sweep stays sound");
  }

  @Test
  void feeBearingUmbuchung_rejectsWhenCartelAccountMissing() {
    // Given: no CARTEL account exists. When / Then: a fee-bearing Umbuchung is refused.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookHolderTransfer(
                    new BankHolderTransferRequest(
                        holderA.getId(), holderB.getId(), new BigDecimal("1000"), null)));
    assertEquals(BankConflictException.CODE_BANK_ACCOUNT_CLOSED, ex.getCode());
  }

  @Test
  void feeBearingUmbuchung_rejectsWhenCartelAccountClosed() {
    // Given: the CARTEL account is CLOSED
    BankAccount cartel = newCartelAccount();
    cartel.setStatus(BankAccountStatus.CLOSED);
    accountRepository.save(cartel);

    // When / Then: the fee cannot be charged to a closed CARTEL account
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookHolderTransfer(
                    new BankHolderTransferRequest(
                        holderA.getId(), holderB.getId(), new BigDecimal("1000"), null)));
    assertEquals(BankConflictException.CODE_BANK_ACCOUNT_CLOSED, ex.getCode());
  }

  @Test
  void feeBearingUmbuchung_rejectsWhenCartelWouldOverdraw() {
    // Given: the CARTEL account holds only 2 — less than the fee 5
    BankAccount cartel = newCartelAccount();
    fund(cartel, "2");

    // When / Then: the fee would drive the CARTEL account negative, so the Umbuchung is refused
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookHolderTransfer(
                    new BankHolderTransferRequest(
                        holderA.getId(), holderB.getId(), new BigDecimal("1000"), null)));
    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
    assertEquals(0, balance(cartel).compareTo(new BigDecimal("2")), "CARTEL balance unchanged");
  }

  @Test
  void reversalOfFeeBearingUmbuchung_negatesAllThreeLegsAndStaysSound() {
    // Given: a booked fee-bearing Umbuchung (CARTEL 95, A -1005, B +1000)
    BankAccount cartel = newCartelAccount();
    fund(cartel, "100");
    BankTransactionDto umbuchung =
        bankLedgerService.bookHolderTransfer(
            new BankHolderTransferRequest(
                holderA.getId(), holderB.getId(), new BigDecimal("1000"), null));

    // When: the Umbuchung is reversed
    bankLedgerService.reverseTransaction(umbuchung.id(), "correction");

    // Then: all three legs (both holder legs + the CARTEL account leg) are negated — CARTEL back to
    // 100, both holders back to zero — and the ledger stays sound.
    assertEquals(0, balance(cartel).compareTo(new BigDecimal("100")), "CARTEL restored");
    assertEquals(0, holderTotal(holderA).signum(), "source restored");
    assertEquals(0, holderTotal(holderB).signum(), "destination restored");
    assertTrue(integrityService.verify().isSound());
  }

  // ---- fixtures --------------------------------------------------------------------------------

  /**
   * Creates the persisted, active singleton CARTEL account (no org unit / area name, V168 CHECK).
   */
  private BankAccount newCartelAccount() {
    BankAccount a = new BankAccount();
    a.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    a.setName("KRT " + UUID.randomUUID());
    a.setType(BankAccountType.CARTEL);
    a.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(a);
    assertNotNull(saved.getId());
    return saved;
  }

  /** Creates a persisted active holder. */
  private BankHolder newHolder(String handle) {
    BankHolder h = new BankHolder();
    h.setHandle(handle);
    h.setActive(true);
    return holderRepository.save(h);
  }

  /** Funds an account with a deposit (its own throwaway holder) so it can bear the fee. */
  private void fund(BankAccount account, String amount) {
    BankHolder funder = newHolder("fund-" + UUID.randomUUID());
    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), funder.getId(), new BigDecimal(amount), null));
  }

  private BigDecimal balance(BankAccount account) {
    return postingRepository.accountBalance(account.getId());
  }

  private BigDecimal holderTotal(BankHolder holder) {
    return holderPostingRepository.holderTotal(holder.getId());
  }

  private BigDecimal storedFee(BankTransactionDto tx) {
    return transactionRepository.findById(tx.id()).orElseThrow().getTransferFee();
  }

  private static BigDecimal sum(List<BigDecimal> amounts) {
    return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
