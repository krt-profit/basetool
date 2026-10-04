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

import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipTypeDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper of the catalogue's {@link ShipType} to its outbound DTO. */
@Mapper(config = CentralMapperConfig.class, uses = ManufacturerMapper.class)
public interface ShipTypeMapper {

  /**
   * Maps a {@link ShipType} to its DTO, with {@code description} taken from {@code descriptionDe},
   * falling back to {@code descriptionEn}.
   *
   * @param shipType the entity to project; {@code null} returns {@code null}
   * @return the ship-type DTO
   */
  @Mapping(
      target = "description",
      expression =
          "java(shipType.getDescriptionDe() != null ? shipType.getDescriptionDe()"
              + " : shipType.getDescriptionEn())")
  ShipTypeDto toDto(ShipType shipType);
}
