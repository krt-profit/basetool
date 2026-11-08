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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotBlank;

/**
 * The starmap page's "create storage place" dialog (REQ-P4K-036); the starmap object comes from the
 * path.
 *
 * @param name the curated name
 * @param description the admin's description, or {@code null}
 * @param hidden whether normal dropdowns skip the new place
 * @param homeLocation whether the hangar's home-location picker offers it
 */
@DtoMirror
public record StarmapStoragePlaceRequest(
    @NotBlank String name, String description, boolean hidden, boolean homeLocation) {}
