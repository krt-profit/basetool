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
 * Write payload for adding, changing or removing one allocation slice of an inventory entry
 * (REQ-INV-027).
 *
 * @param field the split dimension (job order or mission) the write targets; required
 * @param targetId the earmarked job order or mission; required
 * @param amount the slice amount in the material's unit; required and positive for add/change,
 *     ignored for delete
 * @param version the owning entry's optimistic-lock version, shared by both splits
 */
public record InventoryAllocationWriteDto(
    @NotNull InventoryAllocationDimension field,
    @NotNull UUID targetId,
    Double amount,
    Long version) {}
