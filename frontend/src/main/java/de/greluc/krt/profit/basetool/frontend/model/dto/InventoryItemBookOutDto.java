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
 * Frontend mirror of the backend {@code InventoryItemBookOutDto}.
 *
 * <p>{@code targetOwningOrgUnitId} and {@code mergeStock} (REQ-INV-026) apply only to {@link
 * CheckoutType#TRANSFER}; {@code mergeStock} is honoured only for an {@code SCU} material. {@code
 * jobOrderReductions} / {@code missionReductions} name which earmark slices the amount comes from
 * (REQ-INV-027); {@code null} or empty takes it from the unassigned rest.
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
    List<@Valid AllocationReductionDto> jobOrderReductions,
    List<@Valid AllocationReductionDto> missionReductions) {}
