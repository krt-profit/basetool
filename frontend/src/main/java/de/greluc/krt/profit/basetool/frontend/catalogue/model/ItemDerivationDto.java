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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * Frontend mirror of the backend {@code ItemDerivationDto}: the material-derivation preview for one
 * blueprint at a given amount, feeding the item-order create form.
 *
 * @param blueprint the previewed blueprint
 * @param amount the previewed whole-unit amount
 * @param materials the resolved material requirements
 * @param subAssemblies adoptable sub-assembly suggestions
 * @param unresolvedIngredients names of ingredient lines with no resolved material/item
 */
@DtoMirror
public record ItemDerivationDto(
    BlueprintReferenceDto blueprint,
    Integer amount,
    List<DerivedMaterialDto> materials,
    List<SubAssemblySuggestionDto> subAssemblies,
    List<String> unresolvedIngredients) {}
