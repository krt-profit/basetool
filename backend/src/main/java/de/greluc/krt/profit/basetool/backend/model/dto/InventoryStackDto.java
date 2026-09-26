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

/**
 * Read-time grouping of inventory entries sharing owner, location, quality, personal flag and owner
 * pool within one material group, with aggregates computed in SQL.
 *
 * <p>Entries are not inlined; they are fetched page-wise on expand, keyed by the identity fields of
 * this record (REQ-INV-002).
 *
 * @param user the owning user shared by every entry in the stack
 * @param location the storage location shared by every entry
 * @param quality the quality grade shared by every entry
 * @param personal whether the stack holds private (owner-only) stock
 * @param owningSquadron the owning org-unit pool, or {@code null} for an ownerless-personal stack
 * @param totalAmount the summed quantity across all entries (SCU or pieces)
 * @param averageQuality the amount-weighted mean quality across all entries
 * @param maxQuality the highest quality value among the entries
 * @param entryCount the number of underlying entries collapsed into this stack
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
