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

import java.util.UUID;

/**
 * DTO representing a single inventory entry in the material collection overview for a specific job
 * order. Used by the material collection endpoint.
 *
 * <p>{@code quantity} is the entry's <em>total physical</em> stock (it backs the full-row owner /
 * location transfer). {@code allocatedQuantity} is the share of that stock <em>earmarked to this
 * job order</em> (the job-order allocation slice, Variante C / REQ-INV-027) — the amount that
 * actually counts toward the order's fulfilment; it is {@code <= quantity} and equals it when the
 * whole row is allocated to the order.
 */
public record MaterialCollectionEntryDto(
    UUID inventoryEntryId,
    long version,
    String ownerName,
    UUID ownerId,
    String location,
    UUID locationId,
    String materialName,
    Double quality,
    Double quantity,
    Double allocatedQuantity,
    boolean delivered) {}
