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

import de.greluc.krt.profit.basetool.backend.validation.QuantityAware;
import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer record carrying Inventory Item Create payload.
 *
 * <p>The optional {@link #owningOrgUnitId} field is the R5.d picker output: when present, it tells
 * the service which {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit} the new inventory
 * row should be stamped onto. The service validates that the resolved org unit is one the target
 * user actually belongs to (looked up via {@code OrgUnitMembershipRepository}). When {@code null},
 * the service falls back to the legacy "stamp the target user's home Staffel" behaviour — every
 * user has exactly one Staffel membership today, so the field is effectively optional for the
 * single-membership case and required only once users belong to multiple org units.
 *
 * @param userId target user the inventory row is created for; may be {@code null} for self-entries
 *     (the service substitutes the JWT subject).
 * @param materialId Material UUID; required.
 * @param locationId Storage location UUID; required.
 * @param quality Quality percentage in {@code [0, 1000]}; required.
 * @param amount Quantity; required, non-negative (further constrained per material via {@link
 *     de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount}).
 * @param personal {@code true} marks the row as a personal entry not visible in the global Lager
 *     view; cannot be combined with mission/job-order references.
 * @param missionId optional mission reference.
 * @param jobOrderId optional job-order reference.
 * @param owningOrgUnitId optional R5.d owner-picker output: the {@link
 *     de.greluc.krt.profit.basetool.backend.model.OrgUnit} on whose stock this row should land.
 *     When present, must point at an org unit the target user is a member of (validated
 *     server-side). When {@code null}, the service stamps the target user's home Staffel —
 *     preserving today's behaviour for the single-membership case.
 * @param mergeStock per-action stock-merge opt-in (REQ-INV-026). For a {@code PIECE} material the
 *     new row is merged into a matching existing stack unconditionally, so this flag is ignored;
 *     for an {@code SCU} material the merge happens only when this is {@code true} — the user ticks
 *     the modal checkbox for this single book-in. {@code null} is treated as {@code false}. It is
 *     never persisted: it governs only this one transaction.
 * @param jobOrderAllocations optional Variante-C split-at-check-in (REQ-INV-027, R4): earmark parts
 *     of the new entry to several job orders with their own amounts. When non-empty it supersedes
 *     {@link #jobOrderId}; the Σ of the amounts must stay within {@link #amount} (R5) and every
 *     target's material requirement is checked. {@code null}/empty falls back to the single {@link
 *     #jobOrderId}.
 * @param missionAllocations optional Variante-C split-at-check-in for missions — the mission
 *     counterpart of {@link #jobOrderAllocations}; supersedes {@link #missionId} when non-empty.
 */
@ValidQuantityAmount
public record InventoryItemCreateDto(
    UUID userId,
    @NotNull UUID materialId,
    @NotNull UUID locationId,
    @NotNull @Min(0) @Max(1000) Integer quality,
    @NotNull Double amount,
    Boolean personal,
    UUID missionId,
    UUID jobOrderId,
    UUID owningOrgUnitId,
    Boolean mergeStock,
    @Valid List<InventoryAllocationInput> jobOrderAllocations,
    @Valid List<InventoryAllocationInput> missionAllocations)
    implements QuantityAware {}
