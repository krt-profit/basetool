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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Ship entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, SquadronMapper.class, ShipTypeMapper.class})
public interface ShipMapper {
  /**
   * Maps a {@link Ship} to its DTO, publishing the owning org unit as {@code owningSquadron}.
   *
   * @param ship the entity to project; {@code null} returns {@code null}
   * @return the ship DTO
   */
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  ShipDto toDto(Ship ship);

  /** Nested mapping for the ship's stationing {@link Location}. */
  LocationDto locationToDto(Location location);

  /** Nested mapping for the ship's {@link Manufacturer}. */
  ManufacturerDto manufacturerToDto(Manufacturer manufacturer);
}
