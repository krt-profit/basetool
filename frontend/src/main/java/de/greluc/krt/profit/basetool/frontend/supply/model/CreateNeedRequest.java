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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code CreateNeedRequest}; must match it field for field.
 *
 * @param orgUnitId the raising unit
 * @param materialId the material, or {@code null} for a game item
 * @param gameItemId the game item, or {@code null} for a material
 * @param qualityTierId the tier of a material, or {@code null} for the base tier
 * @param amount the amount
 * @param priority the priority, or {@code null} for {@code NORMAL}
 * @param purchasable whether buying is allowed
 * @param purchaseReason why buying is allowed, required when purchasable
 * @param maxUnitPrice the highest whole-aUEC unit price, required when purchasable
 * @param jobOrderId the unit's job order the need feeds, or {@code null}
 */
@DtoMirror
public record CreateNeedRequest(
    @NotNull UUID orgUnitId,
    UUID materialId,
    UUID gameItemId,
    UUID qualityTierId,
    @NotNull @Positive Double amount,
    @BackendEnumAsString String priority,
    boolean purchasable,
    @BackendEnumAsString String purchaseReason,
    @Positive Long maxUnitPrice,
    UUID jobOrderId) {}
