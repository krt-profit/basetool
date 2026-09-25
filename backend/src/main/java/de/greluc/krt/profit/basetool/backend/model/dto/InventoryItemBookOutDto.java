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

import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Book-out request for an inventory entry: discard, sell or transfer an amount.
 *
 * <p>{@link #targetOwningOrgUnitId}, {@link #mergeStock} and the transfer target apply only to
 * {@link CheckoutType#TRANSFER}. A non-null org-unit pick must be a direct membership of the
 * destination user or an org unit the caller may edit (REQ-ORG-016); {@code null} auto-stamps
 * (REQ-ORG-017). {@link #mergeStock} is a non-persisted per-action opt-in (REQ-INV-026).
 *
 * <p>{@link #jobOrderReductions} and {@link #missionReductions} name how much of {@link #amount}
 * comes out of each earmark slice per dimension; the remainder, or everything when a list is {@code
 * null} or empty, comes from that dimension's unassigned rest (REQ-INV-027). On a sell, the mission
 * reductions also split the proceeds among the missions.
 */
public record InventoryItemBookOutDto(
    @NotNull @Min(0) Double amount,
    UUID targetUserId,
    UUID targetLocationId,
    CheckoutType type,
    @Size(max = 120) String terminal,
    @Min(0) BigDecimal sellAmount,
    @NotNull Long version,
    @Nullable UUID targetOwningOrgUnitId,
    Boolean mergeStock,
    @Nullable List<@Valid AllocationReductionDto> jobOrderReductions,
    @Nullable List<@Valid AllocationReductionDto> missionReductions) {}
