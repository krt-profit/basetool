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

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Write payload for the three per-allocation endpoints of an inventory entry (Variante C,
 * REQ-INV-027): {@code POST} adds a slice, {@code PATCH} changes a slice's amount, {@code DELETE}
 * removes a slice. One record serves all three verbs, so the amount is only required (and only
 * validated) by the add/change paths — the service enforces its presence and positivity there —
 * while delete ignores it.
 *
 * @param field which quantity split — the job-order or the mission dimension — the write targets;
 *     required.
 * @param targetId the earmarked job order (for {@link InventoryAllocationDimension#JOB_ORDER}) or
 *     mission (for {@link InventoryAllocationDimension#MISSION}); required.
 * @param amount the slice amount in the material's unit; required and positive for add/change,
 *     unused for delete. Whole numbers for a {@code PIECE} material, up to three decimals for
 *     {@code SCU} — enforced in the service, which has the resolved material at hand.
 * @param version the owning entry's optimistic-lock {@code @Version}, echoed back for the 409
 *     check; the entry's version is the single concurrency token for both its splits.
 */
public record InventoryAllocationWriteDto(
    @NotNull InventoryAllocationDimension field,
    @NotNull UUID targetId,
    Double amount,
    Long version) {}
