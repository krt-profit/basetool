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
 * Result of a blueprint import preview: one {@link BlueprintImportEntryDto} per unique external
 * name plus per-status counts. Nothing is persisted.
 *
 * @param total number of unique external names parsed from the upload
 * @param matched count of {@link BlueprintImportStatus#MATCHED} rows
 * @param matchedByAlias count of {@link BlueprintImportStatus#MATCHED_BY_ALIAS} rows
 * @param suggested count of {@link BlueprintImportStatus#SUGGESTED} rows
 * @param unmatched count of {@link BlueprintImportStatus#UNMATCHED} rows
 * @param alreadyOwned count of {@link BlueprintImportStatus#ALREADY_OWNED} rows
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
