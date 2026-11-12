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

package de.greluc.krt.profit.basetool.backend.admin.api;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/** Supplies the configured transfer-fee basis and exact whole-aUEC fees (ADR-0358). */
public interface TransferFeePolicy {

  /**
   * Captures the validated configured rate for a new payment.
   *
   * @return an immutable basis that callers retain with submitted monetary terms
   */
  @NotNull
  RateSnapshot snapshot();

  /**
   * Calculates a whole-aUEC HALF_UP fee without reading current settings.
   *
   * @param amount the payment amount before the fee
   * @param basis the submitted or historical rate basis
   * @return the exact rounded fee, or zero for a non-positive amount
   */
  @NotNull
  BigDecimal feeOn(@NotNull BigDecimal amount, @NotNull RateSnapshot basis);

  /**
   * Immutable, numerically normalized rate basis for retained monetary terms.
   *
   * @param rate the transfer-fee fraction in {@code [0, 1)}
   */
  record RateSnapshot(@NotNull BigDecimal rate) {

    /**
     * Validates and normalizes a configured or restored rate basis.
     *
     * @param rate the transfer-fee fraction
     * @throws IllegalArgumentException when the rate is outside {@code [0, 1)}
     */
    public RateSnapshot {
      if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
        throw new IllegalArgumentException("Transfer-fee rate must be in [0, 1)");
      }
      rate = rate.stripTrailingZeros();
    }
  }
}
