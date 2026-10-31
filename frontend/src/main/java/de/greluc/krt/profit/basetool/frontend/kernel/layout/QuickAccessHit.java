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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entity a {@link QuickAccessSearchContributor} found: the slim projection of REQ-UI-033.
 *
 * @param href the page the hit opens, starting with {@code /}
 * @param name the entity's name as the page shows it
 * @param statusKey the message key of its status, or {@code null} for a type without one
 * @param date its date as the page shows it, or {@code null} for a type without one
 */
public record QuickAccessHit(
    @NotNull String href,
    @NotNull String name,
    @Nullable String statusKey,
    @Nullable Instant date) {}
