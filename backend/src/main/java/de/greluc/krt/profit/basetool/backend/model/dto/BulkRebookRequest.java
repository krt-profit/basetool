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

import de.greluc.krt.profit.basetool.backend.model.BulkRebookMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for the bulk rebooking (Massen-Umbuchen) of several of the caller's own inventory
 * rows in one action (REQ-INV-036).
 *
 * <p>Every row moves in full, keeping all its job-order / mission earmarks. Carries no {@code
 * version}: the write serialises on a pessimistic row lock per entry.
 *
 * @param itemIds ids of the caller's own rows to rebook; non-empty
 * @param mode which move to perform, see {@link BulkRebookMode}
 * @param targetUserId {@code LOCATION} only: the destination owner, or {@code null} to keep each
 *     row's owner
 * @param targetLocationId {@code LOCATION} only: the destination location, or {@code null} to keep
 *     each row's location
 * @param targetOwningOrgUnitId org-unit pool stamped onto moved rows for {@code LOCATION} and
 *     {@code DEPERSONALIZE}; {@code null} means the target owner's default pool; ignored by {@code
 *     PERSONALIZE}
 * @param mergeStock stock-merge opt-in applied to every moved row (REQ-INV-026); ignored for PIECE
 *     materials and game items, which always merge
 */
public record BulkRebookRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds,
    @NotNull BulkRebookMode mode,
    @Nullable UUID targetUserId,
    @Nullable UUID targetLocationId,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Boolean mergeStock) {}
