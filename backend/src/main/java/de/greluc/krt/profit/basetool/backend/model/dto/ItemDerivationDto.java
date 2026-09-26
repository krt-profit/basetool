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
 * Derivation preview of one blueprint at a given amount for the item-order create form, including
 * ingredient lines that could not be resolved to a material.
 *
 * @param blueprint the blueprint this preview was derived from
 * @param amount the previewed whole-unit amount the quantities were scaled by
 * @param materials the resolved material requirements
 * @param subAssemblies adoptable sub-assembly (ITEM ingredient) suggestions
 * @param unresolvedIngredients display names of ingredient lines with no resolved material/item
 */
public record ItemDerivationDto(
    BlueprintReferenceDto blueprint,
    Integer amount,
    List<DerivedMaterialDto> materials,
    List<SubAssemblySuggestionDto> subAssemblies,
    List<String> unresolvedIngredients) {}
