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
import java.util.UUID;

/**
 * Craftability of one of the caller's owned blueprints, computed from the caller's own stock: how
 * often it can be crafted, the resulting quality and what is missing.
 *
 * <p>Each count is given from inventory alone ({@code craftable}) and with open refinery yield
 * ({@code craftableWithRefinery}), so the refinery toggle needs no refetch.
 *
 * @param blueprintId the owned blueprint's id
 * @param recipeResolved whether an active recipe was found for the owned product
 * @param hasItemIngredients whether the recipe needs an ITEM ingredient that is not evaluated
 * @param hasResourceIngredients whether the recipe has any evaluable material requirement
 * @param craftable how many crafts the inventory stock alone allows
 * @param craftableWithRefinery how many crafts the inventory plus open refinery yield allows
 * @param limitingMaterialName the material capping the inventory-only count, or {@code null}
 * @param limitingMaterialNameWithRefinery the commodity capping the refinery-included count, or
 *     {@code null}
 * @param groups per-requirement-group overlay, in recipe order
 * @param materials per-material breakdown (required / available / effective quality / missing)
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
