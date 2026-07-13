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
 * Frontend mirror of one display stack: append-only inventory rows that share a stock identity
 * (owner, location, quality, personal flag, owning org-unit pool) collapsed into a single row for
 * the Lager view. Since Variante C (REQ-INV-027) the job-order / mission link is no longer part of
 * the stock identity — it lives per entry as quantity-allocation chips on the leaf rows — so it is
 * not part of this stack key. The aggregate figures describe the collapsed row.
 *
 * <p>The individual entries are <em>not</em> inlined. A stack grows unboundedly as contributions
 * accumulate, so the entries are loaded lazily and paginated on expand: the page renders the
 * collapsed stack row and fetches its entries via {@code GET /inventory/{my|all}/stack/entries}
 * (proxying the backend's {@code /api/v1/inventory/{my-inventory|all}/stack/entries}). The lazy
 * fetch is keyed off exactly the stock-identity fields this record exposes — {@code user.id()},
 * {@code location.id()}, {@code quality}, {@code personal} and {@code owningSquadron.id()} (plus
 * the enclosing group's {@code material.id()}) — so the browser can request a stack's entries
 * without any opaque token.
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
