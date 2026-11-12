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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A current unit choice and its contextual writer capability for order creation.
 *
 * @param orgUnitId the unit identity
 * @param orgUnitName the display name
 * @param shorthand the optional short label
 * @param kind the unit kind
 * @param canWrite whether the caller currently writes this side of a proposed order
 */
public record JobOrderCreateUnitOptionDto(
    @NotNull UUID orgUnitId,
    @NotNull String orgUnitName,
    @Nullable String shorthand,
    @NotNull OrgUnitKind kind,
    boolean canWrite) {}
