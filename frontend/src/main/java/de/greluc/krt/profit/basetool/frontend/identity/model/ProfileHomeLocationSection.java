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

package de.greluc.krt.profit.basetool.frontend.identity.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What the profile's section "my home location" shows (REQ-PATCH-030): the stored choice with its
 * version and the curated home locations to choose from.
 *
 * @param homeLocationId the stored choice, or {@code null} when there is none, the profile no
 *     longer offers it or it cannot be read
 * @param version the setting's version to echo back, {@code 0} when there is none or it cannot be
 *     read
 * @param options the curated home locations by name, empty when they cannot be read
 */
public record ProfileHomeLocationSection(
    @Nullable UUID homeLocationId,
    long version,
    @NotNull @Unmodifiable List<LocationDto> options) {}
