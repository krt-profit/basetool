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

package de.greluc.krt.profit.basetool.backend.bank.api;

import de.greluc.krt.profit.basetool.backend.exception.DomainProblem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Bank-domain state conflict carrying its own stable problem code, one of the {@code CODE_BANK_*}
 * constants, so the frontend can render a specific inline error.
 *
 * <p>Mapped to HTTP {@code 409}; the {@link #code}, one of the {@link BankProblemCode} values,
 * becomes the RFC 7807 {@code code} and the optional {@link #properties} are copied onto the
 * problem response.
 */
@Getter
public final class BankConflictException extends DomainProblem {

  /** Overdraft attempt: the booking would take the account balance below zero (REQ-BANK-006). */
  public static final String CODE_BANK_OVERDRAFT = BankProblemCode.BANK_OVERDRAFT.code();

  /**
   * Holder overdraft; never thrown, because holder balances may go negative (REQ-BANK-006,
   * ADR-0039).
   */
  public static final String CODE_BANK_HOLDER_OVERDRAFT =
      BankProblemCode.BANK_HOLDER_OVERDRAFT.code();

  /** Close attempt on an account whose balance is not zero (REQ-BANK-002). */
  public static final String CODE_BANK_ACCOUNT_NOT_EMPTY =
      BankProblemCode.BANK_ACCOUNT_NOT_EMPTY.code();

  /** Booking attempt on a {@code CLOSED} account (REQ-BANK-002). */
  public static final String CODE_BANK_ACCOUNT_CLOSED = BankProblemCode.BANK_ACCOUNT_CLOSED.code();

  /** Grant creation for a user who does not hold the Bank Employee role (REQ-BANK-009). */
  public static final String CODE_BANK_GRANTEE_MISSING_ROLE =
      BankProblemCode.BANK_GRANTEE_MISSING_ROLE.code();

  /**
   * Transfer with identical source and destination: same source/destination account on an
   * account-to-account transfer, or same source/destination holder on a holder Umbuchung
   * (REQ-BANK-011/-031).
   */
  public static final String CODE_BANK_SELF_TRANSFER = BankProblemCode.BANK_SELF_TRANSFER.code();

  /** Reversal attempt on a transaction that has already been reversed (REQ-BANK-004). */
  public static final String CODE_BANK_ALREADY_REVERSED =
      BankProblemCode.BANK_ALREADY_REVERSED.code();

  /** Booking attempt naming a deactivated holder (REQ-BANK-003). */
  public static final String CODE_BANK_HOLDER_INACTIVE =
      BankProblemCode.BANK_HOLDER_INACTIVE.code();

  /**
   * Reversal attempt on a transaction that is not itself reversible — a {@code WIPE_RESET} (a
   * deliberate end-state) or a {@code REVERSAL} (a mistake is corrected by reversing the original,
   * never the correction). REQ-BANK-004.
   */
  public static final String CODE_BANK_NOT_REVERSIBLE = BankProblemCode.BANK_NOT_REVERSIBLE.code();

  /**
   * Decision (confirm/reject/cancel) attempt on a booking request that is no longer {@code PENDING}
   * — it was already confirmed, rejected or cancelled. Blocks double-decisions (REQ-BANK-023).
   */
  public static final String CODE_BANK_REQUEST_NOT_PENDING =
      BankProblemCode.BANK_REQUEST_NOT_PENDING.code();

  /**
   * Edit attempt by the requester on a booking request whose over-limit approval was already
   * granted (REQ-BANK-056); the requester must cancel and re-raise instead.
   */
  public static final String CODE_BANK_REQUEST_ALREADY_APPROVED =
      BankProblemCode.BANK_REQUEST_ALREADY_APPROVED.code();

  /**
   * Close attempt on an account that still has at least one open {@code PENDING} booking request —
   * the request must be confirmed, rejected or cancelled first (REQ-BANK-025).
   */
  public static final String CODE_BANK_ACCOUNT_HAS_PENDING_REQUESTS =
      BankProblemCode.BANK_ACCOUNT_HAS_PENDING_REQUESTS.code();

  /**
   * Confirmation attempt on a booking request that exceeds the requester's approval limit without
   * the bank employee attesting that the responsible holder's approval was obtained — the mandatory
   * over-limit checkbox is missing (REQ-BANK-041).
   */
  public static final String CODE_BANK_OWNER_APPROVAL_REQUIRED =
      BankProblemCode.BANK_OWNER_APPROVAL_REQUIRED.code();

  /**
   * Direct booking out of the KRT ({@code CARTEL}) account by a plain bank employee above the
   * ceiling T1 (REQ-BANK-047). Not thrown: the direct-booking controller files such an attempt as a
   * {@code PENDING} approval request and answers {@code 202} (ADR-0109).
   */
  public static final String CODE_BANK_CARTEL_APPROVAL_REQUIRED =
      BankProblemCode.BANK_CARTEL_APPROVAL_REQUIRED.code();

  /**
   * Split deposit (REQ-BANK-043) attempted while there is no active squadron account to distribute
   * to — none exists, or the only one is the deposit's own named account (which is excluded from
   * the split). The split cannot be honoured.
   */
  public static final String CODE_BANK_SPLIT_NO_TARGETS =
      BankProblemCode.BANK_SPLIT_NO_TARGETS.code();

  /**
   * Split deposit (REQ-BANK-043) whose percentage of the gross rounds to less than 1 aUEC, so there
   * is nothing to distribute. The requested split cannot be honoured — raise the amount or the
   * percentage.
   */
  public static final String CODE_BANK_SPLIT_TOO_SMALL =
      BankProblemCode.BANK_SPLIT_TOO_SMALL.code();

  /**
   * Withdrawal/transfer (request or direct booking) leaving a {@link
   * de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
   * justification-mandating} account ({@code CARTEL}, {@code CARTEL_BANK}, {@code SPECIAL}) without
   * a non-blank justification (Begr&uuml;ndung). REQ-BANK-045.
   */
  public static final String CODE_BANK_JUSTIFICATION_REQUIRED =
      BankProblemCode.BANK_JUSTIFICATION_REQUIRED.code();

  /**
   * Fee-inclusive withdrawal/transfer (REQ-BANK-033) whose amount does not exceed the in-game fee,
   * so nothing would arrive ({@code amount - fee <= 0}). Never thrown in the default on-top fee
   * mode.
   */
  public static final String CODE_BANK_FEE_EXCEEDS_AMOUNT =
      BankProblemCode.BANK_FEE_EXCEEDS_AMOUNT.code();

  /** The stable machine-readable problem code, one of the {@code CODE_BANK_*} constants. */
  private final String code;

  /**
   * Structured, PII-free parameters copied onto the RFC 7807 response as extension properties (e.g.
   * {@code accountNo}, {@code available}, {@code holderHandle}) so the frontend can build a
   * localized message; never {@code null}, possibly empty.
   */
  private final transient Map<String, Object> properties;

  /**
   * Creates a bank conflict without structured parameters.
   *
   * @param code one of the {@code CODE_BANK_*} constants; becomes the RFC 7807 {@code code}
   * @param message human-readable detail (literal English or an i18n key, see {@code
   *     GlobalExceptionHandler#resolveDetail})
   */
  public BankConflictException(@NotNull String code, @NotNull String message) {
    this(code, message, null);
  }

  /**
   * Creates a bank conflict with structured parameters for client-side localization.
   *
   * @param code one of the {@code CODE_BANK_*} constants; becomes the RFC 7807 {@code code}
   * @param message human-readable detail (literal English or an i18n key)
   * @param properties PII-free extension properties for the problem response; copied defensively,
   *     {@code null} means none
   */
  public BankConflictException(
      @NotNull String code, @NotNull String message, @Nullable Map<String, Object> properties) {
    super(message);
    this.code = code;
    this.properties =
        properties == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  @Override
  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }

  @Override
  public String code() {
    return code;
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Bank conflict";
  }

  @Override
  public Map<String, Object> extraProperties() {
    return properties;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, ?> logExtra() {
    return Map.of("bankCode", code);
  }
}
