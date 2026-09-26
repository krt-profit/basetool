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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code TerminalDto}.
 *
 * <p>The {@code hasLoadingDockOverridden} / {@code isAutoLoadOverridden} flags mark a value as
 * admin-pinned, which the UEX sync leaves alone; the {@code uex*} fields show what UEX last
 * reported.
 *
 * @param id terminal primary key
 * @param name canonical terminal name as supplied by UEX
 * @param nickname short label shown in dropdowns / tables
 * @param starSystemName parent star system label
 * @param planetName parent planet label, or {@code null} for orbital terminals
 * @param cityName parent city label, or {@code null}
 * @param spaceStationName parent station label, or {@code null}
 * @param hasLoadingDock current effective "has loading dock" value
 * @param isAutoLoad current effective "is auto-load" value
 * @param hasLoadingDockOverridden whether {@code hasLoadingDock} is admin-pinned
 * @param isAutoLoadOverridden whether {@code isAutoLoad} is admin-pinned
 * @param uexHasLoadingDock raw {@code hasLoadingDock} value from the latest UEX sync, or {@code
 *     null} before the first sync
 * @param uexIsAutoLoad raw {@code isAutoLoad} value from the latest UEX sync, or {@code null}
 *     before the first sync
 * @param uexSyncedAt UTC instant of the last UEX sweep that touched the terminal, or {@code null}
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
