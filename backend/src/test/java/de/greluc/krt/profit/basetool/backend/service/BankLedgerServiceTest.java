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
 * Integration tests for {@link BankLedgerService} against the real Testcontainers PostgreSQL, on
 * the decoupled two-ledger model (ADR-0039): account and holder legs per booking, the
 * <strong>account-only</strong> no-overdraft guard under real concurrent contention (REQ-BANK-006 —
 * holders may go negative), the holder→holder Umbuchung (REQ-BANK-031), the negated-mirror reversal
 * across both ledgers (ADR-0010/0039), the wipe reset (REQ-BANK-013), append-only behavior and the
 * one-audit-row-per-booking rule (REQ-BANK-012).
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
    // Given
    long auditBefore = auditEventRepository.count();

    // When
    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(), holderA.getId(), new BigDecimal("500"), "seed"));

    // Then: one account leg (+500) and one holder leg (+500 global); a deposit is fee-free (the
    // depositor bears their own in-game fee, REQ-BANK-033, ADR-0052)
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
    // Given: the account holds 100
    deposit(account, holderA, "100");

    // When: withdraw 250 — the account cannot cover it
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookWithdrawal(
                    new BankWithdrawalRequest(
                        account.getId(), holderA.getId(), new BigDecimal("250"), null)));

    // Then
    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
    assertEquals("100", ex.getProperties().get("available"));
    assertEquals(0, balance(account).compareTo(new BigDecimal("100")), "balance unchanged");
  }

  @Test
  void bookWithdrawal_fromMandatingAccount_blankJustification_rejected() {
    // REQ-BANK-045: a direct withdrawal leaving a CARTEL / CARTEL_BANK / SPECIAL account must carry
    // a
    // non-blank Begründung; a blank one is rejected with BANK_JUSTIFICATION_REQUIRED and the
    // balance
    // is untouched.
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
    // REQ-BANK-045: a non-blank Begründung is stored on the ledger transaction header so it
    // surfaces
    // in the booking history and the statement PDF.
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
  void bookWithdrawal_allowsHolderToGoNegativeWhenAccountCovers() {
    // Given: account holds 1000 (A:300, B:700) — the account covers a 400 payout plus its fee,
    // holder A does not
    deposit(account, holderA, "300");
    deposit(account, holderB, "700");

    // When: A pays out 400 to a recipient — the fee (round(400 * 0.005) = 2) is added on top
    // (ADR-0052), so the account and holder A are debited the gross 402; A fronts the missing 102
    // (no holder overdraft, ADR-0039)
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holderA.getId(), new BigDecimal("400"), null));

    // Then: the account drops by the gross 402, holder A's GLOBAL balance goes negative
    assertEquals(0, balance(account).compareTo(new BigDecimal("598")));
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("-102")));
  }

  @Test
  void bookWithdrawal_addsFeeOnTopSoTheAccountBearsItAndTheRecipientGetsTheFullAmount() {
    // Given (REQ-BANK-033, ADR-0052): the entered amount is what the recipient must receive; the
    // 0.5% fee is added on top and borne by the debited account. A 1000 payout needs 1005 in the
    // account (1000 + round(1000 * 0.005) = 5 fee).
    deposit(account, holderA, "1005");

    // When: pay out 1000
    BankTransactionDto tx =
        bankLedgerService.bookWithdrawal(
            new BankWithdrawalRequest(
                account.getId(), holderA.getId(), new BigDecimal("1000"), null));

    // Then: the account and holder are debited the gross 1005; the recorded fee is 5 and the
    // recipient effectively received the full 1000 (gross - fee)
    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    assertEquals(0, balance(account).signum(), "account bore the gross 1005");
    assertEquals(0, holderTotal(holderA).signum(), "holder bore the gross 1005");
  }

  @Test
  void bookWithdrawal_rejectsWhenTheFeeOnTopWouldOverdrawTheAccount() {
    // Given: the account holds exactly the entered amount but not the fee on top (ADR-0052)
    deposit(account, holderA, "1000");

    // When / Then: a 1000 payout needs 1005 (1000 + 5 fee); the account cannot cover the gross, so
    // the booking is refused rather than driving the account negative (REQ-BANK-006)
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
    // Given (REQ-BANK-033): a same-holder transfer moves no money in-game (the holder just
    // re-labels which account owns it), so it carries no fee and both legs net to zero.
    deposit(account, holderA, "1000");

    // When: move 400 to another account, but custody stays with holder A
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

    // Then: two account legs summing to zero AND two holder legs summing to zero; no fee recorded
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
    // Given (REQ-BANK-033, ADR-0052): a transfer that changes the holder is a real in-game send, so
    // the 0.5% fee (seeded operation.transfer_fee_rate) is added on top — the source is debited the
    // gross (amount + fee), the destination credited the full entered amount, and the two legs net
    // to -fee. Moving 1000 needs 1005 in the source.
    deposit(account, holderA, "1005");

    // When: deliver 1000 to another account AND another holder
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

    // Then: fee = round(1000 * 0.005) = 5; the source is debited the gross 1005, the destination
    // receives the full 1000, the legs net to -5
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
    // Given (REQ-BANK-033, #999): in the fee-inclusive mode the entered amount is what is DEBITED
    // and
    // the recipient gets amount - fee. A 1000 inclusive payout at 0.5% debits exactly 1000 (not
    // 1005), records fee 5, and the recipient effectively receives 995 (the 500000 -> 497500
    // example
    // scaled down). Only the entered amount needs to be in the account.
    deposit(account, holderA, "1000");

    // When: pay out 1000 with fee-inclusive on
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

    // Then: the recorded fee is still 5, but the account/holder are debited exactly the entered
    // 1000
    // (balance 0), so the recipient got amount - fee = 995.
    assertEquals(0, storedFee(tx).compareTo(new BigDecimal("5")));
    assertEquals(0, balance(account).signum(), "account debited exactly the entered 1000");
    assertEquals(0, holderTotal(holderA).signum(), "holder debited exactly the entered 1000");
  }

  @Test
  void bookTransfer_feeInclusive_debitsEnteredAmountAndCreditsAmountMinusFee() {
    // Given (REQ-BANK-033, #999): a holder-changing transfer in the fee-inclusive mode debits the
    // source exactly the entered amount and credits the destination amount - fee; the account legs
    // still net to -fee, so the ledger-integrity invariant holds. Moving 1000 needs only 1000 in
    // the
    // source (not 1005).
    deposit(account, holderA, "1000");

    // When: deliver 1000 (fee-inclusive) to another account AND another holder
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
                true),
            true);

    // Then: fee = 5; source debited exactly 1000 (balance 0), destination credited 995, and both
    // the
    // account legs and the holder legs still net to -5 (integrity preserved).
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
    // Given (REQ-BANK-033, #999 decision #4): under a punitive fee rate the whole entered amount is
    // consumed by the fee, so nothing would arrive in the fee-inclusive mode. fee = round(1 * 0.99)
    // =
    // 1, so amount - fee = 0. The rate is a global setting seeded by V79; flip it for this test and
    // restore it afterwards so no other test sees the punitive rate.
    SystemSetting rate =
        systemSettingRepository.findById("operation.transfer_fee_rate").orElseThrow();
    String original = rate.getValue();
    rate.setValue("0.99");
    systemSettingRepository.saveAndFlush(rate);
    try {
      deposit(account, holderA, "10");

      // When / Then: a fee-inclusive payout of 1 is refused before the overdraft check, balance
      // intact
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
    // Given
    deposit(account, holderA, "100");

    // When / Then: an account-to-account transfer must target a DIFFERENT account
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
  void bookHolderTransfer_movesGlobalCustodyWithNoAccountLegAndIsFeeFree() {
    // Given: holder A holds 800 (via a deposit onto the account)
    deposit(account, holderA, "800");
    BigDecimal accountBefore = balance(account);
    long auditBefore = auditEventRepository.count();

    // When: A hands 400 of physical custody to B — no account is touched. The internal Umbuchung is
    // fee-free (REQ-BANK-031, ADR-0052): the staff bear any in-game fee personally, so A is debited
    // exactly 400 and B credited exactly 400.
    BankTransactionDto tx =
        bankLedgerService.bookHolderTransfer(
            new BankHolderTransferRequest(
                holderA.getId(), holderB.getId(), new BigDecimal("400"), "Schichtwechsel"));

    // Then: only holder balances move; the account is untouched and the tx books no account leg;
    // no fee is recorded and the two holder legs net to zero.
    assertEquals(BankTransactionType.HOLDER_TRANSFER, tx.type());
    assertEquals(0, storedFee(tx).signum(), "holder Umbuchung is fee-free");
    assertEquals(0, balance(account).compareTo(accountBefore), "account balance unchanged");
    assertTrue(postingRepository.findLegsByTransactionIds(List.of(tx.id())).isEmpty());
    List<BankHolderLeg> holderLegs =
        holderPostingRepository.findHolderLegsByTransactionIds(List.of(tx.id()));
    assertEquals(2, holderLegs.size());
    assertEquals(0, sum(holderLegs.stream().map(BankHolderLeg::amount).toList()).signum());
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("400")), "source debited 400");
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("400")), "destination gets 400");
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
    // Given: B is deactivated and holds nothing; A holds nothing either
    holderB.setActive(false);
    holderRepository.save(holderB);

    // When: reconcile B's stash even though it is deactivated; A goes negative (no holder
    // overdraft). The internal Umbuchung is fee-free (REQ-BANK-031, ADR-0052), so A is debited
    // exactly 200 and B credited exactly 200.
    bankLedgerService.bookHolderTransfer(
        new BankHolderTransferRequest(
            holderA.getId(), holderB.getId(), new BigDecimal("200"), null));

    // Then: source debited 200 (goes negative), destination credited the full 200
    assertEquals(0, holderTotal(holderA).compareTo(new BigDecimal("-200")));
    assertEquals(0, holderTotal(holderB).compareTo(new BigDecimal("200")));
  }

  @Test
  void bookDeposit_rejectsClosedAccount() {
    // Given
    account.setStatus(BankAccountStatus.CLOSED);
    accountRepository.save(account);

    // When / Then
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
    // Given
    holderA.setActive(false);
    holderRepository.save(holderA);

    // When / Then
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
    // Given
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

    // When
    BankTransactionDto reversal =
        bankLedgerService.reverseTransaction(transfer.id(), "Tippfehler korrigiert");

    // Then: account legs are a negated mirror; original survives; balances restored
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
    // Given
    BankTransactionDto deposit = deposit(account, holderA, "100");
    bankLedgerService.reverseTransaction(deposit.id(), null);

    // When / Then
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(deposit.id(), null));
    assertEquals(BankConflictException.CODE_BANK_ALREADY_REVERSED, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsWhenAccountWouldGoNegative() {
    // Given: deposit 201 to A, then pay 200 out — with the 1 aUEC fee on top (ADR-0052) the gross
    // debit is 201, so the account is back to zero
    BankTransactionDto deposit = deposit(account, holderA, "201");
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holderA.getId(), new BigDecimal("200"), null));

    // When / Then: undoing the deposit would drive the ACCOUNT negative (the holder may go
    // negative,
    // but the account may not) — rejected with the account overdraft code
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(deposit.id(), null));
    assertEquals(BankConflictException.CODE_BANK_OVERDRAFT, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsReversingAWipeReset() {
    // Given: a WIPE_RESET transaction (a deliberate end-state, REQ-BANK-013)
    BankTransaction wipe =
        transactionRepository.save(
            BankTransaction.builder()
                .type(BankTransactionType.WIPE_RESET)
                .createdAt(Instant.now())
                .build());

    // When / Then: it cannot itself be reversed (REQ-BANK-004)
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(wipe.getId(), null));
    assertEquals(BankConflictException.CODE_BANK_NOT_REVERSIBLE, ex.getCode());
  }

  @Test
  void reverseTransaction_rejectsReversingAReversal() {
    // Given: a deposit and its reversal
    BankTransactionDto deposit = deposit(account, holderA, "100");
    BankTransactionDto reversal = bankLedgerService.reverseTransaction(deposit.id(), null);

    // When / Then: the reversal itself is not reversible — reverse the original instead
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankLedgerService.reverseTransaction(reversal.id(), null));
    assertEquals(BankConflictException.CODE_BANK_NOT_REVERSIBLE, ex.getCode());
  }

  @Test
  void resetAllBalances_zeroesAccountsAndHoldersKeepsHistoryAndIsIdempotent() {
    // Given: this test's accounts and holders hold money before the wipe. The wipe is bank-WIDE and
    // the test DB is shared, so global result counts are not concurrency-safe — every assertion
    // therefore pins THIS test's own entities (their end state is robust regardless of any
    // concurrent wipe by a sibling test).
    deposit(account, holderA, "300");
    deposit(account, holderB, "700");
    deposit(otherAccount, holderB, "250");
    long postingsBefore = postingRepository.count();

    // When
    bankLedgerService.resetAllBalances();

    // Then: both account balances AND both holder globals (the two decoupled dimensions, ADR-0039)
    // are zero; the ledger only grew (append-only — history preserved, nothing deleted).
    assertEquals(0, balance(account).signum());
    assertEquals(0, balance(otherAccount).signum());
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).signum());
    assertTrue(postingRepository.count() > postingsBefore, "history preserved, postings added");

    // And: a second run is idempotent — this test's entities stay at zero and nothing throws.
    bankLedgerService.resetAllBalances();
    assertEquals(0, balance(account).signum());
    assertEquals(0, balance(otherAccount).signum());
    assertEquals(0, holderTotal(holderA).signum());
    assertEquals(0, holderTotal(holderB).signum());
  }

  @Test
  void concurrentWithdrawals_cannotJointlyOverdrawTheAccount() throws Exception {
    // Given: 500 on the account; 4 threads withdraw 200 each — at most 2 can succeed
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

    // Then: exactly 2 succeed (each gross debit is 200 + round(200 * 0.005) = 201, so 2 x 201 = 402
    // <= 500; a third would overdraw the account). The fee on top (ADR-0052) leaves 500 - 402 = 98.
    assertEquals(2, success.get(), "exactly two withdrawals fit into the balance");
    assertEquals(THREADS - 2, conflict.get());
    assertEquals(0, balance(account).compareTo(new BigDecimal("98")));
  }

  @Test
  void auditRows_oneRowPerSuccessfulBookingAndNoneForRejections() {
    // Given
    long before = auditEventRepository.count();

    // When: deposit + withdrawal + account-transfer + holder-transfer succeed (4 mutations); the
    // reversal of the deposit is rejected (the account no longer covers the original 1000) and must
    // NOT audit.
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

    // Then
    assertEquals(before + 4, auditEventRepository.count(), "one audit row per successful booking");
  }

  @Test
  void bookDeposit_recordsCounterpartyUserHandleAndOrgUnitSnapshotAndAuditTarget() {
    // Given (REQ-BANK-044): an Einzahler who belongs to one Staffel
    User depositor = newUser("einzahler");
    Squadron staffel = newSquadron("Staffel " + UUID.randomUUID());
    linkMembership(depositor, staffel);

    // When: the deposit names the counterparty user and their org unit
    BankTransactionDto tx =
        bankLedgerService.bookDeposit(
            new BankDepositRequest(
                account.getId(),
                holderA.getId(),
                new BigDecimal("500"),
                null,
                depositor.getId(),
                staffel.getId()));

    // Then: the header carries the user + handle + org-unit snapshots (deletion-proof)
    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertEquals(depositor.getId(), stored.getCounterpartyUserId());
    assertEquals(depositor.getEffectiveName(), stored.getCounterpartyHandle());
    assertEquals(staffel.getId(), stored.getCounterpartyOrgUnitId());
    assertEquals(staffel.getName(), stored.getCounterpartyOrgUnitName());
    // And the DEPOSIT_BOOKED audit row points at the counterparty and names them in its detail
    BankAuditEvent audit = auditForTransaction(account.getId(), tx.id());
    assertEquals(depositor.getId(), audit.getTargetUserId());
    assertTrue(audit.getDetails().contains(depositor.getEffectiveName()));
    assertTrue(audit.getDetails().contains(staffel.getName()));
  }

  @Test
  void bookWithdrawal_recordsCounterpartyUserWithoutOrgUnitAndAuditTarget() {
    // Given (REQ-BANK-044): a payout to a recorded Empfänger, no org unit chosen
    deposit(account, holderA, "500");
    User recipient = newUser("empfaenger");

    // When
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

    // Then: the user + handle are snapshotted, the org unit stays empty
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
    // Given (REQ-BANK-044): a user who is NOT a member of the chosen org unit
    User depositor = newUser("fremd");
    Squadron unrelated = newSquadron("Fremd " + UUID.randomUUID());

    // When / Then: the org unit must be one of the counterparty's own memberships
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
    // Given / When: a plain deposit records no counterparty (the optional default)
    BankTransactionDto tx = deposit(account, holderA, "200");

    // Then: every counterparty column is null and the audit row has no target user
    BankTransaction stored = transactionRepository.findById(tx.id()).orElseThrow();
    assertNull(stored.getCounterpartyUserId());
    assertNull(stored.getCounterpartyHandle());
    assertNull(stored.getCounterpartyOrgUnitId());
    assertNull(stored.getCounterpartyOrgUnitName());
    assertNull(auditForTransaction(account.getId(), tx.id()).getTargetUserId());
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
    // AREA carries a free-form area name and no org-unit FK (V150/V168 owner-ref CHECK) — a
    // justification-optional type, so the general ledger tests need no Begründung (REQ-BANK-045).
    a.setType(BankAccountType.AREA);
    a.setAreaName(name);
    a.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(a);
    assertNotNull(saved.getId());
    return saved;
  }

  /**
   * Seeds an active {@code SPECIAL} account — a {@linkplain
   * BankAccountType#requiresDebitJustification() justification-mandating} type — for the
   * REQ-BANK-045 Begründung tests.
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
        .findFiltered(null, null, null, accountId, null, PageRequest.of(0, 50))
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
