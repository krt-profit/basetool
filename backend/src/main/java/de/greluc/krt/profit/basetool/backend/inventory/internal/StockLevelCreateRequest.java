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
 * A new minimum stock level (REQ-INV-068).
 *
 * @param orgUnitId the unit whose pool the level watches
 * @param materialId the watched material, or {@code null} for a game item
 * @param gameItemId the watched game item, or {@code null} for a material
 * @param qualityTierId the tier, or {@code null} for any quality; always {@code null} for a game
 *     item
 * @param minimum the amount below which the level is low
 * @param target the amount the level is filled up to, or {@code null} for the minimum
 */
public record StockLevelCreateRequest(
    @NotNull UUID orgUnitId,
    @Nullable UUID materialId,
    @Nullable UUID gameItemId,
    @Nullable UUID qualityTierId,
    @NotNull @Positive Double minimum,
    @Nullable @Positive Double target) {}
