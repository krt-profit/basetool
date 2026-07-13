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
 * Frontend mirror of the backend {@code InventoryItemPersonalRebookDto} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory). Carries the personal-marker rebooking payload
 * (REQ-INV-007) from the Umbuchen modal's PERSONAL mode to the backend through the {@code
 * /inventory/{id}/personal-rebook} proxy.
 *
 * @param amount the quantity to rebook
 * @param version the source row's optimistic-lock version
 * @param targetOwningOrgUnitId the picked org-unit pool for the de-personalize direction, or {@code
 *     null}
 * @param mergeStock the per-action stock-merge opt-in (REQ-INV-026) for the newly inserted row:
 *     honoured only for an {@code SCU} material (a {@code PIECE} rebooking always merges); {@code
 *     null}/{@code false} keeps the new row separate
 */
public record InventoryItemPersonalRebookDto(
    @NotNull @Min(0) Double amount,
    @NotNull Long version,
    UUID targetOwningOrgUnitId,
    Boolean mergeStock) {}
