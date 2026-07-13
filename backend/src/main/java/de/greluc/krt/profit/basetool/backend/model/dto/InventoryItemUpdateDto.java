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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Data transfer record carrying Inventory Item Update payload.
 *
 * @param materialId the (possibly changed) material of the row; required.
 * @param locationId the (possibly changed) storage location; required.
 * @param quality the quality grade in {@code [0, 1000]}; required.
 * @param amount the quantity; required, non-negative (per-material constrained via {@link
 *     ValidQuantityAmount}).
 * @param personal whether the row is a personal (private) entry.
 * @param jobOrderId optional job-order reference the row is (re)bound to.
 * @param missionId optional mission reference the row is (re)bound to.
 * @param version the optimistic-lock {@code @Version}, echoed back for the 409 check.
 * @param mergeStock per-action stock-merge opt-in (REQ-INV-026). A {@code PIECE} edit always merges
 *     into a matching stack; an {@code SCU} edit merges only when this is {@code true}. {@code
 *     null} is treated as {@code false}; the flag governs only this one transaction and is never
 *     persisted.
 */
@ValidQuantityAmount
public record InventoryItemUpdateDto(
    @NotNull UUID materialId,
    @NotNull UUID locationId,
    @NotNull @Min(0) @Max(1000) Integer quality,
    @NotNull Double amount,
    Boolean personal,
    UUID jobOrderId,
    UUID missionId,
    Long version,
    Boolean mergeStock)
    implements QuantityAware {}
