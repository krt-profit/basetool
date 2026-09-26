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

import java.util.UUID;

/**
 * Internal projection row for the blueprint product search: one active {@code blueprint} recipe
 * reduced to the scalars {@code BlueprintProductService} groups into products.
 *
 * <p>Produced by a JPQL constructor expression; not a controller-boundary DTO.
 *
 * @param outputName the SC Wiki output-item name, grouped into a product after normalization
 * @param scwikiKey the recipe's Wiki key, surfaced as an example key for the product
 * @param manufacturerName the output item's manufacturer name, or {@code null} if unresolved
 * @param outputItemId id of the resolved output {@code game_item}, or {@code null} if unresolved
 */
public record BlueprintProductRow(
    String outputName, String scwikiKey, String manufacturerName, UUID outputItemId) {}
