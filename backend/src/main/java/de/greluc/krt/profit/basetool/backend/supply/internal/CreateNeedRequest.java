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

import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Raises a need (REQ-SUP-003): exactly one of the material and the game item, the amount and, for a
 * material, the quality tier; a purchasable need carries a reason and a maximum unit price
 * (REQ-SUP-004).
 *
 * @param orgUnitId the raising unit
 * @param materialId the material, or {@code null} for a game item
 * @param gameItemId the game item, or {@code null} for a material
 * @param qualityTierId the tier of a material, or {@code null} for the base tier
 * @param amount the amount, whole units for pieces and game items
 * @param priority the priority, {@code NORMAL} when omitted
 * @param purchasable whether buying is allowed
 * @param purchaseReason why buying is allowed, required when purchasable
 * @param maxUnitPrice the highest whole-aUEC unit price, required when purchasable
 * @param jobOrderId the unit's job order the need feeds, or {@code null}
 */
public record CreateNeedRequest(
    @NotNull UUID orgUnitId,
    @Nullable UUID materialId,
    @Nullable UUID gameItemId,
    @Nullable UUID qualityTierId,
    @NotNull @Positive Double amount,
    @Nullable NeedPriority priority,
    boolean purchasable,
    @Nullable PurchaseReason purchaseReason,
    @Nullable @Positive Long maxUnitPrice,
    @Nullable UUID jobOrderId) {}
