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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request to move a shared Lager row, or a part of it, into the pool of another org unit its holder
 * directly belongs to (REQ-INV-059).
 *
 * @param version the row version the client last read
 * @param targetOwningOrgUnitId the org unit whose pool receives the stock
 * @param amount the part to move, split off as a new row; {@code null} moves the whole row
 * @param mergeStock the per-action opt-in to merge an {@code SCU} row into an existing stack of the
 *     new pool (REQ-INV-026); {@code PIECE} and game-item rows always merge
 */
public record InventoryItemPoolChangeDto(
    @NotNull Long version,
    @NotNull UUID targetOwningOrgUnitId,
    @Nullable @Positive Double amount,
    @Nullable Boolean mergeStock) {}
