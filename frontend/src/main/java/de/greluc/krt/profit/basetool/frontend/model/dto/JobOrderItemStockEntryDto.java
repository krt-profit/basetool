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

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemStockEntryDto} (REQ-ORDERS-028): one game-item
 * inventory entry earmarked to a job order, rendered as a row of the order-detail Item-Bestand
 * panel. {@code quantity} is the entry's total physical stock in whole units, {@code
 * allocatedQuantity} the whole-unit slice earmarked to this order (Variante C, REQ-INV-027), {@code
 * delivered} the per-(entry, order) marker of that slice, and {@code version} the entry's
 * optimistic-lock token the delivered toggle echoes.
 *
 * @param inventoryEntryId the inventory entry's primary key
 * @param version the entry's {@code @Version} (0 when the persisted version is still {@code null})
 * @param ownerName the owning user's display name, falling back to the username
 * @param ownerId the owning user's id
 * @param location the entry's location display name
 * @param locationId the entry's location id
 * @param quantity the entry's total physical stock in whole units
 * @param allocatedQuantity the whole-unit slice earmarked to this order
 * @param delivered whether this order's slice is marked delivered
 */
public record JobOrderItemStockEntryDto(
    UUID inventoryEntryId,
    long version,
    String ownerName,
    UUID ownerId,
    String location,
    UUID locationId,
    long quantity,
    long allocatedQuantity,
    boolean delivered) {}
