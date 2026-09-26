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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code InventoryItemPersonalRebookDto}: the personal-marker
 * rebooking payload (REQ-INV-007) of the Umbuchen modal's PERSONAL mode.
 *
 * @param amount the quantity to rebook
 * @param version the source row's optimistic-lock version
 * @param targetOwningOrgUnitId the picked org-unit pool for the de-personalize direction, or {@code
 *     null}
 * @param mergeStock stock-merge opt-in for the new row (REQ-INV-026), honoured only for an {@code
 *     SCU} material; {@code null} or {@code false} keeps it separate
 */
public record InventoryItemPersonalRebookDto(
    @NotNull @Min(0) Double amount,
    @NotNull Long version,
    UUID targetOwningOrgUnitId,
    Boolean mergeStock) {}
