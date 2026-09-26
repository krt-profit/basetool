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

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code BulkOrgUnitChangeRequest}: changes the org unit of a
 * selection of the caller's own personal Lager rows (REQ-INV-052).
 *
 * @param itemIds the selected rows; at least one, none null
 * @param targetOwningOrgUnitId one of the caller's direct memberships, or {@code null} for no unit
 * @param mergeStock the stock-merge opt-in (REQ-INV-026) applied to every changed row
 */
public record BulkOrgUnitChangeRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Boolean mergeStock) {}
