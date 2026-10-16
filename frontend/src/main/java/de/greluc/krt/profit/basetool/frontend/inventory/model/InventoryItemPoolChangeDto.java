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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code InventoryItemPoolChangeDto}: moves a shared Lager row, or a
 * part of it, into another pool of its holder (REQ-INV-059).
 *
 * @param version the row version the page last rendered
 * @param targetOwningOrgUnitId the org unit whose pool receives the stock
 * @param amount the part to move, or {@code null} for the whole row
 * @param mergeStock the stock-merge opt-in (REQ-INV-026), honoured only for an {@code SCU} material
 */
@DtoMirror
public record InventoryItemPoolChangeDto(
    @Nullable Long version,
    @Nullable UUID targetOwningOrgUnitId,
    @Nullable Double amount,
    @Nullable Boolean mergeStock) {}
