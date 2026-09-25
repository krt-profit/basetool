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
 * earmarked to a job order for one {@code GameItem}.
 *
 * @param gameItem the slim game-item reference
 * @param orderedAmount whole units the order requests (0 when not requested)
 * @param manufacturedAmount whole units already manufactured on the matching lines
 * @param allocatedTotal whole units earmarked to this order across the group's entries
 * @param entries the linked entries, in owner/location display order
 */
public record JobOrderItemStockGroupDto(
    InventoryGameItemReferenceDto gameItem,
    int orderedAmount,
    int manufacturedAmount,
    long allocatedTotal,
    List<JobOrderItemStockEntryDto> entries) {}
