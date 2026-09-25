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
 * One row of the aggregated Lager overview ({@code GET /api/v1/inventory/aggregated}), either a
 * material row ({@code gameItem == null}) or a game-item row ({@code material == null}, no quality
 * figures) (REQ-INV-029).
 *
 * @param material the aggregated material, or {@code null} for a game-item row
 * @param gameItem the aggregated game item, or {@code null} for a material row
 * @param quality amount-weighted average quality; {@code null} for a game-item row
 * @param maxQuality highest single-entry quality; {@code 0.0} without stock, {@code null} for a
 *     game-item row
 * @param amount total quantity in stock
 */
public record AggregatedInventoryDto(
    MaterialDto material,
    InventoryGameItemReferenceDto gameItem,
    Double quality,
    Double maxQuality,
    Double amount) {}
