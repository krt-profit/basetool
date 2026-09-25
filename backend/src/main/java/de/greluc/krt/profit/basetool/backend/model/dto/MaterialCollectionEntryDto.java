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
 * One inventory entry in the material collection overview of a job order.
 *
 * <p>{@code quantity} is the entry's total physical stock; {@code allocatedQuantity} is the share
 * earmarked to this order (REQ-INV-027) and is at most {@code quantity}.
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
