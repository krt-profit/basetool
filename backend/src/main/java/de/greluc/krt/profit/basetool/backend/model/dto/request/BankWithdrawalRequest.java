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
 * Write payload for booking a withdrawal (REQ-BANK-004): money left the bank, paid out by the named
 * holder. The account may not be overdrawn, the holder may go negative (REQ-BANK-006).
 *
 * @param accountId the paying account
 * @param holderId the player who physically paid the money out (REQ-BANK-003)
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param justification optional Begr&uuml;ndung (REQ-BANK-045); required when the paying account
 *     type {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}
 * @param staffNote optional internal note by the booking bank employee (REQ-BANK-054), hidden from
 *     the member-facing views
 * @param counterpartyUserId optional registered Empf&auml;nger of the payout (REQ-BANK-044); {@code
 *     null} when none is recorded
 * @param counterpartyOrgUnitId optional org unit of the Empf&auml;nger; one of the user's
 *     memberships for a registered counterparty, any active org unit for an external one
 * @param feeInclusive fee mode (REQ-BANK-033): {@code false} debits {@code amount + fee}, {@code
 *     true} debits {@code amount} and pays out {@code amount - fee}
 * @param counterpartyExternalName optional Empf&auml;nger without a basetool account, as free text;
 *     mutually exclusive with {@code counterpartyUserId}
 */
public record BankWithdrawalRequest(
    @NotNull UUID accountId,
    @NotNull UUID holderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    @Nullable @Size(max = 500) String staffNote,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    boolean feeInclusive,
    @Nullable @Size(max = 100) String counterpartyExternalName) {

  /**
   * Creates a withdrawal with no justification or counterparty in the default on-top fee mode; for
   * programmatic callers only.
   *
   * @param accountId the paying account
   * @param holderId the player who physically paid the money out
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   */
  public BankWithdrawalRequest(
      UUID accountId, UUID holderId, BigDecimal amount, @Nullable String note) {
    this(accountId, holderId, amount, note, null, null, null, null, false, null);
  }

  /**
   * Creates a withdrawal without an external free-text counterparty; for programmatic callers only.
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
        null,
        counterpartyUserId,
        counterpartyOrgUnitId,
        feeInclusive,
        null);
  }
}
