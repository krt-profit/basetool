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
 * Frontend mirror of the backend {@code BlueprintImportPreviewDto}: one row per unique external
 * name plus per-status counts.
 *
 * @param total number of unique external names parsed from the upload
 * @param matched count of directly matched rows
 * @param matchedByAlias count of alias-matched rows
 * @param suggested count of fuzzy-suggested rows
 * @param unmatched count of unmatched rows
 * @param alreadyOwned count of already-owned rows
 * @param entries the per-name preview rows, in upload order
 */
public record BlueprintImportPreviewDto(
    int total,
    int matched,
    int matchedByAlias,
    int suggested,
    int unmatched,
    int alreadyOwned,
    List<BlueprintImportEntryDto> entries) {}
