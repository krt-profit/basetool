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
 * Write payload for booking a deposit (REQ-BANK-004/-005): money entered the bank and physically
 * landed with the named holder. The amount is whole-aUEC and strictly positive — the sign is
 * determined by the transaction type, never by the caller.
 *
 * <p><strong>Split deposit (REQ-BANK-043).</strong> When {@link #splitEnabled} is set the caller
 * additionally provides a whole-percent {@link #splitPercent} (1–100): that percentage of the gross
 * is distributed <em>evenly by count</em> across all active squadron accounts (excluding the named
 * account), and the named account is credited only the remainder. The percentage applies to the
 * whole gross; the resulting per-account amounts stay whole aUEC and sum back to the gross exactly
 * (largest-remainder distribution, see {@code BankLedgerService}). Both fields are absent (a plain
 * single-account deposit) unless the caller opts in.
 *
 * @param accountId the receiving account
 * @param holderId the player who physically received the money (REQ-BANK-003)
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the booking history and statements
 * @param splitEnabled whether to distribute {@link #splitPercent} of the gross across all squadron
 *     accounts (REQ-BANK-044)
 * @param splitPercent the whole-percent (1–100) of the gross to distribute; required when {@link
 *     #splitEnabled}, ignored otherwise
 * @param counterpartyUserId optional Einzahler — the member who handed the money in (REQ-BANK-044),
 *     distinct from the receiving holder; {@code null} when no counterparty is recorded
 * @param counterpartyOrgUnitId optional org unit the Einzahler belongs to; for a registered
 *     counterparty ({@code counterpartyUserId}) it is validated to be one of that user's
 *     memberships, for an external counterparty ({@code counterpartyExternalName}) it may be
 *     <em>any</em> active org unit (REQ-BANK-044, #994)
 * @param counterpartyExternalName optional Einzahler recorded as <strong>free text</strong> for a
 *     person <em>without</em> a basetool account (REQ-BANK-044, #994); mutually exclusive with
 *     {@code counterpartyUserId}. When set, the handle is snapshotted from this name and no {@code
 *     counterparty_user_id} FK is stored
 */
public record BankDepositRequest(
    @NotNull UUID accountId,
    @NotNull UUID holderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note,
    boolean splitEnabled,
    @Nullable @DecimalMin("1") @DecimalMax("100") @WholeNumber BigDecimal splitPercent,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    @Nullable @Size(max = 100) String counterpartyExternalName) {

  /**
   * Cross-field rule (REQ-BANK-043): a split deposit must carry a percentage; a non-split deposit
   * carries none. The numeric range/whole-number of {@link #splitPercent} is enforced by its own
   * field constraints — this only pins the presence/absence relationship to {@link #splitEnabled}.
   *
   * <p>Hidden from the OpenAPI document for the same reason as {@link
   * CreateBankBookingRequest#isSplitConfigConsistent()}: it is derived from the other fields, never
   * sent by a client, and accessor-order instability rewrote {@code openapi.json} between builds.
   *
   * @return {@code true} when the split flag and the percentage are consistent
   */
  @Schema(hidden = true)
  @AssertTrue(message = "A split deposit requires a percentage; a non-split deposit must omit it")
  public boolean isSplitConfigConsistent() {
    return splitEnabled ? splitPercent != null : splitPercent == null;
  }

  /**
   * Convenience constructor for a plain single-account deposit with no split and no recorded
   * counterparty: delegates to the canonical constructor with both defaults. Keeps the many call
   * sites and tests that predate REQ-BANK-043/-044 unchanged; inbound JSON is always deserialized
   * via the canonical (all-component) constructor, so this overload only serves programmatic
   * callers.
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
    this(accountId, holderId, amount, note, false, null, null, null, null);
  }

  /**
   * Convenience constructor for a split deposit with no recorded counterparty (REQ-BANK-043):
   * delegates to the canonical constructor with both counterparty fields {@code null}. The 5th/6th
   * parameter types (a primitive {@code boolean} + {@code BigDecimal}) distinguish this overload
   * from the counterparty one below; programmatic callers only, Jackson uses the canonical.
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
    this(accountId, holderId, amount, note, splitEnabled, splitPercent, null, null, null);
  }

  /**
   * Convenience constructor for a non-split deposit that records a counterparty (REQ-BANK-044):
   * delegates to the canonical constructor with the split disabled. The 5th/6th parameter types
   * (two {@code UUID}s) distinguish this overload from the split one above; programmatic callers
   * only, Jackson uses the canonical.
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
        false,
        null,
        counterpartyUserId,
        counterpartyOrgUnitId,
        null);
  }

  /**
   * Convenience constructor for a deposit with no external free-text counterparty (REQ-BANK-044,
   * #994) — the pre-#994 canonical shape. Delegates to the canonical constructor with {@code
   * counterpartyExternalName} {@code null}, keeping every call site that used the split +
   * registered-counterparty form unchanged; programmatic callers only, Jackson uses the canonical.
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
        splitEnabled,
        splitPercent,
        counterpartyUserId,
        counterpartyOrgUnitId,
        null);
  }
}
