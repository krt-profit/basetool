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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.UUID;

/**
 * Internal projection of an active {@code blueprint} row reduced to its id and SC Wiki output name,
 * used by {@code BlueprintProductService} to resolve a normalized {@code product_key} back to a
 * representative recipe id (the recipe graph is then loaded by id). Not a controller-boundary DTO —
 * produced by a JPQL constructor expression and consumed only inside the service.
 *
 * @param id the blueprint primary key
 * @param outputName the SC Wiki output-item name (normalized, then grouped into a product)
 */
public record BlueprintIdNameRow(UUID id, String outputName) {}
