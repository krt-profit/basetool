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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How one bare name resolved.
 *
 * @param status {@code MATCHED}, {@code MATCHED_BY_ALIAS}, {@code SUGGESTED} or {@code UNMATCHED}
 * @param product the resolved product, or {@code null} unless matched
 * @param suggestions fuzzy candidates, highest first (empty unless {@code status} is SUGGESTED)
 */
public record NameResolution(
    @NotNull BlueprintImportStatus status,
    @Nullable ResolvedProduct product,
    @NotNull List<BlueprintImportSuggestionDto> suggestions) {}
