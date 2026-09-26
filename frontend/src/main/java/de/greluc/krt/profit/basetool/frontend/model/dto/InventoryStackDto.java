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

/**
 * Frontend mirror of one Lager display stack: inventory rows sharing owner, location, quality,
 * personal flag and owning org-unit pool, collapsed into one row (REQ-INV-027).
 *
 * <p>Entries are not inlined; they are fetched lazily and paged via {@code GET
 * /inventory/{my|all}/stack/entries}, keyed by this record's identity fields plus the group's
 * material.
 *
 * @param user the owning user shared by every entry
 * @param location the storage location shared by every entry
 * @param quality the quality grade shared by every entry
 * @param personal whether the stack holds private stock
 * @param owningSquadron the owning org-unit pool, or {@code null}
 * @param totalAmount the summed quantity across all entries
 * @param averageQuality the amount-weighted mean quality
 * @param maxQuality the highest quality among the entries
 * @param entryCount the number of underlying entries
 */
public record InventoryStackDto(
    UserReferenceDto user,
    LocationReferenceDto location,
    Integer quality,
    Boolean personal,
    SquadronReferenceDto owningSquadron,
    Double totalAmount,
    Double averageQuality,
    Integer maxQuality,
    Integer entryCount) {}
