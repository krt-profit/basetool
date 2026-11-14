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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What a member's relocation would move from one current location (REQ-PATCH-021): only rows and
 * ships from before the phase's live time count.
 *
 * @param locationId the current location, or {@code null} for ships without one
 * @param locationName its curated name, or {@code null} for ships without one
 * @param rows how many stock rows lie there, own and pool rows together
 * @param pools the share of those rows held for each org unit's pool
 * @param ships how many ships lie there
 */
public record HomeLocationPreviewDto(
    @Nullable UUID locationId,
    @Nullable String locationName,
    long rows,
    @NotNull @Unmodifiable List<HomeLocationPoolRowsDto> pools,
    long ships) {}
