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

import java.util.List;

/**
 * One game-item group of the order-detail Item-Bestand panel (REQ-ORDERS-028): the item stock
 * earmarked to a job order, grouped per {@code GameItem} — the item sibling of the per-order
 * material collection. Groups are name-sorted; {@code entries} keeps the repository's
 * owner/location display order.
 *
 * <p>{@code orderedAmount} / {@code manufacturedAmount} are the order's own line context for the
 * group's game item (summed over the order's lines requesting it, REQ-ORDERS-025); both are {@code
 * 0} when the order no longer requests the item (an orphaned earmark, which REQ-ORDERS-019 flags
 * separately). {@code allocatedTotal} is the whole-unit sum of the entries' this-order slices.
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
