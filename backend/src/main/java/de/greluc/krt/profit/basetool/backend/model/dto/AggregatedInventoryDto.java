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

/**
 * Data transfer record carrying one row of the aggregated Lager overview ({@code GET
 * /api/v1/inventory/aggregated}). Catalog-discriminated since V220 (REQ-INV-029, ADR-0100): a
 * {@code catalog=MATERIAL} row aggregates one material with its quality figures and {@code gameItem
 * == null}; a {@code catalog=ITEM} row aggregates one game item with {@code material == null} and
 * {@code null} quality columns (game items carry no quality dimension, REQ-INV-028).
 *
 * @param material the aggregated material, or {@code null} for a game-item row.
 * @param gameItem the aggregated game item, or {@code null} for a material row.
 * @param quality the amount-weighted <em>average</em> quality across the material's stock; {@code
 *     null} for a game-item row.
 * @param maxQuality the <em>highest</em> quality available for the material (the best single
 *     entry's quality); {@code 0.0} when the material has no stock, {@code null} for a game-item
 *     row.
 * @param amount the total quantity in stock.
 */
public record AggregatedInventoryDto(
    MaterialDto material,
    InventoryGameItemReferenceDto gameItem,
    Double quality,
    Double maxQuality,
    Double amount) {}
