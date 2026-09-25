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
 * SC Wiki recipe graph of one owned blueprint's product, for the Personal Inventory blueprint view.
 *
 * <p>The graph is that of one representative recipe; {@link #variantCount} tells how many recipe
 * variants share the product.
 *
 * @param productName canonical display name of the product
 * @param variantCount number of recipe variants of the product; 0 when unresolved
 * @param requirementGroups the representative recipe's build slots with ingredients and per-quality
 *     stat modifiers; may be empty
 * @param ingredients flat ingredient list, used when {@link #requirementGroups} is empty
 */
public record PersonalBlueprintRecipeResponse(
    String productName,
    int variantCount,
    List<BlueprintRequirementGroupDto> requirementGroups,
    List<BlueprintRequirementIngredientDto> ingredients) {}
