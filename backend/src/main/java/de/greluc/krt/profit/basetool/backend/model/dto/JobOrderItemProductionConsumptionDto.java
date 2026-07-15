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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * One inventory-entry draw of a production booking: {@code amount} of the entry {@code
 * inventoryItemId} (which must be linked to the order and hold {@code materialId}) is consumed to
 * manufacture the item. Carries the entry's optimistic-lock {@code version} so a concurrent stock
 * change surfaces as a 409.
 *
 * @param inventoryItemId the linked inventory entry drawn from
 * @param materialId the material the entry holds (must be one the item requires)
 * @param amount the SCU/PIECE consumed from this entry (positive, ≤ the entry's own amount and its
 *     earmark to this order)
 * @param version the inventory entry's optimistic-lock version (echoed for the 409 guard)
 */
public record JobOrderItemProductionConsumptionDto(
    @NotNull UUID inventoryItemId,
    @NotNull UUID materialId,
    @NotNull @Positive Double amount,
    @NotNull Long version) {}
