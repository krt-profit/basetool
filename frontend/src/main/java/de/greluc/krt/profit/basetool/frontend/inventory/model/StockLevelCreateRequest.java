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
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code StockLevelCreateRequest}: a new minimum stock level.
 *
 * @param orgUnitId the unit whose pool the level watches
 * @param materialId the watched material
 * @param qualityTierId the tier, or {@code null} for any quality
 * @param minimum the amount below which the level is low
 * @param target the amount the level is filled up to, or {@code null} for the minimum
 */
@DtoMirror
public record StockLevelCreateRequest(
    @NotNull UUID orgUnitId,
    @NotNull UUID materialId,
    @Nullable UUID qualityTierId,
    @NotNull Double minimum,
    @Nullable Double target) {}
