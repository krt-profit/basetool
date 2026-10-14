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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The supply module's runtime settings as the admin page shows them (REQ-SUP-001).
 *
 * @param enabled whether the supply surface is switched on
 * @param governingAreaId the governing Bereich, or {@code null} when none is set
 * @param governingAreaName the Bereich's name, or {@code null} when none is set
 */
public record SupplySettingsDto(
    boolean enabled, @Nullable UUID governingAreaId, @Nullable String governingAreaName) {}
