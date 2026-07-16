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

/**
 * Frontend mirror of the backend {@code JobOrderItemStockGroupDto} (REQ-ORDERS-028): one game-item
 * group of the order-detail Item-Bestand panel — the item stock earmarked to the order, grouped per
 * game item with the order's own line context ({@code orderedAmount} / {@code manufacturedAmount},
 * both 0 for an orphaned earmark) and the whole-unit sum of the entries' this-order slices.
 *
 * @param gameItem the slim game-item reference (id, name, manufacturer, kind)
 * @param orderedAmount whole units of this game item the order requests (0 when not requested)
 * @param manufacturedAmount whole units already manufactured on the order's matching lines
 * @param allocatedTotal whole units of stock earmarked to this order across the group's entries
 * @param entries the linked entries backing the group, in owner/location display order
 */
public record JobOrderItemStockGroupDto(
    InventoryGameItemReferenceDto gameItem,
    int orderedAmount,
    int manufacturedAmount,
    long allocatedTotal,
    List<JobOrderItemStockEntryDto> entries) {}
