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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code InventoryItemBookOutDto} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory).
 *
 * <p>R5.d.g added the trailing {@code targetOwningOrgUnitId} picker output. Only honoured for
 * {@link CheckoutType#TRANSFER}; ignored for {@link CheckoutType#DISCARD} and {@link
 * CheckoutType#SELL}. The final {@code mergeStock} field is the per-action stock-merge opt-in
 * (REQ-INV-026) for the {@code TRANSFER} target: honoured only for an {@code SCU} material (a
 * {@code PIECE} transfer always merges); {@code null}/{@code false} keeps the new row separate.
 *
 * <p>{@code jobOrderReductions} / {@code missionReductions} are the Variante-C "deduct from" plan
 * (REQ-INV-027): the deducted {@code amount} is sourced independently per dimension from named
 * earmark slices or the not-yet-assigned rest. A {@code null}/empty list takes it all from the
 * rest. On a {@code TRANSFER} the reduced tags move to the new row; on a {@code SELL} the mission
 * reductions also drive the coupled proceeds split (each mission credited {@code sellAmount ×
 * amount_j / amount}, the rest personal).
 */
public record InventoryItemBookOutDto(
    @NotNull @Min(0) Double amount,
    UUID targetUserId,
    UUID targetLocationId,
    CheckoutType type,
    String terminal,
    @Min(0) BigDecimal sellAmount,
    @NotNull Long version,
    UUID targetOwningOrgUnitId,
    Boolean mergeStock,
    @Valid List<AllocationReductionDto> jobOrderReductions,
    @Valid List<AllocationReductionDto> missionReductions) {}
