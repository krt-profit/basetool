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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
 * requests from before the live time end as cancelled with the patch change as reason, and a reset
 * that would overdraw an account refuses the wipe.
 *
 * <p>Its live time lies in 2000, before every other test's bookings; every row it writes is removed
 * afterwards, so no later test meets a balance as of that time.
 */
@SpringBootTest
class BankCurrencyWipeIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");

  @Autowired private BankCurrencyWipe wipe;
  @Autowired private BankLedgerService ledger;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankPostingRepository postingRepository;
  @Autowired private BankHolderPostingRepository holderPostingRepository;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;

  private final UUID patchChange = UUID.randomUUID();
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
    jdbc.update(
        "DELETE FROM bank_audit_event WHERE details LIKE ?", "patchChange=" + patchChange + "%");
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

    assertThat(Objects.requireNonNull(preview).requestsCancelled()).isEqualTo(1);
    assertThat(preview.resetTotal()).isEqualByComparingTo("1300");
    assertThat(Objects.requireNonNull(applied).requestsCancelled()).isEqualTo(1);
    assertThat(applied.resetTotal()).isEqualByComparingTo("1300");
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo("200");
    assertThat(postingRepository.accountBalance(otherAccount.getId())).isEqualByComparingTo("0");
    assertThat(holderPostingRepository.holderTotal(holder.getId())).isEqualByComparingTo("200");
    assertThat(holderPostingRepository.holderTotal(otherHolder.getId())).isEqualByComparingTo("0");
    assertThat(statusOf(before)).isEqualTo("CANCELLED");
    assertThat(reasonOf(before)).isEqualTo(patchChange);
    assertThat(statusOf(after)).isEqualTo("PENDING");
    assertThat(reasonOf(after)).isNull();
    assertThat(
            jdbc.queryForList(
                "SELECT details FROM bank_audit_event WHERE event_type = 'WIPE_RESET_EXECUTED'"
                    + " AND details LIKE ?",
                String.class,
                "patchChange=" + patchChange + "%"))
        .containsExactly(
            "patchChange="
                + patchChange
                + " liveAt="
                + LIVE_AT
                + " accounts=2 stashes=2 totalZeroed=1300 cancelledRequests=1");
  }

  @Test
  void aResetThatWouldOverdrawAnAccountRefusesTheWipe() {
    backdate(deposit(account, holder, "1000"));
    ledger.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holder.getId(), new BigDecimal("800"), null));
    UUID before = pendingRequest(account, BEFORE);
    BigDecimal balance = postingRepository.accountBalance(account.getId());

    assertThatThrownBy(() -> transactions.execute(_ -> wipe.preview(LIVE_AT)))
        .isInstanceOf(BankConflictException.class)
        .extracting(refused -> ((BankConflictException) refused).getCode())
        .isEqualTo(BankConflictException.CODE_BANK_OVERDRAFT);
    assertThatThrownBy(() -> transactions.execute(_ -> wipe.apply(LIVE_AT, patchChange)))
        .isInstanceOf(BankConflictException.class)
        .extracting(refused -> ((BankConflictException) refused).getCode())
        .isEqualTo(BankConflictException.CODE_BANK_OVERDRAFT);
    assertThat(balance).isLessThan(new BigDecimal("1000"));
    assertThat(postingRepository.accountBalance(account.getId())).isEqualByComparingTo(balance);
    assertThat(statusOf(before)).isEqualTo("PENDING");
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
    Timestamp at = Timestamp.from(BEFORE);
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
