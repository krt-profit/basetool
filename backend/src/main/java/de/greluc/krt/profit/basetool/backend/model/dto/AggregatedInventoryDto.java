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
 * Data transfer record carrying Aggregated Inventory payload for the per-material Lager overview.
 *
 * @param material the aggregated material.
 * @param quality the amount-weighted <em>average</em> quality across the material's stock.
 * @param maxQuality the <em>highest</em> quality available for the material (the best single
 *     entry's quality); {@code 0.0} when the material has no stock.
 * @param amount the total quantity of the material in stock.
 */
public record AggregatedInventoryDto(
    MaterialDto material, Double quality, Double maxQuality, Double amount) {}
