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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for booking a withdrawal (REQ-BANK-004/-005): money left the bank, paid out by the
 * named holder. Guarded by the no-overdraft rule at <strong>account</strong> level only
 * (REQ-BANK-006, ADR-0039) — the holder may go negative; the amount is whole-aUEC and strictly
 * positive — the sign comes from the transaction type.
 *
 * @param accountId the paying account
 * @param holderId the player who physically paid the money out (REQ-BANK-003)
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param justification optional free-text justification (Begr&uuml;ndung) for the booking history
 *     and statements (REQ-BANK-045); required by the service when the paying account type
 *     {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}, optional otherwise
 * @param counterpartyUserId optional Empf&auml;nger — the member who received the payout
 *     (REQ-BANK-044), distinct from the paying holder; {@code null} when no counterparty is
 *     recorded
 * @param counterpartyOrgUnitId optional org unit the Empf&auml;nger belongs to; for a registered
 *     counterparty ({@code counterpartyUserId}) it is validated to be one of that user's
 *     memberships, for an external counterparty ({@code counterpartyExternalName}) it may be
 *     <em>any</em> active org unit (REQ-BANK-044, #994)
 * @param feeInclusive fee-mode toggle (REQ-BANK-033, #999): {@code false} (default, unchanged)
 *     means the entered {@code amount} is what must arrive and the in-game fee is added on top —
 *     the source is debited {@code amount + fee}; {@code true} means the entered {@code amount} is
 *     the gross debited and the recipient receives {@code amount - fee}. A bank-staff choice at
 *     booking time only — a withdrawal <em>request</em> never carries it (confirmation always books
 *     on-top)
 * @param counterpartyExternalName optional Empf&auml;nger recorded as <strong>free text</strong>
 *     for a person <em>without</em> a basetool account (REQ-BANK-044, #994); mutually exclusive
 *     with {@code counterpartyUserId}. When set, the handle is snapshotted from this name and no
 *     {@code counterparty_user_id} FK is stored
 */
public record BankWithdrawalRequest(
    @NotNull UUID accountId,
    @NotNull UUID holderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    boolean feeInclusive,
    @Nullable @Size(max = 100) String counterpartyExternalName) {

  /**
   * Convenience constructor for a withdrawal with <strong>no</strong> recorded justification or
   * counterparty (REQ-BANK-044/-045) and the default on-top fee mode (REQ-BANK-033) — the common
   * case where neither is captured. Delegates to the canonical constructor with the justification
   * and both counterparty fields {@code null}, {@code feeInclusive} {@code false} and no external
   * counterparty. Inbound JSON is always deserialized via the canonical (all-component)
   * constructor, so this overload only serves programmatic callers.
   *
   * @param accountId the paying account
   * @param holderId the player who physically paid the money out
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   */
  public BankWithdrawalRequest(
      UUID accountId, UUID holderId, BigDecimal amount, @Nullable String note) {
    this(accountId, holderId, amount, note, null, null, null, false, null);
  }

  /**
   * Convenience constructor for a withdrawal with no external free-text counterparty (REQ-BANK-044,
   * #994) — the pre-#994 canonical shape (justification + registered counterparty + fee mode).
   * Delegates to the canonical constructor with {@code counterpartyExternalName} {@code null},
   * keeping every existing call site (the request-confirmation path and the fee-mode tests)
   * unchanged; programmatic callers only, Jackson uses the canonical.
   *
   * @param accountId the paying account
   * @param holderId the player who physically paid the money out
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   * @param justification optional free-text justification (Begr&uuml;ndung)
   * @param counterpartyUserId the Empf&auml;nger (registered member), or {@code null}
   * @param counterpartyOrgUnitId the Empf&auml;nger's org unit, or {@code null}
   * @param feeInclusive the fee mode (REQ-BANK-033); {@code false} is the default on-top mode
   */
  public BankWithdrawalRequest(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID counterpartyUserId,
      @Nullable UUID counterpartyOrgUnitId,
      boolean feeInclusive) {
    this(
        accountId,
        holderId,
        amount,
        note,
        justification,
        counterpartyUserId,
        counterpartyOrgUnitId,
        feeInclusive,
        null);
  }
}
