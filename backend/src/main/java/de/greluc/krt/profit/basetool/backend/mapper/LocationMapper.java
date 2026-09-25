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
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Location entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface LocationMapper {
  /** Maps a {@link Location} entity to its outbound DTO. */
  LocationDto toDto(Location entity);

  /** Builds a new {@link Location} entity from the inbound DTO. */
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "city", ignore = true)
  @Mapping(target = "spaceStation", ignore = true)
  Location toEntity(LocationDto dto);

  /**
   * Clears {@code id} and {@code version} on a freshly mapped entity for the create flow, so a
   * client cannot pre-set them and JPA performs an INSERT.
   */
  static Location stripServerManaged(Location entity) {
    if (entity != null) {
      entity.setId(null);
      entity.setVersion(null);
    }
    return entity;
  }
}
