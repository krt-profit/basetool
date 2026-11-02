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
 * The changeable fields of a minimum stock level; its unit and material stay fixed (REQ-INV-068).
 *
 * @param qualityTierId the tier, or {@code null} for any quality
 * @param minimum the amount below which the level is low
 * @param target the amount the level is filled up to, or {@code null} for the minimum
 * @param version the echoed version of the level
 */
public record StockLevelUpdateRequest(
    @Nullable UUID qualityTierId,
    @NotNull @Positive Double minimum,
    @Nullable @Positive Double target,
    @NotNull Long version) {}
