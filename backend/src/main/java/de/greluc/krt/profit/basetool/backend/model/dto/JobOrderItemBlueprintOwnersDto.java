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
 * Blueprint-coverage view of an {@code ITEM} job order: which members of the responsible org unit
 * own the blueprints for the requested items. Empty for {@code MATERIAL} orders.
 *
 * @param requiredBlueprints one row per required product with its owner count, sorted by product
 *     name; never {@code null}
 * @param owners members owning at least one required blueprint, sorted by name; never {@code null}
 */
public record JobOrderItemBlueprintOwnersDto(
    List<JobOrderRequiredBlueprintDto> requiredBlueprints,
    List<JobOrderBlueprintOwnerDto> owners) {}
