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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code InventoryItemCreateDto}; fields must match the backend
 * record in name and order.
 *
 * <p>Exactly one of {@code materialId} / {@code gameItemId} is set (REQ-INV-029). {@code
 * owningOrgUnitId} overrides the owner's home Staffel when non-null. {@code mergeStock}
 * (REQ-INV-026) is honoured only for an {@code SCU} material. Non-empty {@code jobOrderAllocations}
 * / {@code missionAllocations} (REQ-INV-027) supersede the single {@code jobOrderId} / {@code
 * missionId}.
 */
public record InventoryItemCreateDto(
    UUID userId,
    UUID materialId,
    UUID gameItemId,
    UUID locationId,
    Integer quality,
    Double amount,
    Boolean personal,
    UUID missionId,
    UUID jobOrderId,
    UUID owningOrgUnitId,
    Boolean mergeStock,
    List<InventoryAllocationInput> jobOrderAllocations,
    List<InventoryAllocationInput> missionAllocations) {}
