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

/**
 * Summary of an applied blueprint import. Every non-skipped resolution counts in exactly one of
 * {@link #added} or {@link #alreadyOwned}.
 *
 * @param added number of new {@code personal_blueprint} rows created
 * @param aliasesLearned number of new {@code blueprint_external_alias} rows persisted
 * @param skipped number of resolutions skipped (blank choice or unresolvable product key)
 * @param alreadyOwned number of resolutions whose product the caller already owned
 * @param acquiredAtUpdated number of already-owned rows whose acquisition time was moved earlier
 */
public record BlueprintImportResultDto(
    int added, int aliasesLearned, int skipped, int alreadyOwned, int acquiredAtUpdated) {}
