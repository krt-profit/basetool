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
 * Frontend mirror of the backend {@code InventoryItemCreateDto} wire shape. Adding a field on one
 * side without the other surfaces only at render time in production — keep the two records aligned
 * field-for-field, in the same order (see auto-memory {@code
 * feedback_backend_frontend_dto_mirror}).
 *
 * <p>The trailing {@code owningOrgUnitId} field is the R5.d picker output: when non-null, the
 * backend stamps the new inventory row onto the picked org unit instead of the target user's home
 * Staffel. {@code null} preserves the legacy stamping path. The final {@code mergeStock} field is
 * the per-action stock-merge opt-in (REQ-INV-026): honoured only for an {@code SCU} material (a
 * {@code PIECE} book-in always merges); {@code null}/{@code false} keeps the row separate.
 *
 * <p>The trailing {@code jobOrderAllocations} / {@code missionAllocations} lists are the Variante-C
 * split-at-check-in payload (REQ-INV-027, R4): earmark parts of the new entry to several job orders
 * / missions with their own amounts. When non-empty they supersede the single {@code jobOrderId} /
 * {@code missionId}; {@code null}/empty falls back to the single scalar.
 */
public record InventoryItemCreateDto(
    UUID userId,
    UUID materialId,
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
