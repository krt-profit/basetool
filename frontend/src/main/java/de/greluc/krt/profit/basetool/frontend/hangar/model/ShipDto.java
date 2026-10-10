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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.SquadronReferenceDto;
import java.util.UUID;

/** Data transfer record carrying Ship payload. */
public record ShipDto(
    UUID id,
    String name,
    ShipTypeDto shipType,
    String insurance,
    LocationDto location,
    Boolean fitted,
    UserDto owner,
    SquadronReferenceDto owningSquadron,
    Long version) {}
