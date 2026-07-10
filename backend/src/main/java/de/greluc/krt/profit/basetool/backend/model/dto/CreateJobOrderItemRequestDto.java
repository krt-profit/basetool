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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Create payload for an {@code ITEM} job order. Mirrors {@link CreateJobOrderDto}'s org-unit
 * stamping contract ({@code responsibleOrgUnitId} = profit-eligible processor, ignored for guests
 * who are routed to the intake SK; {@code requestingOrgUnitId} = mandatory customer) but carries
 * finished-item lines instead of raw materials. The required materials are derived and snapshotted
 * from each line's blueprint server-side; the client never sends quantities, only the per-material
 * Gut/Keine choices.
 *
 * @param responsibleOrgUnitId the profit-eligible org unit that processes the order; required for
 *     authenticated callers, ignored for guests (routed to the configured intake SK)
 * @param requestingOrgUnitId the customer/Auftraggeber org unit the order is placed for (any kind:
 *     Staffel/SK/Bereich/OL, epic #692); mandatory
 * @param handle optional contact handle (≤ 200 chars)
 * @param comment optional free-text note (≤ 1000 chars), HTML-escaped on display
 * @param items the ordered finished-item lines (1..50)
 * @param version optimistic-lock version (unused on create)
 */
public record CreateJobOrderItemRequestDto(
    @Nullable UUID responsibleOrgUnitId,
    @Nullable UUID requestingOrgUnitId,
    @Size(max = 200) String handle,
    @Size(max = 1000) String comment,
    @NotEmpty @Size(max = 50) List<@Valid CreateJobOrderItemLineDto> items,
    Long version) {}
