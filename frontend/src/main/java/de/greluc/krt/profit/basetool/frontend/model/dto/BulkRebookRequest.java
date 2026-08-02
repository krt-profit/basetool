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
 * Frontend mirror of the backend {@code BulkRebookRequest} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory): the "Mein Lager" bulk bar's Massen-Umbuchen
 * payload (REQ-INV-036). Every listed row moves in full — the bulk selection spans collapsed stacks
 * and later pages, so there is no per-row amount to review.
 *
 * @param itemIds the ids of the owned inventory rows to rebook; at least one, none null
 * @param mode which move to perform
 * @param targetUserId {@code LOCATION} only: the destination owner, or {@code null} to keep the
 *     current one
 * @param targetLocationId {@code LOCATION} only: the destination location, or {@code null} to keep
 *     the current one
 * @param targetOwningOrgUnitId the org-unit pool to stamp onto the moved rows ({@code LOCATION} and
 *     {@code DEPERSONALIZE}); ignored when personalizing
 * @param mergeStock the per-action stock-merge opt-in (REQ-INV-026) applied to every moved row
 */
public record BulkRebookRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds,
    @NotNull BulkRebookMode mode,
    @Nullable UUID targetUserId,
    @Nullable UUID targetLocationId,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Boolean mergeStock) {}
