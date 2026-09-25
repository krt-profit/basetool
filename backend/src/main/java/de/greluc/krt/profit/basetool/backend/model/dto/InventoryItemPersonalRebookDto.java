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
 * Request payload for moving part of an inventory row between the personal and the shared pool
 * (REQ-INV-007); the direction follows the source row's {@code personal} flag.
 *
 * @param amount the quantity to rebook; positive and at most the source row's amount
 * @param version the source row's optimistic-lock version
 * @param targetOwningOrgUnitId the org unit the new shared row is stamped on; ignored when
 *     personalizing
 * @param mergeStock per-action stock-merge opt-in for an {@code SCU} material (REQ-INV-026); {@code
 *     null} means {@code false}
 */
public record InventoryItemPersonalRebookDto(
    @NotNull @Min(0) Double amount,
    @NotNull Long version,
    @Nullable UUID targetOwningOrgUnitId,
    Boolean mergeStock) {}
