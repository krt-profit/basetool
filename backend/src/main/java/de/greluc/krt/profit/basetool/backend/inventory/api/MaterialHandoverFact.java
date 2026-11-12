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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable physical and allocation facts for planning a material handover without a write grant.
 *
 * @param rowId the existing physical row
 * @param version the observed persisted row version
 * @param materialId the material, or null for a wrong-kind game-item row
 * @param quantityType the material's unit, or null when absent
 * @param quality the row's material quality, or null when absent
 * @param amount the physical row quantity
 * @param orderAllocatedAmount the order's slice amount, or null when no slice exists
 */
public record MaterialHandoverFact(
    @NotNull UUID rowId,
    long version,
    @Nullable UUID materialId,
    @Nullable QuantityType quantityType,
    @Nullable Integer quality,
    double amount,
    @Nullable Double orderAllocatedAmount) {}
