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
 * Request DTO for the bulk rebooking (Massen-Umbuchen, REQ-INV-036) of several own inventory rows
 * in one action. All listed ids must belong to the authenticated caller.
 *
 * <p>Every listed row moves in <em>full</em> — there is no per-row amount. The bulk bar's selection
 * spans collapsed stacks and later pages (REQ-INV-034), so a per-row quantity could not be reviewed
 * before submitting; moving the whole row keeps the action predictable and lets each moved row
 * inherit all of its job-order / mission earmarks unchanged ("Marken mitnehmen", REQ-INV-027).
 *
 * <p>Carries no {@code version}: like {@link BulkCheckoutRequest} the write serialises on a
 * pessimistic row lock per entry rather than a client-echoed optimistic token, because the bulk bar
 * holds only entry ids (a server-resolved "Alle markieren" set never carries versions).
 *
 * @param itemIds the ids of the caller's own rows to rebook; must be non-empty
 * @param mode which move to perform — the personal directions are explicit rather than inferred,
 *     see {@link BulkRebookMode}
 * @param targetUserId {@code LOCATION} only: the destination owner, or {@code null} to keep each
 *     row's current owner
 * @param targetLocationId {@code LOCATION} only: the destination location, or {@code null} to keep
 *     each row's current location
 * @param targetOwningOrgUnitId the org-unit pool to stamp onto the moved rows ({@code LOCATION} and
 *     {@code DEPERSONALIZE}); {@code null} resolves to the target owner's default pool. {@code
 *     PERSONALIZE} ignores it — a personalized row keeps its source stamp
 * @param mergeStock the per-action stock-merge opt-in (REQ-INV-026) applied to every moved row;
 *     ignored for PIECE materials and game items, which always merge
 */
public record BulkRebookRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds,
    @NotNull BulkRebookMode mode,
    @Nullable UUID targetUserId,
    @Nullable UUID targetLocationId,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Boolean mergeStock) {}
