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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.validation.QuantityAware;
import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One entry of a refinery order's store dialog.
 *
 * <ul>
 *   <li>{@code amount} is the stored quantity, validated via {@link ValidQuantityAmount} / {@link
 *       QuantityAware}.
 *   <li>{@code note} is copied to the resulting {@code InventoryItem}.
 *   <li>{@code owningOrgUnitId} stamps the item's owning OrgUnit via {@code
 *       OwnerScopeService.resolveOrgUnitForPickerOutputNullable}; an inadmissible pick is rejected
 *       with 400.
 *   <li>{@code personal} marks the item as the receiver's private stock (REQ-INV-035); {@code null}
 *       means {@code false}, and combining it with {@code jobOrderId} is rejected with 400.
 * </ul>
 */
@ValidQuantityAmount
public record RefineryOrderStoreItemDto(
    @NotNull UUID materialId,
    @NotNull UUID locationId,
    @NotNull @Min(0) @Max(1000) Integer quality,
    @NotNull Double amount,
    UUID userId,
    UUID jobOrderId,
    @Size(max = 1000) String note,
    UUID owningOrgUnitId,
    Boolean personal)
    implements QuantityAware {}
