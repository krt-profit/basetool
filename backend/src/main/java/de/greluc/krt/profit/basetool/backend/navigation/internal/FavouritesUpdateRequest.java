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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Write DTO that replaces the favourites section. {@code version} is the section version the caller
 * last saw; a stale one is rejected with HTTP 409.
 *
 * @param keys the new favourite entry keys in display order, at most {@link
 *     NavigationLimits#MAX_FAVOURITES}
 * @param version the favourites version the caller last saw
 */
public record FavouritesUpdateRequest(
    @NotNull @Size(max = NavigationLimits.MAX_FAVOURITES)
        List<
                @NotBlank @Size(max = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
                @Pattern(regexp = NavigationLimits.ENTRY_KEY_PATTERN) String>
            keys,
    @NotNull @Min(0) Long version) {}
