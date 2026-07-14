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
 * DTO for a single entry in the material collection overview of a job order. {@code quantity} is
 * the entry's total physical stock (backs the full-row transfer); {@code allocatedQuantity} is the
 * share earmarked to this job order (Variante C / REQ-INV-027), {@code <= quantity}.
 */
public record MaterialCollectionEntryDto(
    UUID inventoryEntryId,
    long version,
    String ownerName,
    UUID ownerId,
    String location,
    UUID locationId,
    String materialName,
    double quality,
    double quantity,
    double allocatedQuantity,
    boolean delivered) {}
