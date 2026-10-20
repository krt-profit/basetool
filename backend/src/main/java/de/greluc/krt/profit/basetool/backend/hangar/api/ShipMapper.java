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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** MapStruct mapper between Ship entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, SquadronMapper.class, ShipTypeMapper.class})
public interface ShipMapper {
  /**
   * Maps a {@link Ship} to its DTO, publishing the owning org unit as {@code owningSquadron} and
   * leaving out the pledge data, which only {@link #toOwnerDto} carries (REQ-HANGAR-015).
   *
   * @param ship the entity to project; {@code null} returns {@code null}
   * @return the ship DTO without pledge data
   */
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "pledge", ignore = true)
  ShipDto toDto(Ship ship);

  /**
   * Maps a {@link Ship} to its DTO including the imported pledge data, for the owner and for admins
   * (REQ-HANGAR-015).
   *
   * @param ship the entity to project; {@code null} returns {@code null}
   * @return the ship DTO with pledge data
   */
  @Named("withPledge")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "pledge", source = "ship")
  ShipDto toOwnerDto(Ship ship);

  /**
   * Collects the imported pledge columns of a ship.
   *
   * @param ship the entity to read
   * @return the pledge data, or {@code null} when the ship carries no pledge id
   */
  default @Nullable ShipPledgeDto pledgeOf(@Nullable Ship ship) {
    if (ship == null || ship.getPledgeId() == null) {
      return null;
    }
    return new ShipPledgeDto(
        ship.getPledgeId(),
        ship.getPledgeDate(),
        ship.getPledgeCost(),
        ship.getPledgeCostCurrency(),
        ship.getMeltable(),
        ship.getGiftable());
  }

  /** Nested mapping for the ship's stationing {@link Location}. */
  LocationDto locationToDto(Location location);

  /** Nested mapping for the ship's {@link Manufacturer}. */
  ManufacturerDto manufacturerToDto(Manufacturer manufacturer);
}
