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

/**
 * Boundary DTO for one ingredient line of a blueprint. The {@link #name} is the Wiki name snapshot
 * persisted on the ingredient (always present, so rendering it never triggers a material /
 * game-item load). {@link #kind} is the {@code RESOURCE} / {@code ITEM} discriminator; the matching
 * quantity field is populated per kind.
 *
 * @param kind {@code "RESOURCE"} or {@code "ITEM"}
 * @param name display name of the ingredient (Wiki snapshot)
 * @param quantityScu amount for a RESOURCE line, else {@code null}; in the resolved material's
 *     {@link de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType} unit — fractional
 *     SCU for an SCU material, a whole piece count for a PIECE material
 * @param quantityUnits whole-unit count for an ITEM line, else {@code null}
 * @param minQuality minimum quality tier required, or {@code null}
 * @param quantityType the resolved material's quantity unit ({@code "SCU"} / {@code "PIECE"}) for a
 *     RESOURCE line, so the UI labels {@code quantityScu} correctly; {@code null} for an ITEM line
 *     or an unresolved RESOURCE line
 */
public record BlueprintRequirementIngredientDto(
    String kind,
    String name,
    Double quantityScu,
    Integer quantityUnits,
    Integer minQuality,
    String quantityType) {}
