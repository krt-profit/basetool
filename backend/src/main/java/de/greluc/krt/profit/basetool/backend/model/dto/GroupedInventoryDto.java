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
 * Per-catalog-entry roll-up of the Lager for the {@code /grouped} list views. Each group carries
 * its squadron-wide totals and the list of {@link InventoryStackDto} stacks it breaks down into —
 * one stack per distinct stock identity. The stacks in turn hold the individual append-only
 * entries, so the UI renders Material/Item → Stack → Entries.
 *
 * <p>Catalog-discriminated since V220 (REQ-INV-029, ADR-0101): a {@code catalog=MATERIAL} group
 * carries {@code material} with the quality aggregates and {@code gameItem == null}; a {@code
 * catalog=ITEM} group carries {@code gameItem} with {@code material == null} and {@code null}
 * quality aggregates (game items have no quality dimension). Exactly one of the two references is
 * set per group.
 *
 * @param material the grouping material, or {@code null} for a game-item group
 * @param gameItem the grouping game item, or {@code null} for a material group
 * @param totalAmount the summed quantity across every stack of this group
 * @param averageQuality the amount-weighted mean quality across every stack; {@code null} for a
 *     game-item group
 * @param maxQuality the highest quality value seen across the stacks; {@code null} for a game-item
 *     group
 * @param stacks the per-stock-identity stacks this group breaks down into
 */
public record GroupedInventoryDto(
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    Double totalAmount,
    Double averageQuality,
    Integer maxQuality,
    List<InventoryStackDto> stacks) {}
