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

package de.greluc.krt.profit.basetool.frontend.personalinventory.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * One entry of the personal-inventory location picker. Mirrors the backend {@code
 * PersonalInventoryLocationOption}; {@code id} is the {@code locationId} a write sends.
 *
 * @param id the location's id
 * @param type the kind of place, for the picker's marker
 * @param name the location's name
 * @param starSystemName the star system of the linked city or station, or {@code null}
 * @param parentName the planet of the linked city, or {@code null}
 */
@DtoMirror
public record PersonalInventoryLocationOption(
    UUID id,
    PersonalInventoryLocationType type,
    String name,
    String starSystemName,
    String parentName) {}
