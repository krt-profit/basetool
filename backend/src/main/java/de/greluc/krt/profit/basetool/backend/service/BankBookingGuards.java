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
 * The bank's booking validation guards (extracted from {@link BankLedgerService}, #1253): the
 * pre-persist checks a booking must pass before {@link BankPostingWriter} writes any ledger row —
 * account/holder status, the conditional Begr&uuml;ndung requirement, the account-level
 * no-overdraft invariant (REQ-BANK-006), the fee-inclusive {@code amount > fee} rule (#999) and the
 * KRT-account direct-booking cap (REQ-BANK-047). Each throws a {@link BankConflictException} (or
 * {@link de.greluc.krt.profit.basetool.backend.exception.BadRequestException}) with a stable code;
 * none mutates state, so the guards leave the ledgers untouched and only reject or pass.
 *
 * <p>The overdraft guard ({@link #requireAccountCoverage}) reads the balance while the account row
 * is locked by the surrounding {@code BankLedgerService} booking transaction, so concurrent
 * bookings cannot jointly overdraw. The <strong>holder</strong> dimension is deliberately unguarded
 * (ADR-0039) — a holder balance may go negative — so no method here checks holder coverage.
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
   * Enforces the conditional Begr&uuml;ndung rule (REQ-BANK-045) for a debit (withdrawal/transfer)
   * leaving the given account: when the account type {@linkplain
   * BankAccountType#requiresDebitJustification() mandates a reason} ({@code CARTEL}, {@code
   * CARTEL_BANK}, {@code SPECIAL}) the justification must be present and non-blank. Shared by the
   * direct-booking paths in {@link BankLedgerService} and the booking-request create path (after
   * its own type guard). A deposit never reaches this check.
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
  public BankConflictException accountOverdraft(
      @NotNull String accountNo, @NotNull BigDecimal available) {
    return new BankConflictException(
        BankConflictException.CODE_BANK_OVERDRAFT,
        "The booking would overdraw the account",
        Map.of("accountNo", accountNo, "available", plain(available)));
  }

  /**
   * Guards the fee-inclusive fee mode (REQ-BANK-033, #999): the entered gross must exceed the
   * in-game fee so something actually arrives at the recipient/destination. Rejects with {@code
   * BANK_FEE_EXCEEDS_AMOUNT} when {@code amount - fee <= 0}. Called only in the inclusive mode; in
   * the default on-top mode the fee rides on top and the full amount always arrives, so the guard
   * never applies.
   *
   * @param amount the entered gross debited from the source
   * @param fee the in-game fee skimmed from it
   * @throws BankConflictException {@code BANK_FEE_EXCEEDS_AMOUNT} when nothing would arrive
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
   * Enforces the KRT-account direct-booking cap (REQ-BANK-047): a plain bank employee may
   * <em>directly</em> book a withdrawal / transfer leaving the KRT ({@code CARTEL}) account only up
   * to the bank-employee approval ceiling {@code T1} ({@link
   * BankAccount#getEmployeeApprovalCeiling()}, an unset ceiling treated as {@code 0}); above it the
   * money must go through the booking-request → external-approval flow (Bereichsleiter Profit /
   * Organisationsleitung). Bank management and admins (management-or-above) are unrestricted, and
   * every non-CARTEL account is a no-op. Called by the <em>direct-booking</em> controller only —
   * NOT the request-confirmation path, whose over-limit approval was already attested via the
   * confirm checkbox, so {@link BankLedgerService#bookWithdrawal}/{@link
   * BankLedgerService#bookTransfer} stay uncapped and reusable there.
   *
   * @param accountId the (source) account the direct booking debits
   * @param amount the entered whole-aUEC amount leaving the account
   * @throws BankConflictException {@code BANK_CARTEL_APPROVAL_REQUIRED} when a plain employee
   *     exceeds the ceiling on the KRT account
   */
  @Transactional(readOnly = true)
  public void requireCartelDirectBookingAllowed(
      @NotNull UUID accountId, @NotNull BigDecimal amount) {
    if (authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT))) {
      return;
    }
    BankAccount account = accountRepository.findById(accountId).orElse(null);
    if (account == null || account.getType() != BankAccountType.CARTEL) {
      return;
    }
    BigDecimal ceiling =
        account.getEmployeeApprovalCeiling() == null
            ? BigDecimal.ZERO
            : account.getEmployeeApprovalCeiling();
    if (amount.compareTo(ceiling) > 0) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_CARTEL_APPROVAL_REQUIRED,
          "The amount exceeds the bank-employee approval ceiling for the KRT account; raise a"
              + " booking request so the Bereichsleiter Profit / Organisationsleitung can approve"
              + " it",
          Map.of("ceiling", plain(ceiling)));
    }
  }
}
