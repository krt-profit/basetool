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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What a currency wipe offsets per account or per holder (REQ-PATCH-041, ADR-0384): the balance as
 * of the live time, unless that offset would take the current balance below zero; then the offset
 * stops at zero, so the balance ends at zero, or stays where it is when it is already negative. A
 * negative balance of the live time is offset exactly.
 *
 * @param offsets the amount to offset by id
 * @param clamped how many offsets stopped at zero
 */
record BankWipeOffsets(@NotNull @Unmodifiable Map<UUID, BigDecimal> offsets, int clamped) {

  /** Copies the offsets, so the record never shares a caller's map. */
  BankWipeOffsets {
    offsets = Map.copyOf(offsets);
  }

  /**
   * Derives the offsets from the balances of the live time and the current balances.
   *
   * @param atLiveTime the balance as of the live time by id
   * @param current the current balance by id; an id without one counts as zero
   * @return the offsets
   */
  static @NotNull BankWipeOffsets of(
      @NotNull Map<UUID, BigDecimal> atLiveTime, @NotNull Map<UUID, BigDecimal> current) {
    Map<UUID, BigDecimal> offsets = new HashMap<>();
    int clamped = 0;
    for (Map.Entry<UUID, BigDecimal> balance : atLiveTime.entrySet()) {
      BigDecimal offset = balance.getValue();
      if (offset.signum() > 0) {
        BigDecimal available = current.getOrDefault(balance.getKey(), BigDecimal.ZERO);
        if (available.compareTo(offset) < 0) {
          offset = available.max(BigDecimal.ZERO);
          clamped++;
        }
      }
      offsets.put(balance.getKey(), offset);
    }
    return new BankWipeOffsets(offsets, clamped);
  }

  /**
   * Sums the offsets.
   *
   * @return the total offset, in whole aUEC
   */
  @NotNull
  BigDecimal total() {
    return offsets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
