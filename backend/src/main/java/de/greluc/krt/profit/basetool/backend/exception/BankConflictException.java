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

package de.greluc.krt.profit.basetool.backend.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Bank-domain state conflict carrying its own <em>stable problem code</em> (epic #556). Unlike the
 * generic {@link BusinessConflictException} (always {@code BUSINESS_CONFLICT}), the bank spec
 * mandates distinguishable 409 codes — {@code BANK_OVERDRAFT}, {@code BANK_ACCOUNT_NOT_EMPTY},
 * {@code BANK_ACCOUNT_CLOSED}, {@code BANK_GRANTEE_MISSING_ROLE}, … — so the frontend can render a
 * specific inline field error (e.g. the overdraft hint at the amount input, K1 mockup) instead of a
 * generic toast.
 *
 * <p>Mapped to HTTP {@code 409 Conflict} by {@link GlobalExceptionHandler}'s generic {@code
 * AppException} dispatch handler; the {@link #code} is surfaced as the RFC 7807 {@code code}
 * extension property and the optional {@link #properties} are copied onto the problem response so
 * clients can localize parameterized messages (available balance, account number, holder handle)
 * without parsing the human-readable {@code detail}.
 *
 * <p>Unlike its seven {@link AppException} siblings, this exception's {@link #code()}, {@link
 * #titleKey()}, {@link #detailKey()} and {@link #typeSuffix()} are <b>not</b> a fixed per-type
 * {@link AppExceptionKind} constant — each instance picks its own {@link #code} from one of the
 * {@code CODE_BANK_*} constants below, so the abstract accessors are computed from that field
 * instead of delegated to an enum.
 */
@Getter
public final class BankConflictException extends AppException {

  /** Overdraft attempt: the booking would take the account balance below zero (REQ-BANK-006). */
  public static final String CODE_BANK_OVERDRAFT = "BANK_OVERDRAFT";

  /**
   * Retired since ADR-0039: holder balances are now a global dimension allowed to go negative
   * (REQ-BANK-006), so no booking path checks holder coverage. The constant is kept for backward
   * reference; it is no longer thrown.
   */
  public static final String CODE_BANK_HOLDER_OVERDRAFT = "BANK_HOLDER_OVERDRAFT";

  /** Close attempt on an account whose balance is not zero (REQ-BANK-002). */
  public static final String CODE_BANK_ACCOUNT_NOT_EMPTY = "BANK_ACCOUNT_NOT_EMPTY";

  /** Booking attempt on a {@code CLOSED} account (REQ-BANK-002). */
  public static final String CODE_BANK_ACCOUNT_CLOSED = "BANK_ACCOUNT_CLOSED";

  /** Grant creation for a user who does not hold the Bank Employee role (REQ-BANK-009). */
  public static final String CODE_BANK_GRANTEE_MISSING_ROLE = "BANK_GRANTEE_MISSING_ROLE";

  /**
   * Transfer with identical source and destination: same source/destination account on an
   * account-to-account transfer, or same source/destination holder on a holder Umbuchung
   * (REQ-BANK-011/-031).
   */
  public static final String CODE_BANK_SELF_TRANSFER = "BANK_SELF_TRANSFER";

  /** Reversal attempt on a transaction that has already been reversed (REQ-BANK-004). */
  public static final String CODE_BANK_ALREADY_REVERSED = "BANK_ALREADY_REVERSED";

  /** Booking attempt naming a deactivated holder (REQ-BANK-003). */
  public static final String CODE_BANK_HOLDER_INACTIVE = "BANK_HOLDER_INACTIVE";

  /**
   * Reversal attempt on a transaction that is not itself reversible — a {@code WIPE_RESET} (a
   * deliberate end-state) or a {@code REVERSAL} (a mistake is corrected by reversing the original,
   * never the correction). REQ-BANK-004.
   */
  public static final String CODE_BANK_NOT_REVERSIBLE = "BANK_NOT_REVERSIBLE";

  /**
   * Decision (confirm/reject/cancel) attempt on a booking request that is no longer {@code PENDING}
   * — it was already confirmed, rejected or cancelled. Blocks double-decisions (REQ-BANK-023).
   */
  public static final String CODE_BANK_REQUEST_NOT_PENDING = "BANK_REQUEST_NOT_PENDING";

  /**
   * Close attempt on an account that still has at least one open {@code PENDING} booking request —
   * the request must be confirmed, rejected or cancelled first (REQ-BANK-025).
   */
  public static final String CODE_BANK_ACCOUNT_HAS_PENDING_REQUESTS =
      "BANK_ACCOUNT_HAS_PENDING_REQUESTS";

  /**
   * Confirmation attempt on a booking request that exceeds the requester's approval limit without
   * the bank employee attesting that the responsible holder's approval was obtained — the mandatory
   * over-limit checkbox is missing (REQ-BANK-041).
   */
  public static final String CODE_BANK_OWNER_APPROVAL_REQUIRED = "BANK_OWNER_APPROVAL_REQUIRED";

  /**
   * Direct withdrawal/transfer leaving the KRT ({@code CARTEL}) account by a plain bank employee
   * for an amount above the bank-employee approval ceiling T1 — the money must instead go through
   * the booking-request → external-approval flow (Bereichsleiter Profit / Organisationsleitung).
   * Management and admins are unrestricted (REQ-BANK-047).
   */
  public static final String CODE_BANK_CARTEL_APPROVAL_REQUIRED = "BANK_CARTEL_APPROVAL_REQUIRED";

  /**
   * Split deposit (REQ-BANK-043) attempted while there is no active squadron account to distribute
   * to — none exists, or the only one is the deposit's own named account (which is excluded from
   * the split). The split cannot be honoured.
   */
  public static final String CODE_BANK_SPLIT_NO_TARGETS = "BANK_SPLIT_NO_TARGETS";

  /**
   * Split deposit (REQ-BANK-043) whose percentage of the gross rounds to less than 1 aUEC, so there
   * is nothing to distribute. The requested split cannot be honoured — raise the amount or the
   * percentage.
   */
  public static final String CODE_BANK_SPLIT_TOO_SMALL = "BANK_SPLIT_TOO_SMALL";

  /**
   * Withdrawal/transfer (request or direct booking) leaving a {@link
   * de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
   * justification-mandating} account ({@code CARTEL}, {@code CARTEL_BANK}, {@code SPECIAL}) without
   * a non-blank justification (Begr&uuml;ndung). REQ-BANK-045.
   */
  public static final String CODE_BANK_JUSTIFICATION_REQUIRED = "BANK_JUSTIFICATION_REQUIRED";

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

  @Override
  public String typeSuffix() {
    return code.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @Override
  public String titleKey() {
    return keyBase() + ".title";
  }

  @Override
  public String detailKey() {
    return keyBase() + ".detail";
  }

  @Override
  public String logLabel() {
    return "Bank conflict";
  }

  @Override
  public Map<String, Object> extraProperties() {
    return properties;
  }

  @Override
  public Map<String, ?> logExtra() {
    return Map.of("bankCode", code);
  }

  /**
   * The {@code problem.<code>} bundle-key prefix {@link #titleKey()}/{@link #detailKey()} append
   * {@code .title}/{@code .detail} to, e.g. {@code "problem.bank_overdraft"} for {@link
   * #CODE_BANK_OVERDRAFT}.
   *
   * @return the bundle-key prefix derived from {@link #code}
   */
  private String keyBase() {
    return "problem." + code.toLowerCase(Locale.ROOT);
  }
}
