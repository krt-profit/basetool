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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * How many of the rows at one location a member holds for one org unit's pool (REQ-PATCH-021).
 *
 * @param orgUnitId the org unit
 * @param orgUnitName its name
 * @param rows how many rows
 */
public record HomeLocationPoolRowsDto(
    @NotNull UUID orgUnitId, @NotNull String orgUnitName, long rows) {}
