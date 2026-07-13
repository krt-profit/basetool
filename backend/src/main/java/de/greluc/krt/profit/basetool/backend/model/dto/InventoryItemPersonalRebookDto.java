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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request payload for the personal-marker rebooking (Umbuchung) of an inventory row (REQ-INV-007).
 *
 * <p>The direction is <em>not</em> carried in the payload: the service infers it from the source
 * row's {@code personal} flag. A source {@code personal = true} row is <em>de-personalized</em>
 * (moved into the shared squadron pool); a source {@code personal = false} row is
 * <em>personalized</em> (moved into the owner's private pool). Either way the operation is an
 * append-only split — the moved {@link #amount} is decremented off the source row and inserted as a
 * new row with the opposite {@code personal} flag (REQ-INV-001), mirroring the book-out {@code
 * TRANSFER} branch.
 *
 * @param amount the SCU/piece quantity to rebook; must be {@code > 0} and {@code <=} the source
 *     row's available amount (validated in the service)
 * @param version the source row's optimistic-lock {@code @Version}, echoed back so a concurrent
 *     edit surfaces as HTTP 409
 * @param targetOwningOrgUnitId the org-unit pool the new shared row should be stamped on — the
 *     picker output for the <em>de-personalize</em> direction, resolved through {@code
 *     OwnerScopeService.resolveOrgUnitForPickerOutputNullable}. Ignored for the
 *     <em>personalize</em> direction, which carries the owner's existing stamp over to the private
 *     row.
 * @param mergeStock per-action stock-merge opt-in (REQ-INV-026) for the newly inserted row. A
 *     {@code PIECE} material merges the moved quantity into a matching stack unconditionally; an
 *     {@code SCU} material merges only when this is {@code true}. {@code null} is treated as {@code
 *     false}; the flag governs only this one Umbuchung and is never persisted.
 */
public record InventoryItemPersonalRebookDto(
    @NotNull @Min(0) Double amount,
    @NotNull Long version,
    @Nullable UUID targetOwningOrgUnitId,
    Boolean mergeStock) {}
