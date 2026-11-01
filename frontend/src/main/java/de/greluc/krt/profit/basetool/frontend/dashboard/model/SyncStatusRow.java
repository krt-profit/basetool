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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The latest run of one synchronisation or import, as the sync status widget lists it.
 *
 * @param source the synchronisation or import
 * @param lastRunAt when the latest run happened, {@code null} when there never was one
 * @param status the latest run's status for an import ({@code SUCCEEDED}, {@code FAILED}, ...),
 *     {@code null} for a synchronisation
 * @param inRange whether the latest run lies inside the widget's range
 */
public record SyncStatusRow(
    @NotNull SyncSource source,
    @Nullable Instant lastRunAt,
    @Nullable String status,
    boolean inRange) {}
