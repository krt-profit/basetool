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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code NeedDto}; must match it field for field. The maximum unit
 * price, the purchased amount and the UEX reference price are absent for a caller who may not read
 * them.
 *
 * @param id the need
 * @param orgUnitId the raising unit
 * @param orgUnitName the raising unit's name
 * @param orgUnitShorthand the raising unit's shorthand
 * @param material the material, or {@code null} for a game-item need
 * @param gameItem the game item, or {@code null} for a material need
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 * @param qualityTier the tier of a material need, or {@code null}
 * @param amount the amount asked for
 * @param fulfilledAmount what is covered so far
 * @param openAmount what is still missing
 * @param priority the priority, {@code LOW}, {@code NORMAL} or {@code HIGH}
 * @param status the lifecycle state
 * @param purchasable whether buying is allowed
 * @param purchaseReason why buying is allowed, or {@code null}
 * @param maxUnitPrice the highest unit price a deal may pay, or {@code null} when not readable
 * @param purchasedAmount the part covered by accepted deliveries, or {@code null}
 * @param uexReferencePrice the average UEX buy price, or {@code null}
 * @param jobOrderId the job order the need feeds, or {@code null}
 * @param canManage whether the caller manages the raising unit's needs
 * @param version the optimistic-locking version
 * @param createdAt when the need was raised
 */
@DtoMirror
public record NeedDto(
    UUID id,
    UUID orgUnitId,
    String orgUnitName,
    String orgUnitShorthand,
    SupplyRefDto material,
    SupplyRefDto gameItem,
    String quantityType,
    SupplyTierRefDto qualityTier,
    Double amount,
    Double fulfilledAmount,
    Double openAmount,
    @BackendEnumAsString String priority,
    @BackendEnumAsString String status,
    boolean purchasable,
    @BackendEnumAsString String purchaseReason,
    Long maxUnitPrice,
    Double purchasedAmount,
    Double uexReferencePrice,
    UUID jobOrderId,
    boolean canManage,
    Long version,
    Instant createdAt) {

  /**
   * The share of the amount that is covered, as a whole percent between 0 and 100.
   *
   * @return the percent, 0 for a need without an amount
   */
  public int fulfilledPercent() {
    if (amount == null || amount <= 0 || fulfilledAmount == null) {
      return 0;
    }
    return (int) Math.min(100, Math.round(fulfilledAmount * 100 / amount));
  }
}
