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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code CreateJobOrderItemRequestDto}: the POST payload for
 * creating an item order via {@code /api/v1/orders/items}.
 *
 * @param responsibleOrgUnitId the profit-eligible org unit that processes the order
 * @param requestingOrgUnitId the customer org unit the order is placed for
 * @param handle optional contact handle
 * @param comment optional free-text note
 * @param items the ordered finished-item lines
 * @param version optimistic-lock version (unused on create)
 */
public record CreateJobOrderItemRequestDto(
    @Nullable UUID responsibleOrgUnitId,
    @Nullable UUID requestingOrgUnitId,
    String handle,
    String comment,
    List<CreateJobOrderItemLineDto> items,
    Long version) {}
