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
 * version and the curated home locations to choose from, or that they cannot be read.
 *
 * @param available whether the setting and the curated home locations were both read; only then
 *     does the section offer a form, so no version the page never read is saved
 * @param homeLocationId the stored choice, or {@code null} when there is none, the profile no
 *     longer offers it or the section is unavailable
 * @param version the setting's version to echo back, {@code 0} when the member made no choice or
 *     the section is unavailable
 * @param options the curated home locations by name, empty when none are curated or the section is
 *     unavailable
 */
public record ProfileHomeLocationSection(
    boolean available,
    @Nullable UUID homeLocationId,
    long version,
    @NotNull @Unmodifiable List<LocationDto> options) {

  /** The section whose setting or curated home locations could not be read. */
  public static final ProfileHomeLocationSection UNAVAILABLE =
      new ProfileHomeLocationSection(false, null, 0L, List.of());
}
