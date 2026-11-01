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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Write DTO that replaces the „Darstellung" section. {@code version} is the section version the
 * caller last saw; a stale one is rejected with HTTP 409.
 *
 * @param desktopMode the chosen desktop mode
 * @param version the appearance version the caller last saw
 */
public record AppearanceUpdateRequest(
    @NotNull DesktopMode desktopMode, @NotNull @Min(0) Long version) {}
