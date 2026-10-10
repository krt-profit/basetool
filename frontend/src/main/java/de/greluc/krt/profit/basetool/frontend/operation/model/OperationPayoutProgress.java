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

package de.greluc.krt.profit.basetool.frontend.operation.model;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import java.math.BigDecimal;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Payout progress of one operation as the detail page's KPI bar, payout card and payout sum row
 * show it, folded from the payout rows the backend returned.
 *
 * @param participants number of payout rows
 * @param paidOut number of rows marked paid out
 * @param openAmount sum of the payout amounts not yet marked paid out
 * @param totalAmount sum of all payout amounts
 * @param donors number of rows whose preference is {@link PayoutPreference#DONATE}
 * @param paidPercent share of paid-out rows in percent, {@code 0} without rows
 */
public record OperationPayoutProgress(
    int participants,
    int paidOut,
    @NotNull BigDecimal openAmount,
    @NotNull BigDecimal totalAmount,
    int donors,
    double paidPercent) {

  /**
   * Folds the payout rows into their progress; a missing payout amount counts as zero.
   *
   * @param payouts the payout rows, may be {@code null}
   * @return the progress, all zero for {@code null} or no rows
   */
  @NotNull
  public static OperationPayoutProgress of(@Nullable List<OperationPayoutDto> payouts) {
    if (payouts == null || payouts.isEmpty()) {
      return new OperationPayoutProgress(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
    }
    int paid = 0;
    int donating = 0;
    BigDecimal open = BigDecimal.ZERO;
    BigDecimal total = BigDecimal.ZERO;
    for (OperationPayoutDto payout : payouts) {
      BigDecimal amount = payout.payoutAmount() != null ? payout.payoutAmount() : BigDecimal.ZERO;
      total = total.add(amount);
      if (payout.paidOut()) {
        paid++;
      } else {
        open = open.add(amount);
      }
      if (payout.payoutPreference() == PayoutPreference.DONATE) {
        donating++;
      }
    }
    return new OperationPayoutProgress(
        payouts.size(), paid, open, total, donating, paid * 100.0 / payouts.size());
  }
}
