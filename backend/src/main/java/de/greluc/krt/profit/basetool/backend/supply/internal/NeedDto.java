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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A need as the pages show it (REQ-SUP-003, REQ-SUP-006). The maximum unit price, the purchase
 * progress and the UEX reference price are left out of the response, not blanked, for a caller who
 * may not read them.
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
 * @param fulfilledAmount what the credited donations and deliveries cover
 * @param openAmount what is still missing
 * @param priority the priority
 * @param status the lifecycle state
 * @param purchasable whether the unit allows the need to be bought
 * @param purchaseReason why buying is allowed, or {@code null}
 * @param maxUnitPrice the highest unit price a deal may pay, only for the purchase audience
 * @param purchasedAmount the part covered by accepted deliveries, only for the purchase audience
 * @param uexReferencePrice the average UEX buy price, only for the purchase audience
 * @param jobOrderId the job order the need feeds, or {@code null}
 * @param canManage whether the caller manages the raising unit's needs
 * @param version the optimistic-locking version
 * @param createdAt when the need was raised
 */
public record NeedDto(
    UUID id,
    UUID orgUnitId,
    String orgUnitName,
    String orgUnitShorthand,
    @Nullable SupplyRefDto material,
    @Nullable SupplyRefDto gameItem,
    String quantityType,
    @Nullable SupplyTierRefDto qualityTier,
    Double amount,
    Double fulfilledAmount,
    Double openAmount,
    NeedPriority priority,
    NeedStatus status,
    boolean purchasable,
    @Nullable PurchaseReason purchaseReason,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Long maxUnitPrice,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Double purchasedAmount,
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Double uexReferencePrice,
    @Nullable UUID jobOrderId,
    boolean canManage,
    Long version,
    Instant createdAt) {}
