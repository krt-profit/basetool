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

package de.greluc.krt.profit.basetool.backend.model.projection;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;

/**
 * Read-only GROUP BY projection of one game-item inventory <em>stack</em> — the item-catalog
 * sibling of {@link InventoryStackAggregate} (REQ-INV-029, ADR-0100). Game-item rows carry no
 * quality dimension, so the stack key is ({@code gameItem}, {@code user}, {@code location}, {@code
 * personal}, {@code owningOrgUnit}) and the quality aggregates of the material projection have no
 * counterpart here. One row of this projection equals one display stack of the Lager item views;
 * the underlying append-only entries are fetched lazily and paginated on expand (REQ-INV-005), the
 * same as material stacks.
 *
 * @param gameItem the grouping game item shared by every entry in the stack; never {@code null} —
 *     the producing queries filter on {@code gameItem IS NOT NULL}
 * @param user the owning user shared by every entry
 * @param location the storage location shared by every entry
 * @param personal whether the stack holds private (owner-only) stock
 * @param owningOrgUnit the owning org-unit pool shared by every entry, or {@code null} for an
 *     ownerless-personal stack
 * @param totalAmount the summed whole-unit quantity across all entries ({@code SUM(amount)},
 *     null-coalesced)
 * @param entryCount the number of underlying entries collapsed into this stack ({@code COUNT})
 */
public record InventoryItemStackAggregate(
    GameItem gameItem,
    User user,
    Location location,
    Boolean personal,
    OrgUnit owningOrgUnit,
    Double totalAmount,
    Long entryCount) {}
