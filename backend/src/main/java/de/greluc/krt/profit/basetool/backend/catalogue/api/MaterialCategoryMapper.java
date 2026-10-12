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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Material Category entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface MaterialCategoryMapper {
  /** Maps a {@link MaterialCategory} entity to its outbound DTO. */
  MaterialCategoryDto toDto(MaterialCategory entity);

  /**
   * Builds a new {@link MaterialCategory} entity from the inbound DTO. Timestamps are owned by the
   * persistence provider and ignored.
   */
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  MaterialCategory toEntity(MaterialCategoryDto dto);
}
