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
 * Computes the in-game aUEC transfer fee for a {@code WITHDRAWAL} and for a {@code TRANSFER} with a
 * holder change (ADR-0052, REQ-BANK-033); a {@code HOLDER_TRANSFER} is fee-free.
 *
 * <p>The fee is added on top and debited from the source, so the destination receives the entered
 * amount. The rate is the {@code operation.transfer_fee_rate} system setting shared with the
 * operation payout; the fee is rounded to whole aUEC.
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
   * Computes the fee on delivering {@code amount}: {@code round(amount × rate)} to whole aUEC
   * (HALF_UP), or zero for a non-positive amount.
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
   * Computes the gross debited from the source so that {@code amount} arrives: {@code amount +
   * feeOn(amount)}. The source account must cover it (REQ-BANK-006).
   *
   * @param amount the amount that must arrive at the destination
   * @return the gross to debit, never {@code null}
   */
  @NotNull
  public BigDecimal totalDebit(@NotNull BigDecimal amount) {
    return amount.add(feeOn(amount));
  }

  /**
   * Loads the transfer-fee rate from {@code system_setting}, falling back to {@link
   * #DEFAULT_TRANSFER_FEE_RATE} (logged at WARN) when it is absent, invalid or outside {@code [0,
   * 1)}.
   *
   * @return a non-null rate in {@code [0, 1)}
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
