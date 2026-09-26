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

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request to change the owning org unit of a selection of the caller's own personal Lager rows
 * (REQ-INV-052). Like the bulk rebooking it carries no versions: the rows are locked
 * pessimistically.
 *
 * @param itemIds the selected rows; every one must be a personal row of the caller
 * @param targetOwningOrgUnitId one of the caller's direct memberships, or {@code null} for no unit
 * @param mergeStock the per-action opt-in to merge {@code SCU} rows into an existing stack of the
 *     new unit (REQ-INV-026)
 */
public record BulkOrgUnitChangeRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Boolean mergeStock) {}
