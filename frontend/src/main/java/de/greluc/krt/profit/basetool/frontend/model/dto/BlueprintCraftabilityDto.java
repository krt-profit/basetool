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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code BlueprintCraftabilityDto}: how often one owned blueprint
 * can be crafted from the caller's stock, with and without open refinery yield.
 *
 * @param blueprintId the owned blueprint's id
 * @param recipeResolved whether an active recipe backs the owned product
 * @param hasItemIngredients whether the recipe needs an ITEM ingredient that is not evaluated
 * @param hasResourceIngredients whether the recipe has any evaluable material requirement
 * @param craftable how many crafts the inventory stock alone allows
 * @param craftableWithRefinery how many crafts inventory plus open refinery yield allows
 * @param limitingMaterialName the commodity capping the inventory-only count, or {@code null}
 * @param limitingMaterialNameWithRefinery the commodity capping the refinery-included count, or
 *     {@code null}
 * @param groups per-requirement-group overlay, in recipe order
 * @param materials per-material breakdown
 */
public record BlueprintCraftabilityDto(
    UUID blueprintId,
    boolean recipeResolved,
    boolean hasItemIngredients,
    boolean hasResourceIngredients,
    int craftable,
    int craftableWithRefinery,
    String limitingMaterialName,
    String limitingMaterialNameWithRefinery,
    List<CraftabilityGroupDto> groups,
    List<CraftabilityMaterialDto> materials) {}
