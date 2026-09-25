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
 * Write payload for booking a deposit (REQ-BANK-004): money entered the bank and landed with the
 * named holder.
 *
 * <p>With {@link #splitEnabled}, {@link #splitPercent} of the gross is distributed evenly across
 * all other active squadron accounts and the named account receives the remainder, all in whole
 * aUEC (REQ-BANK-043).
 *
 * @param accountId the receiving account
 * @param holderId the player who physically received the money (REQ-BANK-003)
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param staffNote optional internal note by the booking bank employee (REQ-BANK-054), hidden from
 *     the member-facing views
 * @param splitEnabled whether to distribute {@link #splitPercent} of the gross across the squadron
 *     accounts
 * @param splitPercent the whole percent (1–100) to distribute; required when {@link #splitEnabled},
 *     ignored otherwise
 * @param counterpartyUserId optional registered Einzahler who handed the money in (REQ-BANK-044);
 *     {@code null} when none is recorded
 * @param counterpartyOrgUnitId optional org unit of the Einzahler; one of the user's memberships
 *     for a registered counterparty, any active org unit for an external one
 * @param counterpartyExternalName optional Einzahler without a basetool account, as free text;
 *     mutually exclusive with {@code counterpartyUserId}
 */
public record BankDepositRequest(
    @NotNull UUID accountId,
    @NotNull UUID holderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    @Nullable @Size(max = 500) String staffNote,
    boolean splitEnabled,
    @Nullable @DecimalMin("1") @DecimalMax("100") @WholeNumber BigDecimal splitPercent,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    @Nullable @Size(max = 100) String counterpartyExternalName) {

  /**
   * Checks that a split deposit carries a percentage and a non-split deposit carries none
   * (REQ-BANK-043); the range of {@link #splitPercent} and its relation to {@link #splitEnabled}
   * are separate constraints. Hidden from OpenAPI because it is derived.
   *
   * @return {@code true} when the split flag and the percentage are consistent
   */
  @Schema(hidden = true)
  @AssertTrue(message = "A split deposit requires a percentage; a non-split deposit must omit it")
  public boolean isSplitConfigConsistent() {
    return splitEnabled ? splitPercent != null : splitPercent == null;
  }

  /**
   * Creates a plain single-account deposit with no split and no counterparty; for programmatic
   * callers only.
   *
   * @param accountId the receiving account
   * @param holderId the player who physically received the money
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   */
  public BankDepositRequest(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull BigDecimal amount,
      @Nullable String note) {
    this(accountId, holderId, amount, note, null, false, null, null, null, null);
  }

  /**
   * Creates a split deposit with no counterparty (REQ-BANK-043); for programmatic callers only.
   *
   * @param accountId the receiving account
   * @param holderId the player who physically received the money
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   * @param splitEnabled whether to distribute {@code splitPercent} across the squadron accounts
   * @param splitPercent the whole-percent (1–100) to distribute when {@code splitEnabled}
   */
  public BankDepositRequest(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull BigDecimal amount,
      @Nullable String note,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent) {
    this(accountId, holderId, amount, note, null, splitEnabled, splitPercent, null, null, null);
  }

  /**
   * Creates a non-split deposit with a registered counterparty (REQ-BANK-044); for programmatic
   * callers only.
   *
   * @param accountId the receiving account
   * @param holderId the player who physically received the money
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   * @param counterpartyUserId the Einzahler (member who handed the money in), or {@code null}
   * @param counterpartyOrgUnitId the Einzahler's org unit, or {@code null}
   */
  public BankDepositRequest(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable UUID counterpartyUserId,
      @Nullable UUID counterpartyOrgUnitId) {
    this(
        accountId,
        holderId,
        amount,
        note,
        null,
        false,
        null,
        counterpartyUserId,
        counterpartyOrgUnitId,
        null);
  }

  /**
   * Creates a deposit without an external free-text counterparty (REQ-BANK-044); for programmatic
   * callers only.
   *
   * @param accountId the receiving account
   * @param holderId the player who physically received the money
   * @param amount whole-aUEC amount, at least 1
   * @param note optional free-text note for the booking history and statements
   * @param splitEnabled whether to distribute {@code splitPercent} across the squadron accounts
   * @param splitPercent the whole-percent (1–100) to distribute when {@code splitEnabled}
   * @param counterpartyUserId the Einzahler (registered member), or {@code null}
   * @param counterpartyOrgUnitId the Einzahler's org unit, or {@code null}
   */
  public BankDepositRequest(
      @NotNull UUID accountId,
      @NotNull UUID holderId,
      @NotNull BigDecimal amount,
      @Nullable String note,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent,
      @Nullable UUID counterpartyUserId,
      @Nullable UUID counterpartyOrgUnitId) {
    this(
        accountId,
        holderId,
        amount,
        note,
        null,
        splitEnabled,
        splitPercent,
        counterpartyUserId,
        counterpartyOrgUnitId,
        null);
  }
}
