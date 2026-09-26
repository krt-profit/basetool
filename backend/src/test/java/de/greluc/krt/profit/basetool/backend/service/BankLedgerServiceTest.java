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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.SystemSetting;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.SystemSettingRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link BankLedgerService} against real Postgres on the two-ledger model
 * (ADR-0039): account-only overdraft guard under contention (REQ-BANK-006), Umbuchung, reversal,
 * wipe reset, append-only behaviour and one audit row per booking.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankLedgerServiceTest {

  private static final int THREADS = 4;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 60;

  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankTransactionRepository transactionRepository;
  @Autowired private BankPostingRepository postingRepository;
  @Autowired private BankHolderPostingRepository holderPostingRepository;
  @Autowired private BankAuditEventRepository auditEventRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SystemSettingRepository systemSettingRepository;

  private BankAccount account;
  private BankAccount otherAccount;
  private BankHolder holderA;
  private BankHolder holderB;

  /**
   * Seeds two fresh AREA accounts and two holders per test (unique names per run). AREA is used as
   * the neutral, justification-optional fixture (REQ-BANK-045): the general ledger tests must not
   * be entangled with the Begründung rule; the dedicated justification tests build a SPECIAL
   * account via {@link #newMandatingAccount(String)}.
   */
  @BeforeEach
  void seed() {
    account = newAccount("Test Konto " + UUID.randomUUID());
    otherAccount = newAccount("Gegenkonto " + UUID.randomUUID());
    holderA = newHolder("holder-a-" + UUID.randomUUID());
    holderB = newHolder("holder-b-" + UUID.randomUUID());
  }

  @Test
  void bookDeposit_createsAccountAndHolderLegAndExactlyOneAuditRow() {
    long auditBefore = auditEventRepository.count();

    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(), holderA.getId(), new BigDecimal("500"), "seed"));

    assertEquals(BankTransactionType.DEPOSIT, tx.type());
    assertEquals(0, storedFee(tx).signum(), "deposit is fee-free");
    assertEquals(0, balance(account).compareTo(new BigDecimal("500")));
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("500")));
    assertEquals(
        1, holderPostingRepository.findHolderLegsByTransactionIds(List.of(tx.id())).size());
    assertEquals(auditBefore + 1, auditEventRepository.count(), "exactly one audit row");
    assertTrue(auditEventRepository.existsByTransactionId(tx.id()));
  }

  @Test
  void bookWithdrawal_rejectsAccountOverdraftWithStableCode() {
    deposit(account, holderA, "100");

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookWithdrawal(
                    new BankWithdrawalRequest(
                        account.getId(), holderA.getId(), new BigDecimal("250"), null)));

    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
    assertEquals("100", ex.getProperties().get("available"));
    assertEquals(0, balance(account).compareTo(new BigDecimal("100")), "balance unchanged");
  }

  @Test
  void bookWithdrawal_fromMandatingAccount_blankJustification_rejected() {
    BankAccount special = newMandatingAccount("Sonderkonto " + UUID.randomUUID());
    deposit(special, holderA, "500");

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookWithdrawal(
                    new BankWithdrawalRequest(
                        special.getId(),
                        holderA.getId(),
                        new BigDecimal("100"),
                        "note",
                        "   ",
                        null,
                        null,
                        false)));

    assertEquals(BankConflictException.CODE_BANK_JUSTIFICATION_REQUIRED, ex.getCode());
    assertEquals(0, balance(special).compareTo(new BigDecimal("500")), "balance unchanged");
  }

  @Test
  void bookWithdrawal_fromMandatingAccount_persistsJustification() {
    BankAccount special = newMandatingAccount("Sonderkonto " + UUID.randomUUID());
    deposit(special, holderA, "500");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                special.getId(),
                holderA.getId(),
                new BigDecimal("100"),
                null,
                "Reparaturkosten",
                null,
                null,
                false));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals("Reparaturkosten", stored.getJustification());
  }

  @Test
  void bookDeposit_persistsStaffNote() {
    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("500"),
                "vom Verkauf",
                "in zwei Tranchen uebergeben",
                false,
                null,
                null,
                null,
                null));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals("in zwei Tranchen uebergeben", stored.getStaffNote());
    assertEquals("vom Verkauf", stored.getNote(), "the party's own note is untouched");
  }

  @Test
  void bookWithdrawal_persistsStaffNoteAlongsideJustification() {
    BankAccount special = newMandatingAccount("Sonderkonto " + UUID.randomUUID());
    deposit(special, holderA, "500");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                special.getId(),
                holderA.getId(),
                new BigDecimal("100"),
                null,
                "Reparaturkosten",
                "nach Ruecksprache mit der SL",
                null,
                null,
                false,
                null));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals("Reparaturkosten", stored.getJustification());
    assertEquals("nach Ruecksprache mit der SL", stored.getStaffNote());
  }

  @Test
  void bookWithdrawal_allowsHolderToGoNegativeWhenAccountCovers() {
    deposit(account, holderA, "300");
    deposit(account, holderB, "700");

    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holderA.getId(), new BigDecimal("400"), null));

    assertEquals(0, balance(account).compareTo(new BigDecimal("598")));
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("-102")));
  }

  @Test
  void bookWithdrawal_addsFeeOnTopSoTheAccountBearsItAndTheRecipientGetsTheFullAmount() {
    deposit(account, holderA, "1005");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                account.getId(), holderA.getId(), new BigDecimal("1000"), null));

    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    assertEquals(0, balance(account).signum(), "account bore the gross 1005");
    assertEquals(0, holderTotal(holderA).signum(), "holder bore the gross 1005");
  }

  @Test
  void bookWithdrawal_rejectsWhenTheFeeOnTopWouldOverdrawTheAccount() {
    deposit(account, holderA, "1000");

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookWithdrawal(
                    new BankWithdrawalRequest(
                        account.getId(), holderA.getId(), new BigDecimal("1000"), null)));
    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
    assertEquals(0, balance(account).compareTo(new BigDecimal("1000")), "balance unchanged");
  }

  @Test
  void bookTransfer_sameHolder_isFeeFreeAndLegsSumToZero() {
    deposit(account, holderA, "1000");

    BankTransactionDto tx =
        bankLedgerService.bookTransfer(
            new BankTransferRequest(
                account.getId(),
                holderA.getId(),
                otherAccount.getId(),
                holderA.getId(),
                new BigDecimal("400"),
                "Umschichtung"),
            true);

    List<BankCounterLeg> accountLegs = postingRepository.findLegsByTransactionIds(List.of(tx.id()));
    assertEquals(2, accountLegs.size());
    assertEquals(0, sum(accountLegs.stream().map(BankCounterLeg::amount).toList()).signum());
    assertEquals(0, storedFee(tx).signum(), "same-holder transfer is fee-free");
    assertEquals(0, balance(account).compareTo(new BigDecimal("600")));
    assertEquals(0, balance(otherAccount).compareTo(new BigDecimal("400")));
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("1000")), "custody unchanged");
  }

  @Test
  void bookTransfer_holderChange_addsFeeOnTopAndCreditsFullAmountToDestination() {
    deposit(account, holderA, "1005");

    BankTransactionDto tx =
        bankLedgerService.bookTransfer(
            new BankTransferRequest(
                account.getId(),
                holderA.getId(),
                otherAccount.getId(),
                holderB.getId(),
                new BigDecimal("1000"),
                "Bereichsanteil"),
            true);

    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    List<BankCounterLeg> accountLegs = postingRepository.findLegsByTransactionIds(List.of(tx.id()));
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
    assertEquals(0, balance(account).signum(), "source debited the gross 1005");
    assertEquals(
        0,
        balance(otherAccount).compareTo(new BigDecimal("1000")),
        "destination gets the full entered amount");
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("1000")));
  }

  @Test
  void bookWithdrawal_feeInclusive_debitsEnteredAmountAndRecipientGetsAmountMinusFee() {
    deposit(account, holderA, "1000");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("1000"),
                null,
                null,
                null,
                null,
                true));

    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    assertEquals(0, balance(account).signum(), "account debited exactly the entered 1000");
    assertEquals(0, holderTotal(holderA).signum(), "holder debited exactly the entered 1000");
  }

  @Test
  void bookTransfer_feeInclusive_debitsEnteredAmountAndCreditsAmountMinusFee() {
    deposit(account, holderA, "1000");

    BankTransactionDto tx =
        bankLedgerService.bookTransfer(
            new BankTransferRequest(
                account.getId(),
                holderA.getId(),
                otherAccount.getId(),
                holderB.getId(),
                new BigDecimal("1000"),
                "Bereichsanteil",
                null,
                null,
                true),
            true);

    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    List<BankCounterLeg> accountLegs = postingRepository.findLegsByTransactionIds(List.of(tx.id()));
    assertEquals(
        0,
        sum(accountLegs.stream().map(BankCounterLeg::amount).toList())
            .compareTo(new BigDecimal("-5")),
        "account legs net to -fee");
    List<BankHolderLeg> holderLegs =
        holderPostingRepository.findHolderLegsByTransactionIds(List.of(tx.id()));
    assertEquals(
        0,
        sum(holderLegs.stream().map(BankHolderLeg::amount).toList())
            .compareTo(new BigDecimal("-5")),
        "holder legs net to -fee");
    assertEquals(0, balance(account).signum(), "source debited exactly the entered 1000");
    assertEquals(
        0, balance(otherAccount).compareTo(new BigDecimal("995")), "destination gets amount - fee");
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("995")));
  }

  @Test
  void bookWithdrawal_feeInclusive_rejectsWhenAmountDoesNotExceedFee() {
    SystemSetting rate =
        systemSettingRepository.findById("operation.transfer_fee_rate").orElseThrow();
    String original = rate.getValue();
    rate.setValue("0.99");
    systemSettingRepository.saveAndFlush(rate);
    try {
      deposit(account, holderA, "10");

      BankConflictException ex =
          assertThrows(
              BankConflictException.class,
              () ->
                  bankLedgerService.bookWithdrawal(
                      new BankWithdrawalRequest(
                          account.getId(),
                          holderA.getId(),
                          new BigDecimal("1"),
                          null,
                          null,
                          null,
                          null,
                          true)));
      assertEquals(BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT, ex.getCode());
      assertEquals(0, balance(account).compareTo(new BigDecimal("10")), "balance unchanged");
    } finally {
      SystemSetting reset =
          systemSettingRepository.findById("operation.transfer_fee_rate").orElseThrow();
      reset.setValue(original);
      systemSettingRepository.saveAndFlush(reset);
    }
  }

  @Test
  void bookTransfer_rejectsSameAccount() {
    deposit(account, holderA, "100");

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookTransfer(
                    new BankTransferRequest(
                        account.getId(),
                        holderA.getId(),
                        account.getId(),
                        holderB.getId(),
                        new BigDecimal("50"),
                        null),
                    true));
    assertEquals(BankConflictException.CODE_BANK_SELF_TRANSFER, ex.getCode());
  }

  @Test
  void bookHolderTransfer_tinyAmount_isFeeFreeAndBooksNoAccountLeg() {
    deposit(account, holderA, "800");
    BigDecimal accountBefore = balance(account);
    long auditBefore = auditEventRepository.count();

    BankTransactionDto tx =
        bankLedgerService.bookHolderTransfer(
            new BankHolderTransferRequest(
                holderA.getId(), holderB.getId(), new BigDecimal("50"), "Schichtwechsel"));

    assertEquals(BankTransactionType.HOLDER_TRANSFER, tx.type());
    assertEquals(0, storedFee(tx).signum(), "a tiny Umbuchung whose fee rounds to 0 is fee-free");
    assertEquals(0, balance(account).compareTo(accountBefore), "account balance unchanged");
    assertTrue(postingRepository.findLegsByTransactionIds(List.of(tx.id())).isEmpty());
    List<BankHolderLeg> holderLegs =
        holderPostingRepository.findHolderLegsByTransactionIds(List.of(tx.id()));
    assertEquals(2, holderLegs.size());
    assertEquals(0, sum(holderLegs.stream().map(BankHolderLeg::amount).toList()).signum());
    assertEquals(
        0, holderTotal(holderA).compareTo(new BigDecimal("750")), "source debited 50 (800 - 50)");
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("50")), "destination gets 50");
    assertEquals(auditBefore + 1, auditEventRepository.count());
    assertTrue(auditEventRepository.existsByTransactionId(tx.id()));
  }

  @Test
  void bookHolderTransfer_rejectsSameHolder() {
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookHolderTransfer(
                    new BankHolderTransferRequest(
                        holderA.getId(), holderA.getId(), new BigDecimal("10"), null)));
    assertEquals(BankConflictException.CODE_BANK_SELF_TRANSFER, ex.getCode());
  }

  @Test
  void bookHolderTransfer_allowsNegativeSourceAndDeactivatedHolders() {
    holderB.setActive(false);
    holderRepository.save(holderB);

    bankLedgerService.bookHolderTransfer(
        new BankHolderTransferRequest(
            holderA.getId(), holderB.getId(), new BigDecimal("50"), null));

    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("-50")));
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("50")));
  }

  @Test
  void bookDeposit_rejectsClosedAccount() {
    account.setStatus(BankAccountStatus.CLOSED);
    accountRepository.save(account);

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookDeposit(
                    new BankDepositRequest(
                        account.getId(), holderA.getId(), new BigDecimal("10"), null)));
    assertEquals(BankConflictException.CODE_BANK_ACCOUNT_CLOSED, ex.getCode());
  }

  @Test
  void bookDeposit_rejectsInactiveHolder() {
    holderA.setActive(false);
    holderRepository.save(holderA);

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookDeposit(
                    new BankDepositRequest(
                        account.getId(), holderA.getId(), new BigDecimal("10"), null)));
    assertEquals(BankConflictException.CODE_BANK_HOLDER_INACTIVE, ex.getCode());
  }

  @Test
  void reverseTransaction_createsNegatedMirrorOnBothLedgersAndKeepsOriginalUntouched() {
    deposit(account, holderA, "1000");
    BankTransactionDto transfer =
        bankLedgerService.bookTransfer(
            new BankTransferRequest(
                account.getId(),
                holderA.getId(),
                otherAccount.getId(),
                holderB.getId(),
                new BigDecimal("400"),
                null),
            true);
    List<BankCounterLeg> originalAccountLegs =
        postingRepository.findLegsByTransactionIds(List.of(transfer.id()));
    long postingsBefore = postingRepository.count();
    long holderPostingsBefore = holderPostingRepository.count();

    BankTransactionDto reversal =
        bankLedgerService.reverseTransaction(transfer.id(), "Tippfehler korrigiert");

    List<BankCounterLeg> reversalAccountLegs =
        postingRepository.findLegsByTransactionIds(List.of(reversal.id()));
    assertEquals(originalAccountLegs.size(), reversalAccountLegs.size());
    for (BankCounterLeg original : originalAccountLegs) {
      assertTrue(
          reversalAccountLegs.stream()
              .anyMatch(
                  mirrored ->
                      mirrored.accountId().equals(original.accountId())
                          && mirrored.amount().compareTo(original.amount().negate()) == 0),
          "every original account leg must have a negated mirror");
    }
    assertEquals(postingsBefore + reversalAccountLegs.size(), postingRepository.count());
    assertEquals(holderPostingsBefore + 2, holderPostingRepository.count(), "holder legs mirrored");
    assertEquals(
        originalAccountLegs.size(),
        postingRepository.findLegsByTransactionIds(List.of(transfer.id())).size(),
        "original legs survive unchanged");
    assertEquals(0, balance(account).compareTo(new BigDecimal("1000")), "balances restored");
    assertEquals(0, balance(otherAccount).signum());
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("1000")));
    assertEquals(0, holderTotal(holderB).signum());
  }

  @Test
  void reverseTransaction_rejectsSecondReversal() {
    BankTransactionDto deposit = deposit(account, holderA, "100");
    bankLedgerService.reverseTransaction(deposit.id(), null);

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(deposit.id(), null));
    assertEquals(BankConflictException.CODE_BANK_ALREADY_REVERSED, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsWhenAccountWouldGoNegative() {
    BankTransactionDto deposit = deposit(account, holderA, "201");
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holderA.getId(), new BigDecimal("200"), null));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(deposit.id(), null));
    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsReversingAWipeReset() {
    BankTransaction wipe =
        transactionRepository.save(
            BankTransaction.builder()
                .type(BankTransactionType.WIPE_RESET)
                .createdAt(Instant.now())
                .build());

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(wipe.getId(), null));
    assertEquals(BankConflictException.CODE_BANK_NOT_REVERSIBLE, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsReversingAReversal() {
    BankTransactionDto deposit = deposit(account, holderA, "100");
    BankTransactionDto reversal = bankLedgerService.reverseTransaction(deposit.id(), null);

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(reversal.id(), null));
    assertEquals(BankConflictException.CODE_BANK_NOT_REVERSIBLE, ex.getCode());
  }

  @Test
  void resetAllBalances_zeroesAccountsAndHoldersKeepsHistoryAndIsIdempotent() {
    deposit(account, holderA, "300");
    deposit(account, holderB, "700");
    deposit(otherAccount, holderB, "250");
    long postingsBefore = postingRepository.count();

    bankLedgerService.resetAllBalances();

    assertEquals(0, balance(account).signum());
    assertEquals(0, balance(otherAccount).signum());
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).signum());
    assertTrue(postingRepository.count() > postingsBefore, "history preserved, postings added");

    bankLedgerService.resetAllBalances();
    assertEquals(0, balance(account).signum());
    assertEquals(0, balance(otherAccount).signum());
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).signum());
  }

  @Test
  void concurrentWithdrawals_cannotJointlyOverdrawTheAccount() throws Exception {
    deposit(account, holderA, "500");
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              try {
                if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                  return;
                }
                bankLedgerService.bookWithdrawal(
                    new BankWithdrawalRequest(
                        account.getId(), holderA.getId(), new BigDecimal("200"), null));
                success.incrementAndGet();
              } catch (BankConflictException expected) {
                conflict.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertTrue(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      go.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertEquals(2, success.get(), "exactly two withdrawals fit into the balance");
    assertEquals(THREADS - 2, conflict.get());
    assertEquals(0, balance(account).compareTo(new BigDecimal("98")));
  }

  @Test
  void auditRows_oneRowPerSuccessfulBookingAndNoneForRejections() {
    long before = auditEventRepository.count();

    BankTransactionDto deposit = deposit(account, holderA, "1000");
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holderA.getId(), new BigDecimal("100"), null));
    bankLedgerService.bookTransfer(
        new BankTransferRequest(
            account.getId(),
            holderA.getId(),
            otherAccount.getId(),
            holderB.getId(),
            new BigDecimal("200"),
            null),
        true);
    bankLedgerService.bookHolderTransfer(
        new BankHolderTransferRequest(
            holderA.getId(), holderB.getId(), new BigDecimal("50"), null));
    assertThrows(
        BankConflictException.class,
        () -> bankLedgerService.reverseTransaction(deposit.id(), null));

    assertEquals(before + 4, auditEventRepository.count(), "one audit row per successful booking");
  }

  @Test
  void bookDeposit_recordsCounterpartyUserHandleAndOrgUnitSnapshotAndAuditTarget() {
    User depositor = newUser("einzahler");
    Squadron staffel = newSquadron("Staffel " + UUID.randomUUID());
    linkMembership(depositor, staffel);

    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("500"),
                null,
                depositor.getId(),
                staffel.getId()));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals(depositor.getId(), stored.getCounterpartyUserId());
    assertEquals(depositor.getEffectiveName(), stored.getCounterpartyHandle());
    assertEquals(staffel.getId(), stored.getCounterpartyOrgUnitId());
    assertEquals(staffel.getName(), stored.getCounterpartyOrgUnitName());
    BankAuditEvent audit = auditForTransaction(account.getId(), tx.id());
    assertEquals(depositor.getId(), audit.getTargetUserId());
    assertTrue(audit.getDetails().contains(depositor.getEffectiveName()));
    assertTrue(audit.getDetails().contains(staffel.getName()));
  }

  @Test
  void bookWithdrawal_recordsCounterpartyUserWithoutOrgUnitAndAuditTarget() {
    deposit(account, holderA, "500");
    User recipient = newUser("empfaenger");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("100"),
                null,
                null,
                recipient.getId(),
                null,
                false));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals(recipient.getId(), stored.getCounterpartyUserId());
    assertEquals(recipient.getEffectiveName(), stored.getCounterpartyHandle());
    assertNull(stored.getCounterpartyOrgUnitId());
    assertNull(stored.getCounterpartyOrgUnitName());
    assertEquals(
        recipient.getId(), auditForTransaction(account.getId(), tx.id()).getTargetUserId());
  }

  @Test
  void bookDeposit_rejectsCounterpartyOrgUnitThatIsNotAMembership() {
    User depositor = newUser("fremd");
    Squadron unrelated = newSquadron("Fremd " + UUID.randomUUID());

    assertThrows(
        BadRequestException.class,
        () ->
            bankLedgerService.bookDeposit(
                new BankDepositRequest(
                    account.getId(),
                    holderA.getId(),
                    new BigDecimal("10"),
                    null,
                    depositor.getId(),
                    unrelated.getId())));
  }

  @Test
  void bookDeposit_withoutCounterparty_leavesHeaderFieldsAndAuditTargetNull() {
    BankTransactionDto tx = deposit(account, holderA, "200");

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertNull(stored.getCounterpartyUserId());
    assertNull(stored.getCounterpartyHandle());
    assertNull(stored.getCounterpartyOrgUnitId());
    assertNull(stored.getCounterpartyOrgUnitName());
    assertNull(auditForTransaction(account.getId(), tx.id()).getTargetUserId());
  }

  @Test
  void bookDeposit_recordsExternalCounterpartyNameAndAnyOrgUnitWithoutUserId() {
    Squadron anyStaffel = newSquadron("Staffel " + UUID.randomUUID());

    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("500"),
                null,
                null,
                false,
                null,
                null,
                anyStaffel.getId(),
                "Max Mustermann"));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertNull(stored.getCounterpartyUserId(), "an external counterparty has no user FK");
    assertEquals("Max Mustermann", stored.getCounterpartyHandle());
    assertEquals(anyStaffel.getId(), stored.getCounterpartyOrgUnitId());
    assertEquals(anyStaffel.getName(), stored.getCounterpartyOrgUnitName());
    BankAuditEvent audit = auditForTransaction(account.getId(), tx.id());
    assertNull(audit.getTargetUserId(), "no target user for an external counterparty");
    assertTrue(audit.getDetails().contains("Max Mustermann"));
    assertTrue(audit.getDetails().contains(anyStaffel.getName()));
  }

  @Test
  void bookWithdrawal_externalCounterparty_nameOnly_snapshotsNameWithNullUserAndOrgUnit() {
    deposit(account, holderA, "500");

    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("100"),
                null,
                null,
                null,
                null,
                null,
                false,
                "Erika Extern"));

    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertNull(stored.getCounterpartyUserId());
    assertEquals("Erika Extern", stored.getCounterpartyHandle());
    assertNull(stored.getCounterpartyOrgUnitId());
    assertNull(stored.getCounterpartyOrgUnitName());
    assertNull(auditForTransaction(account.getId(), tx.id()).getTargetUserId());
  }

  @Test
  void bookDeposit_rejectsBothRegisteredAndExternalCounterparty() {
    User depositor = newUser("doppelt");

    assertThrows(
        BadRequestException.class,
        () ->
            bankLedgerService.bookDeposit(
                new BankDepositRequest(
                    account.getId(),
                    holderA.getId(),
                    new BigDecimal("10"),
                    null,
                    null,
                    false,
                    null,
                    depositor.getId(),
                    null,
                    "Max Mustermann")));
  }

  /** Books a deposit through the service (the canonical seeding path). */
  private BankTransactionDto deposit(BankAccount target, BankHolder holder, String amount) {
    return bankLedgerService.bookDeposit(
        new BankDepositRequest(target.getId(), holder.getId(), new BigDecimal(amount), null));
  }

  private BigDecimal balance(BankAccount target) {
    return postingRepository.accountBalance(target.getId());
  }

  private BigDecimal holderTotal(BankHolder holder) {
    return holderPostingRepository.holderTotal(holder.getId());
  }

  /** Reads the in-game transfer fee recorded on a persisted transaction (ADR-0052). */
  private BigDecimal storedFee(BankTransactionDto tx) {
    return transactionRepository.findById(tx.id()).orElseThrow().getTransferFee();
  }

  private static BigDecimal sum(List<BigDecimal> amounts) {
    return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BankAccount newAccount(String name) {
    BankAccount a = new BankAccount();
    a.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    a.setName(name);
    a.setType(BankAccountType.AREA);
    a.setAreaName(name);
    a.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(a);
    assertNotNull(saved.getId());
    return saved;
  }

  /**
   * Seeds an active {@code SPECIAL} account, a {@linkplain
   * BankAccountType#requiresDebitJustification() justification-mandating} type (REQ-BANK-045).
   *
   * @param name the display name
   * @return the persisted SPECIAL account
   */
  private BankAccount newMandatingAccount(String name) {
    BankAccount a = new BankAccount();
    a.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    a.setName(name);
    a.setType(BankAccountType.SPECIAL);
    a.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(a);
    assertNotNull(saved.getId());
    return saved;
  }

  private BankHolder newHolder(String handle) {
    BankHolder h = new BankHolder();
    h.setHandle(handle);
    h.setActive(true);
    return holderRepository.save(h);
  }

  /**
   * Resolves the account-scoped audit row for one booking transaction (the account is freshly
   * seeded per test, so the filter is unambiguous).
   */
  private BankAuditEvent auditForTransaction(UUID accountId, UUID transactionId) {
    return auditEventRepository
        .findFiltered(null, null, null, accountId, null, null, PageRequest.of(0, 50))
        .getContent()
        .stream()
        .filter(event -> transactionId.equals(event.getTransactionId()))
        .findFirst()
        .orElseThrow();
  }

  /** Creates a minimal persisted tool user; its username doubles as the effective-name handle. */
  private User newUser(String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(true);
    return userRepository.save(user);
  }

  /** Creates a persisted Staffel — an {@code org_unit} row, so it satisfies the counterparty FK. */
  private Squadron newSquadron(String name) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand("S" + UUID.randomUUID().toString().substring(0, 8));
    return squadronRepository.save(squadron);
  }

  /** Links a user to a Staffel as a plain member (the {@code kind} column is trigger-managed). */
  private void linkMembership(User user, Squadron squadron) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), squadron.getId()));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }
}
