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
 * A read-time grouping of inventory entries that share the same stock identity — what the Lager
 * used to merge into a single physical row but now keeps as separate {@code InventoryItem} rows.
 * The stack key is the inventory natural key minus the material (which is the enclosing {@link
 * GroupedInventoryDto} group): owner ({@code user}), {@code location}, {@code quality}, the {@code
 * personal} flag and the {@code owningSquadron} owner pool. Since Variante C (REQ-INV-027) the
 * job-order / mission link is no longer part of the stock identity — it lives per entry as quantity
 * allocations shown on the leaf rows — so entries differing only by their assignments now share a
 * stack. The aggregate figures ({@code totalAmount}, {@code averageQuality}, {@code maxQuality},
 * {@code entryCount}) are computed across the underlying rows directly in SQL for the collapsed
 * display row.
 *
 * <p>The individual entries are <em>not</em> inlined: append-only inventory grows unboundedly per
 * stack, so the entries are loaded lazily and paginated on expand via the {@code
 * /api/v1/inventory/{my-inventory|all}/stack/entries} endpoint (ADR-0003, REQ-INV-002). The lazy
 * fetch is keyed off exactly the stock-identity fields this record exposes — {@code user.id()},
 * {@code location.id()}, {@code quality}, {@code personal} and {@code owningSquadron.id()} — so the
 * client can request a stack's entries without any opaque token. Every per-entry action (book-out,
 * transfer, note, delivered, delete, allocation edit) still operates on a single fetched entry by
 * id + version.
 *
 * @param user the owning user shared by every entry in the stack
 * @param location the storage location shared by every entry
 * @param quality the quality grade shared by every entry
 * @param personal whether the stack holds private (owner-only) stock
 * @param owningSquadron the owning org-unit pool shared by every entry, or {@code null} for an
 *     ownerless-personal stack
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
