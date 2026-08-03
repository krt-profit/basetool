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
 * Write payload for a caller raising a confirm-before-post booking request
 * (REQ-BANK-022/-039/-040). The caller names the (source) account they act on — any account they
 * may <em>view</em> — the movement kind and a whole-aUEC amount. For a {@code TRANSFER} the {@code
 * targetAccountId} names the destination (any active account); it is required for {@code TRANSFER}
 * and must be absent for {@code DEPOSIT} / {@code WITHDRAWAL} (the service enforces this). There is
 * deliberately <strong>no holder field</strong> — the holder(s) are recorded by the bank employee
 * at confirmation, not by the requester.
 *
 * <p><strong>Split deposit (REQ-BANK-043).</strong> A {@code DEPOSIT} request may set {@link
 * #splitEnabled} with a whole-percent {@link #splitPercent} (1–100): that percentage of the gross
 * is distributed evenly across all squadron accounts when the request is confirmed (the percentage
 * is snapshotted now; the concrete legs are resolved against the squadron-account set active at
 * confirmation). The split is <em>DEPOSIT-only</em> — a withdrawal/transfer request must not carry
 * it.
 *
 * @param sourceAccountId the (source) account the request acts on (the caller must be able to view
 *     it)
 * @param type whether to request a deposit, a withdrawal or a transfer
 * @param targetAccountId the destination account for a {@code TRANSFER}; {@code null} otherwise
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note carried onto the booking on confirmation
 * @param justification optional free-text justification (Begr&uuml;ndung) carried onto the booking
 *     on confirmation (REQ-BANK-045); captured only for a {@code WITHDRAWAL} / {@code TRANSFER} and
 *     required by the service when the source account type {@linkplain
 *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
 *     mandates a reason}, optional otherwise
 * @param splitEnabled whether a {@code DEPOSIT} distributes {@link #splitPercent} across squadron
 *     accounts (REQ-BANK-044)
 * @param splitPercent the whole-percent (1–100) to distribute; required when {@link #splitEnabled},
 *     absent otherwise
 */
public record CreateBankBookingRequest(
    @NotNull UUID sourceAccountId,
    @NotNull BankBookingRequestType type,
    @Nullable UUID targetAccountId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String justification,
    boolean splitEnabled,
    @Nullable @DecimalMin("1") @DecimalMax("100") @WholeNumber BigDecimal splitPercent) {

  /**
   * Cross-field rule (REQ-BANK-043): the split is DEPOSIT-only and, when enabled, must carry a
   * percentage; otherwise no percentage is allowed. The numeric range/whole-number of {@link
   * #splitPercent} is enforced by its own field constraints.
   *
   * <p>{@code @Schema(hidden = true)} keeps this derived guard out of the generated OpenAPI
   * document: it is computed from the other fields and is never part of the request payload.
   * Accessor-derived schema properties are also harvested in unguaranteed {@code
   * Class#getDeclaredMethods()} order, which rewrote {@code openapi.json} between builds (see
   * {@code OpenApiDerivedPropertyTest}).
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
   * Convenience constructor for a request without a split (the pre-REQ-BANK-043 shape), delegating
   * to the canonical constructor with the split disabled.
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
    this(sourceAccountId, type, targetAccountId, amount, note, null, false, null);
  }
}
