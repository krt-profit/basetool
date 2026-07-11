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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the in-game aUEC transfer fee for the bank's customer-facing holder-initiated transfers
 * (ADR-0052 superseding ADR-0041, REQ-BANK-033). It applies to a {@code WITHDRAWAL} and to an
 * account-to-account {@code TRANSFER} with a holder change — where the bank moves money on a
 * member's behalf and the game skims a small percentage. A holder→holder {@code HOLDER_TRANSFER}
 * (the internal Umbuchung at {@code /bank/manage?tab=halter}) is deliberately
 * <strong>fee-free</strong>: the staff reconciling their stashes bear that in-game fee personally,
 * so it is not the bank's concern.
 *
 * <p><strong>The fee is added on top and borne by the debited source (ADR-0052).</strong> The
 * entered amount is the amount that must <em>arrive</em> at the destination; the fee is added on
 * top and the source (account + holder stash) is debited the {@link #totalDebit(BigDecimal) gross}
 * ({@code amount + fee}), so the destination receives the full entered amount and the bank — not
 * the staffer — absorbs the fee. This is the inverse of the original carve-out model (ADR-0041),
 * where the entered amount was the gross sent and the destination received less.
 *
 * <p><strong>Single rate for the whole org.</strong> The rate is the same runtime-editable setting
 * the operation payout uses ({@code operation.transfer_fee_rate} in {@code system_setting}, default
 * {@code 0.005} = 0.5%, editable at {@code /admin/settings}); the bank deliberately reuses it so
 * one knob governs every fee calculation (the requester chose "same rate as operations"). The
 * resolution logic mirrors {@code OperationPayoutService#resolveTransferFeeRate} on purpose — both
 * read the identical key and fall back to the identical default — so the two stay in lock-step.
 *
 * <p>The bank rounds the fee to <strong>whole aUEC</strong> (mobiGlas transfers carry no fractional
 * aUEC), unlike the operation payout which keeps the fee at scale 2 inside its own breakdown.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankTransferFeeService {

  /**
   * Key of the in-game transfer-fee rate in {@code system_setting} — intentionally the operation
   * payout's key so a single setting governs both surfaces (REQ-BANK-033).
   */
  static final String TRANSFER_FEE_RATE_SETTING_KEY = "operation.transfer_fee_rate";

  /** Fallback rate (0.5%) used when the setting is missing, blank, unparseable or out of range. */
  static final BigDecimal DEFAULT_TRANSFER_FEE_RATE = new BigDecimal("0.005");

  /** Exclusive upper bound: a rate &gt;= 1 would consume the entire transfer and is rejected. */
  static final BigDecimal MAX_TRANSFER_FEE_RATE = BigDecimal.ONE;

  private final SystemSettingService systemSettingService;

  /**
   * The in-game transfer fee charged on delivering {@code amount} to a destination: {@code
   * round(amount × rate)} to whole aUEC (HALF_UP). Under the fee-on-top model (ADR-0052) this fee
   * is added on top of {@code amount} and borne by the source, so a 0.5% rate turns a 500 000
   * transfer into a 2 500 fee. Returns {@link BigDecimal#ZERO} for a non-positive amount (defensive
   * — callers pass validated positive amounts).
   *
   * @param amount the amount that must arrive at the destination
   * @return the whole-aUEC fee, never {@code null} and never negative
   */
  @NotNull
  public BigDecimal feeOn(@NotNull BigDecimal amount) {
    if (amount.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return amount.multiply(resolveTransferFeeRate()).setScale(0, RoundingMode.HALF_UP);
  }

  /**
   * The gross amount debited from the source so that {@code amount} arrives at the destination:
   * {@code amount + feeOn(amount)} (ADR-0052). With the seeded 0.5% rate, {@code
   * totalDebit(500000)} is {@code 502500}. This is what the source account and holder stash are
   * charged; it must be covered by the source account (REQ-BANK-006) or the booking is refused.
   *
   * @param amount the amount that must arrive at the destination
   * @return the gross to debit from the source, never {@code null}
   */
  @NotNull
  public BigDecimal totalDebit(@NotNull BigDecimal amount) {
    return amount.add(feeOn(amount));
  }

  /**
   * Loads and validates the runtime-editable in-game transfer-fee rate from {@code system_setting}.
   * Falls back to {@link #DEFAULT_TRANSFER_FEE_RATE} when the row is absent, blank, not a number,
   * negative or {@code >= 1}; the fallback is logged at WARN so an operator sees the
   * misconfiguration. Mirrors {@code OperationPayoutService#resolveTransferFeeRate} (same key, same
   * default) so the bank and operations always compute off the identical rate.
   *
   * @return a non-null, validated rate in {@code [0, 1)}
   */
  @NotNull
  public BigDecimal resolveTransferFeeRate() {
    Optional<String> raw = systemSettingService.getSettingValue(TRANSFER_FEE_RATE_SETTING_KEY);
    if (raw.isEmpty() || raw.get().isBlank()) {
      log.warn(
          "System setting '{}' is missing or blank, falling back to default {}",
          TRANSFER_FEE_RATE_SETTING_KEY,
          DEFAULT_TRANSFER_FEE_RATE);
      return DEFAULT_TRANSFER_FEE_RATE;
    }
    try {
      BigDecimal parsed = new BigDecimal(raw.get().trim());
      if (parsed.signum() < 0 || parsed.compareTo(MAX_TRANSFER_FEE_RATE) >= 0) {
        log.warn(
            "System setting '{}'={} is out of range [0, 1), falling back to default {}",
            TRANSFER_FEE_RATE_SETTING_KEY,
            parsed,
            DEFAULT_TRANSFER_FEE_RATE);
        return DEFAULT_TRANSFER_FEE_RATE;
      }
      return parsed;
    } catch (NumberFormatException e) {
      log.warn(
          "System setting '{}'='{}' is not a valid decimal, falling back to default {}",
          TRANSFER_FEE_RATE_SETTING_KEY,
          raw.get(),
          DEFAULT_TRANSFER_FEE_RATE);
      return DEFAULT_TRANSFER_FEE_RATE;
    }
  }
}
