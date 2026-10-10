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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import jakarta.validation.constraints.NotNull;

/**
 * DTO for toggling whether an item order's blueprint-coverage view counts cosmetic variants of the
 * ordered items toward availability ({@code true}, family-key matching) or matches blueprints
 * exactly ({@code false}). Carries the order's optimistic-lock {@code version} so a concurrent edit
 * of the same order surfaces as a 409 instead of a lost update.
 *
 * @param countBlueprintsWithVariants {@code true} to count owners of any cosmetic variant of an
 *     ordered item, {@code false} to count only owners of the exact ordered blueprint
 * @param version the order's expected optimistic-lock version
 */
public record UpdateJobOrderBlueprintCountingDto(
    @NotNull Boolean countBlueprintsWithVariants, @NotNull Long version) {}
