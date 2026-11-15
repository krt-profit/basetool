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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * How many of an owner's ships from before a relocation's cutoff lie at one location
 * (REQ-PATCH-021).
 *
 * @param locationId the location, or {@code null} for ships without one
 * @param locationName the location's curated name, or {@code null} for ships without one
 * @param ships how many ships
 */
public record ShipRelocationCount(
    @Nullable UUID locationId, @Nullable String locationName, long ships) {}
