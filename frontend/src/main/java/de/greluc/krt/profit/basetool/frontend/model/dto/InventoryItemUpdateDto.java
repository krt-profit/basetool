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

import java.util.UUID;

/**
 * Data transfer record carrying Inventory Item Update payload.
 *
 * <p>The trailing {@code mergeStock} field is the per-action stock-merge opt-in (REQ-INV-026):
 * honoured only for an {@code SCU} material (a {@code PIECE} edit always merges); {@code
 * null}/{@code false} keeps the row separate.
 */
public record InventoryItemUpdateDto(
    UUID materialId,
    UUID locationId,
    Integer quality,
    Double amount,
    Boolean personal,
    UUID jobOrderId,
    UUID missionId,
    Long version,
    Boolean mergeStock) {}
