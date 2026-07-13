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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;

/**
 * Read-only GROUP BY projection of one inventory <em>stack</em> — the rows that share the inventory
 * natural key (stock identity) collapsed by the database for the group-on-read Lager views
 * (ADR-0003, REQ-INV-002). One row of this projection equals one display stack; the underlying
 * individual entries are <em>not</em> loaded here — they are fetched lazily and paginated when a
 * stack is expanded. Since Variante C (REQ-INV-027) moved the job-order/mission link off the row
 * into per-entry quantity allocations, they are no longer part of the stock identity: the shared
 * key is ({@code material}, {@code user}, {@code location}, {@code quality}, {@code personal},
 * {@code owningOrgUnit}) and each leaf entry carries its own allocation chips. {@code
 * weightedQualitySum} is {@code SUM(amount * quality)} so the service can derive the
 * amount-weighted mean quality as {@code weightedQualitySum / totalAmount} without re-reading the
 * entries.
 *
 * @param material the grouping material shared by every entry in the stack
 * @param user the owning user shared by every entry
 * @param location the storage location shared by every entry
 * @param quality the quality grade shared by every entry, or {@code null}
 * @param personal whether the stack holds private (owner-only) stock
 * @param owningOrgUnit the owning org-unit pool shared by every entry, or {@code null} for an
 *     ownerless-personal stack
 * @param totalAmount the summed quantity across all entries ({@code SUM(amount)}, null-coalesced)
 * @param weightedQualitySum the amount-weighted quality sum ({@code SUM(amount * quality)},
 *     null-coalesced) the service divides by {@code totalAmount} for the mean quality
 * @param maxQuality the highest quality value among the entries ({@code MAX(quality)},
 *     null-coalesced)
 * @param entryCount the number of underlying entries collapsed into this stack ({@code COUNT})
 */
public record InventoryStackAggregate(
    Material material,
    User user,
    Location location,
    Integer quality,
    Boolean personal,
    OrgUnit owningOrgUnit,
    Double totalAmount,
    Double weightedQualitySum,
    Integer maxQuality,
    Long entryCount) {}
