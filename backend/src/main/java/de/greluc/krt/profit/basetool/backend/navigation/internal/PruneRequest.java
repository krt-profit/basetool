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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Names the entry keys the caller may no longer see, to be deleted from every section.
 *
 * @param keys the entry keys to delete
 */
public record PruneRequest(
    @NotNull @Size(max = NavigationLimits.MAX_PRUNE_KEYS)
        List<
                @NotBlank @Size(max = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
                @Pattern(regexp = NavigationLimits.ENTRY_KEY_PATTERN) String>
            keys) {}
