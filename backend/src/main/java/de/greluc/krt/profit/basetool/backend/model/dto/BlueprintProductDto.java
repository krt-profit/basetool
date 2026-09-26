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
 * One searchable blueprint product: all active recipes whose output name normalizes to the same
 * {@link #productKey}, collapsed into one entry.
 *
 * @param productKey normalized product identity (matches {@code personal_blueprint.product_key})
 * @param name display name of the product (original SC Wiki spelling)
 * @param variantCount number of active blueprint recipes that produce this product
 * @param manufacturerName manufacturer of the produced item, or {@code null} if unresolved
 * @param exampleKey one representative SC Wiki recipe key, or {@code null}
 * @param ownedByCurrentUser whether the calling user already owns this product
 */
public record BlueprintProductDto(
    String productKey,
    String name,
    int variantCount,
    String manufacturerName,
    String exampleKey,
    boolean ownedByCurrentUser) {}
