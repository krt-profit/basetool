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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Kartellbank's part of a currency wipe against PostgreSQL (REQ-PATCH-041, -042, ADR-0384): one
 * reset offsets the balances as of the live time on both ledgers, later bookings stay, the pending
 * requests from before the live time end as cancelled with the patch change as reason, a reset that
 * would overdraw an account stops at zero, a custody move without an account leg waits for the
 * reset, a later wipe counts an earlier wipe's reset at that wipe's live time, and a wipe after a
 * standalone reset since its live time offsets nothing, so every booking after that reset stays.
 *
 * <p>Its live time lies in 2000, before every other test's bookings; every row it writes is removed
 * afterwards, the standalone resets included, so no later test meets a balance as of that time or a
 * reset that took effect after it.
 */
@SpringBootTest
class BankCurrencyWipeIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");
  private static final Instant BETWEEN = Instant.parse("2000-03-01T00:00:00Z");
  private static final Instant LATER_LIVE_AT = Instant.parse("2000-06-01T00:00:00Z");
  private static final String FEE_RATE = "operation.transfer_fee_rate";

  @Autowired private BankCurrencyWipe wipe;
  @Autowired private BankLedgerService ledger;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankPostingRepository postingRepository;
  @Autowired private BankHolderPostingRepository holderPostingRepository;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;

  private final UUID patchChange = UUID.randomUUID();
  private final UUID laterPatchChange = UUID.randomUUID();
  private BankAccount account;
  private BankAccount otherAccount;
  private BankHolder holder;
  private BankHolder otherHolder;

  @BeforeEach
  void seed() {
    account = newAccount("Wipe Konto " + UUID.randomUUID());
    otherAccount = newAccount("Wipe Gegenkonto " + UUID.randomUUID());
    holder = newHolder("wipe-holder-" + UUID.randomUUID());
    otherHolder = newHolder("wipe-other-" + UUID.randomUUID());
  }

  @AfterEach
  void clean() {
    List<UUID> accounts = List.of(account.getId(), otherAccount.getId());
    for (UUID id : accounts) {
      jdbc.update("DELETE FROM bank_booking_request WHERE account_id = ?", id);
      jdbc.update("DELETE FROM bank_audit_event WHERE account_id = ?", id);
    }
    List<UUID> bookings =
        jdbc.queryForList(
            """
            SELECT DISTINCT transaction_id FROM bank_posting WHERE account_id IN (?, ?)
            UNION SELECT DISTINCT transaction_id FROM bank_holder_posting WHERE holder_id IN (?, ?)
            """,
            UUID.class,
            account.getId(),
            otherAccount.getId(),
            holder.getId(),
            otherHolder.getId());
    for (UUID transaction : bookings) {
      jdbc.update("DELETE FROM bank_audit_event WHERE transaction_id = ?", transaction);
      jdbc.update("DELETE FROM bank_holder_posting WHERE transaction_id = ?", transaction);
      jdbc.update("DELETE FROM bank_posting WHERE transaction_id = ?", transaction);
      jdbc.update("DELETE FROM bank_transaction WHERE id = ?", transaction);
    }
    for (UUID wiped : List.of(patchChange, laterPatchChange)) {
      jdbc.update(
          "DELETE FROM bank_audit_event WHERE details LIKE ?", "patchChange=" + wiped + "%");
    }
    jdbc.update("DELETE FROM bank_holder WHERE id IN (?, ?)", holder.getId(), otherHolder.getId());
    jdbc.update("DELETE FROM bank_account WHERE id IN (?, ?)", accounts.get(0), accounts.get(1));
  }

  @Test
  void theResetOffsetsTheBalancesOfTheLiveTimeAndKeepsLaterBookings() {
    backdate(deposit(account, holder, "1000"));
    backdate(deposit(otherAccount, otherHolder, "300"));
    deposit(account, holder, "200");
    UUID before = pendingRequest(account, BEFORE);
    UUID after = pendingRequest(account, Instant.now());

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LIVE_AT));
    BankCurrencyWipe.Effect applied = transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));

    assertThat(Objects.requireNonNull(preview).unlinkedRequestsCancelled()).isEqualTo(1);
    assertThat(preview.resetTotal()).isEqualByComparingTo("1300");
    assertThat(Objects.requireNonNull(applied).unlinkedRequestsCancelled()).isEqualTo(1);
    assertThat(applied.resetTotal()).isEqualByComparingTo("1300");
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("200");
    assertThat(postingRepository.accountBalance(otherAccount.getId())).isEqualByComparingTo("0");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("200");
    assertThat(holderPostingRepository.holderTotal(otherHolder.getId())).isEqualByComparingTo("0");
    assertThat(statusOf(before)).isEqualTo("CANCELLED");
    assertThat(reasonOf(before)).isEqualTo(patchChange);
    assertThat(statusOf(after)).isEqualTo("PENDING");
    assertThat(reasonOf(after)).isNull();
    assertThat(summaryOf(patchChange))
        .isEqualTo(
            "patchChange="
                + patchChange
                + " liveAt="
                + LIVE_AT
                + " accounts=2 stashes=2 totalZeroed=1300 clampedAccounts=0 clampedStashes=0"
                + " cancelledRequests=1");
  }

  @Test
  void aResetThatWouldOverdrawAnAccountStopsItAtZero() {
    backdate(deposit(account, holder, "1000"));
    ledger.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holder.getId(), new BigDecimal("800"), null));
    BigDecimal balance = postingRepository.accountBalance(account.getId());
    BigDecimal custody = holderPostingRepository.holderTotal(holder.getId());

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LIVE_AT));
    BankCurrencyWipe.Effect applied = transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));

    assertThat(balance).isLessThan(new BigDecimal("1000")).isPositive();
    assertThat(custody).isLessThan(new BigDecimal("1000"));
    assertThat(Objects.requireNonNull(preview).accountsClamped()).isEqualTo(1);
    assertThat(preview.resetTotal()).isEqualByComparingTo(balance);
    assertThat(Objects.requireNonNull(applied).accountsClamped()).isEqualTo(1);
    assertThat(applied.resetTotal()).isEqualByComparingTo(balance);
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("0");
    assertThat(holderPostingRepository.holderTotal(holder.getId()))
        .isEqualByComparingTo(custody.min(BigDecimal.ZERO));
    assertThat(summaryOf(patchChange)).contains(" clampedAccounts=1 clampedStashes=1 ");
  }

  @Test
  void aWipeAfterAStandaloneResetKeepsTheCreditBookedSinceThatReset() {
    backdate(deposit(account, holder, "100"));
    ledger.resetAllBalances();
    deposit(account, holder, "50");

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LIVE_AT));
    BankCurrencyWipe.Effect applied = transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));

    assertTheWipeOffsetNothing(preview, applied);
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("50");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("50");
    assertThat(effectiveTimesOfTheResets()).containsExactly((Timestamp) null);
  }

  @Test
  void aWipeAfterAStandaloneResetOffsetsNothingOfWhatWasSpentBeforeThatReset() {
    backdate(deposit(account, holder, "100"));
    ledger.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holder.getId(), new BigDecimal("80"), null));
    ledger.resetAllBalances();
    deposit(account, holder, "50");

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LIVE_AT));
    BankCurrencyWipe.Effect applied = transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));

    assertTheWipeOffsetNothing(preview, applied);
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("50");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("50");
  }

  @Test
  void aWipeAfterAStandaloneResetKeepsTheCustodyAHolderTookOnSinceThatReset() {
    backdate(deposit(account, holder, "100"));
    ledger.resetAllBalances();
    deposit(otherAccount, holder, "50");

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LIVE_AT));
    BankCurrencyWipe.Effect applied = transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));

    assertTheWipeOffsetNothing(preview, applied);
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("0");
    assertThat(postingRepository.accountBalance(otherAccount.getId())).isEqualByComparingTo("50");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("50");
  }

  @Test
  void aLaterWipeStillOffsetsWhatWasBookedBetweenTheTwoLiveTimes() {
    backdate(deposit(account, holder, "100"));
    backdate(deposit(account, holder, "30"), BETWEEN);
    transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));
    deposit(account, holder, "20");

    BankCurrencyWipe.Effect applied =
        transactions.execute(_ -> wipe.apply(LATER_LIVE_AT, laterPatchChange));

    assertThat(Objects.requireNonNull(applied).resetTotal()).isEqualByComparingTo("30");
    assertThat(applied.accountsClamped()).isZero();
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("20");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("20");
  }

  @Test
  void aLaterWipeCountsAnEarlierWipesResetAtThatWipesLiveTime() {
    backdate(deposit(account, holder, "100"));
    transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange));
    deposit(account, holder, "20");

    BankCurrencyWipe.Effect preview = transactions.execute(_ -> wipe.preview(LATER_LIVE_AT));
    BankCurrencyWipe.Effect applied =
        transactions.execute(_ -> wipe.apply(LATER_LIVE_AT, laterPatchChange));

    assertThat(Objects.requireNonNull(preview).resetTotal()).isEqualByComparingTo("0");
    assertThat(preview.accountsClamped()).isZero();
    assertThat(Objects.requireNonNull(applied).resetTotal()).isEqualByComparingTo("0");
    assertThat(applied.accountsClamped()).isZero();
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("20");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("20");
    assertThat(effectiveTimesOfTheResets()).containsExactly(Timestamp.from(LIVE_AT));
  }

  @Test
  void aZeroFeeHolderTransferThatRacesTheWipeLeavesItsSourceAtZero() throws Exception {
    backdate(deposit(account, holder, "1000"));
    String feeRate = feeRate();
    setFeeRate("0");
    try (LockRace race = new LockRace(transactions, jdbc)) {
      LockRace.Held<BankTransactionDto> transfer =
          race.hold(
              null,
              () ->
                  ledger.bookHolderTransfer(
                      new BankHolderTransferRequest(
                          holder.getId(), otherHolder.getId(), new BigDecimal("1000"), null)));
      Future<BankCurrencyWipe.Effect> wiped =
          race.startWaiting(
              null, () -> transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange)));
      transfer.commit();
      LockRace.finish(wiped);
    } finally {
      setFeeRate(feeRate);
    }

    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("0");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("0");
    assertThat(holderPostingRepository.holderTotal(otherHolder.getId()))
        .isEqualByComparingTo("1000");
  }

  /**
   * Asserts that the wipe at the live time offset nothing and stopped no account or holder at zero,
   * in its preview, its result and its summary event.
   *
   * @param preview the wipe's preview
   * @param applied the wipe's result
   */
  private void assertTheWipeOffsetNothing(
      BankCurrencyWipe.Effect preview, BankCurrencyWipe.Effect applied) {
    assertThat(Objects.requireNonNull(preview).resetTotal()).isEqualByComparingTo("0");
    assertThat(preview.accountsClamped()).isZero();
    assertThat(Objects.requireNonNull(applied).resetTotal()).isEqualByComparingTo("0");
    assertThat(applied.accountsClamped()).isZero();
    assertThat(summaryOf(patchChange))
        .isEqualTo(
            "patchChange="
                + patchChange
                + " liveAt="
                + LIVE_AT
                + " accounts=0 stashes=0 totalZeroed=0 clampedAccounts=0 clampedStashes=0"
                + " cancelledRequests=0");
  }

  /**
   * Reads the details of the one summary event a wipe recorded.
   *
   * @param wiped the patch change of the wipe
   * @return the event's details
   */
  private String summaryOf(@NotNull UUID wiped) {
    return jdbc.queryForObject(
        "SELECT details FROM bank_audit_event WHERE event_type = 'WIPE_RESET_EXECUTED'"
            + " AND details LIKE ?",
        String.class,
        "patchChange=" + wiped + "%");
  }

  /**
   * Reads the effective time of every wipe reset that booked on the test's account.
   *
   * @return the stored effective times, {@code null} for a reset that takes effect when recorded
   */
  private List<Timestamp> effectiveTimesOfTheResets() {
    return jdbc.queryForList(
        """
        SELECT t.effective_at FROM bank_transaction t
        WHERE t.type = 'WIPE_RESET'
          AND t.id IN (SELECT transaction_id FROM bank_posting WHERE account_id = ?)
        """,
        Timestamp.class,
        account.getId());
  }

  /**
   * Reads the configured transfer-fee rate.
   *
   * @return the stored setting value
   */
  private String feeRate() {
    return jdbc.queryForObject(
        "SELECT setting_value FROM system_setting WHERE setting_key = ?", String.class, FEE_RATE);
  }

  /**
   * Stores the transfer-fee rate.
   *
   * @param rate the setting value
   */
  private void setFeeRate(String rate) {
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?", rate, FEE_RATE);
  }

  /**
   * Books a deposit now.
   *
   * @param target the account
   * @param receiver the receiving holder
   * @param amount the whole aUEC
   * @return the transaction
   */
  private @NotNull UUID deposit(
      @NotNull BankAccount target, @NotNull BankHolder receiver, @NotNull String amount) {
    return ledger
        .bookDeposit(
            new BankDepositRequest(target.getId(), receiver.getId(), new BigDecimal(amount), null))
        .id();
  }

  /**
   * Moves a transaction and its legs to before the live time.
   *
   * @param transaction the transaction
   */
  private void backdate(@NotNull UUID transaction) {
    backdate(transaction, BEFORE);
  }

  /**
   * Moves a transaction and its legs to a past booking time.
   *
   * @param transaction the transaction
   * @param bookedAt the booking time
   */
  private void backdate(@NotNull UUID transaction, @NotNull Instant bookedAt) {
    Timestamp at = Timestamp.from(bookedAt);
    jdbc.update("UPDATE bank_transaction SET created_at = ? WHERE id = ?", at, transaction);
    jdbc.update("UPDATE bank_posting SET created_at = ? WHERE transaction_id = ?", at, transaction);
    jdbc.update(
        "UPDATE bank_holder_posting SET created_at = ? WHERE transaction_id = ?", at, transaction);
  }

  /**
   * Inserts a pending deposit request submitted at the given time.
   *
   * @param target the account
   * @param createdAt the submission time
   * @return the request
   */
  private @NotNull UUID pendingRequest(@NotNull BankAccount target, @NotNull Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO bank_booking_request
            (id, account_id, type, amount, requester_handle, created_at, updated_at)
        VALUES (?, ?, 'DEPOSIT', 50, 'wipe-requester', ?, ?)
        """,
        id,
        target.getId(),
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private String statusOf(@NotNull UUID request) {
    return jdbc.queryForObject(
        "SELECT status FROM bank_booking_request WHERE id = ?", String.class, request);
  }

  private UUID reasonOf(@NotNull UUID request) {
    return jdbc.queryForObject(
        "SELECT cancelled_by_patch_change FROM bank_booking_request WHERE id = ?",
        UUID.class,
        request);
  }

  private BankAccount newAccount(String name) {
    BankAccount created = new BankAccount();
    created.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    created.setName(name);
    created.setType(BankAccountType.AREA);
    created.setAreaName(name);
    created.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.save(created);
  }

  private BankHolder newHolder(String handle) {
    BankHolder created = new BankHolder();
    created.setHandle(handle);
    created.setActive(true);
    return holderRepository.save(created);
  }
}
