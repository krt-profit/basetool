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

import static de.greluc.krt.profit.basetool.backend.util.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-persist validation guards a booking must pass before {@link BankPostingWriter} writes any
 * ledger row: account and holder status, the conditional Begr&uuml;ndung, the account-level
 * no-overdraft rule (REQ-BANK-006), the fee-inclusive {@code amount > fee} rule and the KRT-account
 * direct-booking cap (REQ-BANK-047).
 *
 * <p>Each guard throws a {@link BankConflictException} (or {@link
 * de.greluc.krt.profit.basetool.backend.exception.BadRequestException}) with a stable code and
 * never mutates state. Holder balances may go negative and are not guarded (ADR-0039).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankBookingGuards {

  private final BankAccountRepository accountRepository;
  private final BankPostingRepository postingRepository;
  private final AuthHelperService authHelperService;

  /**
   * Rejects bookings on closed accounts (REQ-BANK-002).
   *
   * @param account the locked account
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} when the account is not active
   */
  public void requireActive(@NotNull BankAccount account) {
    if (account.getStatus() != BankAccountStatus.ACTIVE) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_ACCOUNT_CLOSED,
          "The account is closed and rejects postings",
          Map.of("accountNo", account.getAccountNo()));
    }
  }

  /**
   * Enforces the conditional Begr&uuml;ndung rule (REQ-BANK-045): a debit from an account whose
   * type {@linkplain BankAccountType#requiresDebitJustification() mandates a reason} needs a
   * non-blank justification.
   *
   * @param account the debited (source/paying) account
   * @param justification the supplied justification, or {@code null}
   * @throws BankConflictException with {@code BANK_JUSTIFICATION_REQUIRED} when a reason is
   *     mandated but missing
   */
  public static void requireDebitJustification(
      @NotNull BankAccount account, @Nullable String justification) {
    if (account.getType().requiresDebitJustification()
        && (justification == null || justification.isBlank())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_JUSTIFICATION_REQUIRED,
          "A justification is required for a withdrawal or transfer from this account",
          Map.of("accountNo", account.getAccountNo(), "accountType", account.getType().name()));
    }
  }

  /**
   * Rejects incoming postings naming a deactivated holder (REQ-BANK-003) — money may still be moved
   * OUT of a deactivated holder's stash, and a holder Umbuchung may reconcile it in either
   * direction.
   *
   * @param holder the receiving holder
   * @throws BankConflictException with {@code BANK_HOLDER_INACTIVE} when the holder is deactivated
   */
  public void requireActiveHolder(@NotNull BankHolder holder) {
    if (!holder.isActive()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_HOLDER_INACTIVE,
          "The holder is deactivated and accepts no new money",
          Map.of("holderHandle", holder.getHandle()));
    }
  }

  /**
   * The no-overdraft guard (REQ-BANK-006): the account balance must cover the removal. Runs while
   * the account row is locked, so concurrent bookings cannot jointly overdraw. The holder dimension
   * is intentionally not guarded — it may go negative (ADR-0039).
   *
   * @param account the locked source account
   * @param amount the positive removal amount
   * @throws BankConflictException with {@code BANK_OVERDRAFT} when the balance does not cover the
   *     removal
   */
  public void requireAccountCoverage(@NotNull BankAccount account, @NotNull BigDecimal amount) {
    BigDecimal balance = postingRepository.accountBalance(account.getId());
    if (balance.compareTo(amount) < 0) {
      throw accountOverdraft(account.getAccountNo(), balance);
    }
  }

  /**
   * Builds the account-level overdraft conflict naming account and available balance (REQ-BANK-006
   * acceptance) as structured properties. Exposed so the reversal path in {@link BankLedgerService}
   * — which re-checks the negated mirror against current balances — raises the identical conflict.
   *
   * @param accountNo the account's display number
   * @param available the current balance
   * @return the 409 conflict to throw
   */
  @NotNull
  public BankConflictException accountOverdraft(
      @NotNull String accountNo, @NotNull BigDecimal available) {
    return new BankConflictException(
        BankConflictException.CODE_BANK_OVERDRAFT,
        "The booking would overdraw the account",
        Map.of("accountNo", accountNo, "available", plain(available)));
  }

  /**
   * Guards the fee-inclusive mode (REQ-BANK-033): the entered gross must exceed the in-game fee so
   * something arrives. Called only in the inclusive mode.
   *
   * @param amount the entered gross debited from the source
   * @param fee the in-game fee skimmed from it
   * @throws BankConflictException {@code BANK_FEE_EXCEEDS_AMOUNT} when {@code amount - fee <= 0}
   */
  public void requireAmountExceedsFee(@NotNull BigDecimal amount, @NotNull BigDecimal fee) {
    if (amount.subtract(fee).signum() <= 0) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT,
          "In fee-inclusive mode the entered amount does not exceed the fee, so nothing would"
              + " arrive; raise the amount",
          Map.of("fee", plain(fee)));
    }
  }

  /**
   * Reports whether a plain bank employee's direct withdrawal or transfer from the KRT ({@code
   * CARTEL}) account exceeds the approval ceiling {@link BankAccount#getEmployeeApprovalCeiling()}
   * (unset counts as {@code 0}) and must become an approval request instead (REQ-BANK-047,
   * ADR-0109).
   *
   * <p>Management and admins are uncapped, and non-CARTEL accounts always return {@code false}.
   * Used only by the direct-booking controller, not by request confirmation.
   *
   * @param accountId the (source) account the direct booking debits
   * @param amount the entered whole-aUEC amount leaving the account
   * @return {@code true} when the attempt must become an approval request; {@code false} when it
   *     may book directly
   */
  @Transactional(readOnly = true)
  public boolean exceedsCartelDirectBookingCeiling(
      @NotNull UUID accountId, @NotNull BigDecimal amount) {
    if (authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT))) {
      return false;
    }
    BankAccount account = accountRepository.findById(accountId).orElse(null);
    if (account == null || account.getType() != BankAccountType.CARTEL) {
      return false;
    }
    BigDecimal ceiling =
        account.getEmployeeApprovalCeiling() == null
            ? BigDecimal.ZERO
            : account.getEmployeeApprovalCeiling();
    return amount.compareTo(ceiling) > 0;
  }
}
