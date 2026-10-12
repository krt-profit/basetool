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

import java.util.List;

/**
 * Boundary DTO for one named build slot of a blueprint, bundling the ingredient(s) that fill the
 * slot ({@link #ingredients}) with the stat contributions that slot makes to the crafted item
 * ({@link #modifiers}). This is the unit the admin blueprint page renders to show "which ingredient
 * delivers which stat".
 *
 * @param name display name of the slot (e.g. {@code "Emitter"})
 * @param groupKey Wiki internal key of the slot (e.g. {@code "EMITTER"})
 * @param requiredCount number of children that must be fulfilled within the slot
 * @param modifiers the stat contributions of this slot
 * @param ingredients the ingredient(s) that fill this slot
 */
public record BlueprintRequirementGroupDto(
    String name,
    String groupKey,
    Integer requiredCount,
    List<BlueprintRequirementModifierDto> modifiers,
    List<BlueprintRequirementIngredientDto> ingredients) {}
