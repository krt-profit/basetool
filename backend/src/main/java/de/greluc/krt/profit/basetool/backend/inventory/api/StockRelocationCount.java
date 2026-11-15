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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How many of a holder's rows from before a relocation's cutoff lie at one location, for one pool
 * or as the holder's own stock (REQ-PATCH-021).
 *
 * @param locationId the location
 * @param locationName the location's curated name
 * @param orgUnitId the pool the rows are held for, or {@code null} for the holder's own rows
 * @param orgUnitName the pool's name, or {@code null} for the holder's own rows
 * @param rows how many rows
 */
public record StockRelocationCount(
    @NotNull UUID locationId,
    @NotNull String locationName,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    long rows) {}
