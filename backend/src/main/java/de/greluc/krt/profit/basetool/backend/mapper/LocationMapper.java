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
   * Strips server-managed fields from a freshly mapped entity for the POST/create flow so a client
   * cannot pre-set them via the request body (mass-assignment / over-posting). {@code id} is left
   * null so JPA performs an INSERT instead of a merge against an existing row; {@code version} is
   * left null so the persistence provider initializes the optimistic-locking counter.
   *
   * <p>Declared as a static helper rather than a default mapping method so MapStruct does not
   * consider it a candidate for nested {@code LocationDto -> Location} mappings inside other
   * mappers.
   */
  static Location stripServerManaged(Location entity) {
    if (entity != null) {
      entity.setId(null);
      entity.setVersion(null);
    }
    return entity;
  }
}
