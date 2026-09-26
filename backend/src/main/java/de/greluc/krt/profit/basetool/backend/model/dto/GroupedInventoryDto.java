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

import java.util.List;

/**
 * Per-catalog-entry roll-up of the Lager: the totals of one material or game item and the stacks it
 * breaks down into (REQ-INV-029).
 *
 * <p>Exactly one of {@code material} and {@code gameItem} is set; game-item groups carry {@code
 * null} quality aggregates.
 *
 * @param material the grouping material, or {@code null} for a game-item group
 * @param gameItem the grouping game item, or {@code null} for a material group
 * @param totalAmount the summed quantity across all stacks
 * @param averageQuality amount-weighted mean quality; {@code null} for a game-item group
 * @param maxQuality highest quality across the stacks; {@code null} for a game-item group
 * @param stacks the per-stock-identity stacks of this group
 */
public record GroupedInventoryDto(
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    Double totalAmount,
    Double averageQuality,
    Integer maxQuality,
    List<InventoryStackDto> stacks) {}
