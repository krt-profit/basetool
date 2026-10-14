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

package de.greluc.krt.profit.basetool.backend.supply.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What another module may read of a need: the raising unit, the catalogue entry and tier, the
 * progress and the purchase release (REQ-SUP-003 … REQ-SUP-005). The maximum unit price is part of
 * it, so only callers that already passed their own gate for the purchase fields may expose it.
 *
 * @param id the need
 * @param orgUnitId the raising unit
 * @param materialId the material, or {@code null} for a game-item need
 * @param gameItemId the game item, or {@code null} for a material need
 * @param qualityTierId the tier a material must reach, or {@code null} for a game item
 * @param minQuality the lowest quality that satisfies the tier, {@code 0} for a game item
 * @param label the name of the material or game item
 * @param quantityPiece whether the entry is counted in whole units
 * @param amount the amount asked for
 * @param fulfilledAmount what donations and accepted deliveries cover together
 * @param purchasedAmount the part of the covered amount that came from accepted deliveries
 * @param purchasable whether the raising unit allows buying
 * @param maxUnitPrice the highest whole-aUEC unit price a deal may pay, or {@code null}
 * @param status the need's state
 */
public record NeedRef(
    @NotNull UUID id,
    @NotNull UUID orgUnitId,
    @Nullable UUID materialId,
    @Nullable UUID gameItemId,
    @Nullable UUID qualityTierId,
    int minQuality,
    @NotNull String label,
    boolean quantityPiece,
    double amount,
    double fulfilledAmount,
    double purchasedAmount,
    boolean purchasable,
    @Nullable Long maxUnitPrice,
    @NotNull NeedStatus status) {

  /**
   * Returns what the need still asks for.
   *
   * @return the amount not yet covered, never negative
   */
  public double openAmount() {
    return Math.max(0.0, amount - fulfilledAmount);
  }
}
