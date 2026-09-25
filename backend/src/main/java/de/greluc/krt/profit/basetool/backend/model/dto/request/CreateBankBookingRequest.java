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

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for raising a confirm-before-post booking request (REQ-BANK-022/-039/-040). It
 * carries no holder; the bank employee records the holder(s) at confirmation.
 *
 * <p>A {@code DEPOSIT} may enable a split (REQ-BANK-043): the given percentage of the gross is
 * distributed evenly across the squadron accounts active at confirmation.
 *
 * @param sourceAccountId the (source) account the request acts on; the caller must be able to view
 *     it
 * @param type whether to request a deposit, a withdrawal or a transfer
 * @param targetAccountId the destination account for a {@code TRANSFER}; {@code null} otherwise
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note carried onto the booking on confirmation
 * @param justification optional Begr&uuml;ndung carried onto the booking (REQ-BANK-045); only for a
 *     {@code WITHDRAWAL} / {@code TRANSFER}, and required when the source account type {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}
 * @param splitEnabled whether a {@code DEPOSIT} distributes {@link #splitPercent} across squadron
 *     accounts (REQ-BANK-044)
 * @param splitPercent the whole percent (1–100) to distribute; required when {@link #splitEnabled},
 *     absent otherwise
 * @param counterpartyUserId the registered member receiving a {@code WITHDRAWAL} payout
 *     (REQ-BANK-055); {@code WITHDRAWAL}-only, and {@code null} derives the requester at
 *     confirmation
 * @param counterpartyOrgUnitId the org unit of {@link #counterpartyUserId}, which must be one of
 *     that user's direct memberships; requires a counterparty user
 */
public record CreateBankBookingRequest(
    @NotNull UUID sourceAccountId,
    @NotNull BankBookingRequestType type,
    @Nullable UUID targetAccountId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    boolean splitEnabled,
    @Nullable @DecimalMin("1") @DecimalMax("100") @WholeNumber BigDecimal splitPercent,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId) {

  /**
   * Cross-field rule (REQ-BANK-043): a split is DEPOSIT-only and, when enabled, requires a
   * percentage; otherwise no percentage is allowed. Hidden from the OpenAPI schema.
   *
   * @return {@code true} when the split flag, type and percentage are consistent
   */
  @Schema(hidden = true)
  @AssertTrue(message = "A split is only valid on a deposit and requires a percentage")
  public boolean isSplitConfigConsistent() {
    if (!splitEnabled) {
      return splitPercent == null;
    }
    return type == BankBookingRequestType.DEPOSIT && splitPercent != null;
  }

  /**
   * Cross-field rule (REQ-BANK-055): a counterparty is valid only on a {@code WITHDRAWAL}, and a
   * counterparty org unit requires a counterparty user. Hidden from the OpenAPI schema.
   *
   * @return {@code true} when the counterparty fields are consistent with the movement kind
   */
  @Schema(hidden = true)
  @AssertTrue(message = "A counterparty is only valid on a withdrawal and an org unit needs a user")
  public boolean isCounterpartyConfigConsistent() {
    if (counterpartyUserId == null) {
      return counterpartyOrgUnitId == null;
    }
    return type == BankBookingRequestType.WITHDRAWAL;
  }

  /**
   * Creates a request without a split and without a named counterparty.
   *
   * @param sourceAccountId the (source) account
   * @param type the movement kind
   * @param targetAccountId the transfer destination, or {@code null}
   * @param amount whole-aUEC amount
   * @param note optional note
   */
  public CreateBankBookingRequest(
      @NotNull UUID sourceAccountId,
      @NotNull BankBookingRequestType type,
      @Nullable UUID targetAccountId,
      @NotNull BigDecimal amount,
      @Nullable String note) {
    this(sourceAccountId, type, targetAccountId, amount, note, null, false, null, null, null);
  }
}
