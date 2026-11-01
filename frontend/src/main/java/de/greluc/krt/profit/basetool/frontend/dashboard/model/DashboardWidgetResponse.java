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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One widget instance as the backend stores it; a default that is not stored yet has no id.
 *
 * @param id the instance id, {@code null} while the default is not stored
 * @param type the widget type
 * @param column the column
 * @param optionsVersion the version the next option write echoes
 * @param orgUnitId the pinned org unit, or {@code null} to follow the context
 * @param days the days ahead, or {@code null} when the type offers no such option
 * @param rows the rows shown, or {@code null} when the type offers no such option
 * @param status the status filter, or {@code null} when the type offers no such option
 */
@DtoMirror
public record DashboardWidgetResponse(
    @Nullable UUID id,
    @NotNull DashboardWidgetType type,
    @NotNull DashboardColumn column,
    long optionsVersion,
    @Nullable UUID orgUnitId,
    @Nullable Integer days,
    @Nullable Integer rows,
    @Nullable DashboardStatus status) {}
