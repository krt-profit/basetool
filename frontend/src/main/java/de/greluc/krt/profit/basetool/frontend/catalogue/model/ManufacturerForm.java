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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Form-binding object for Manufacturer input. */
public record ManufacturerForm(
    @NotBlank(message = "{validation.name.required}") @Size(max = 255) String name,
    @NotBlank(message = "{validation.abbreviation.required}") @Size(max = 50) String abbreviation,
    @Size(max = 255) String nickname,
    @Size(max = 255) String wiki,
    @Size(max = 2000) String description) {}
