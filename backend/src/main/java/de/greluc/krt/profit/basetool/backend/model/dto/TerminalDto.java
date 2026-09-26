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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound projection of a {@code Terminal}, including the admin-override flags and the raw values
 * UEX last reported.
 *
 * @param id terminal primary key
 * @param name canonical terminal name as supplied by UEX
 * @param nickname short label shown in dropdowns / tables
 * @param starSystemName parent star system label
 * @param planetName parent planet label, or {@code null} for orbital / Lagrange terminals
 * @param cityName parent city label, or {@code null}
 * @param spaceStationName parent station label, or {@code null}
 * @param hasLoadingDock effective "has loading dock" value (UEX-sourced or admin-pinned)
 * @param isAutoLoad effective "is auto-load" value (UEX-sourced or admin-pinned)
 * @param hasLoadingDockOverridden {@code true} iff an admin pinned {@code hasLoadingDock}, so the
 *     UEX sync leaves it untouched
 * @param isAutoLoadOverridden {@code true} iff an admin pinned {@code isAutoLoad}, so the UEX sync
 *     leaves it untouched
 * @param uexHasLoadingDock raw value from the latest UEX sweep; {@code null} before the first sync
 * @param uexIsAutoLoad raw value from the latest UEX sweep; {@code null} before the first sync
 * @param uexSyncedAt UTC instant of the latest UEX sweep, or {@code null} if never synced
 * @param hidden whether the terminal is hidden from regular dropdowns / lists
 */
public record TerminalDto(
    UUID id,
    String name,
    String nickname,
    String starSystemName,
    String planetName,
    String cityName,
    String spaceStationName,
    Boolean hasLoadingDock,
    Boolean isAutoLoad,
    boolean hasLoadingDockOverridden,
    boolean isAutoLoadOverridden,
    Boolean uexHasLoadingDock,
    Boolean uexIsAutoLoad,
    Instant uexSyncedAt,
    boolean hidden) {}
