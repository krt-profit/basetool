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

package de.greluc.krt.profit.basetool.backend.admin.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the central transfer-fee setting and calculates fees from explicit snapshots. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfiguredTransferFeePolicy implements TransferFeePolicy {

  private static final String SETTING_KEY = "operation.transfer_fee_rate";
  private static final RateSnapshot DEFAULT_BASIS = new RateSnapshot(new BigDecimal("0.005"));

  private final SystemSettings systemSettings;

  /**
   * Captures the configured rate, falling back to 0.5% for absent or invalid settings.
   *
   * @return the validated immutable rate basis
   */
  @Override
  @Transactional(readOnly = true)
  public @NotNull RateSnapshot snapshot() {
    Optional<String> raw = systemSettings.getSettingValue(SETTING_KEY);
    if (raw.isEmpty() || raw.get().isBlank()) {
      log.warn(
          "System setting '{}' is missing or blank; using default transfer-fee rate", SETTING_KEY);
      return DEFAULT_BASIS;
    }
    try {
      return new RateSnapshot(new BigDecimal(raw.get().trim()));
    } catch (IllegalArgumentException _) {
      log.warn("System setting '{}' is invalid; using default transfer-fee rate", SETTING_KEY);
      return DEFAULT_BASIS;
    }
  }

  /**
   * Calculates the whole-aUEC fee using only the supplied basis.
   *
   * @param amount the payment amount before the fee
   * @param basis the submitted or historical rate basis
   * @return the fee rounded HALF_UP, or zero for a non-positive amount
   */
  @Override
  public @NotNull BigDecimal feeOn(@NotNull BigDecimal amount, @NotNull RateSnapshot basis) {
    Objects.requireNonNull(basis);
    if (amount.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return amount.multiply(basis.rate()).setScale(0, RoundingMode.HALF_UP);
  }
}
