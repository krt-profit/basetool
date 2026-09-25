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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the backend {@code PersonalBlueprintRecipeResponse}: the SC Wiki recipe of one
 * owned blueprint's product, for the "Zutaten &amp; Stats" detail of the blueprint view.
 *
 * @param productName canonical display name of the product
 * @param variantCount number of recipe variants collapsing into the product
 * @param requirementGroups the representative recipe's build slots with ingredients + stat
 *     modifiers
 * @param ingredients flat ingredient list used as the fallback when {@code requirementGroups} is
 *     empty
 */
public record PersonalBlueprintRecipeDto(
    String productName,
    int variantCount,
    List<BlueprintRequirementGroupDto> requirementGroups,
    List<BlueprintRequirementIngredientDto> ingredients) {}
