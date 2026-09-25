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

import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExternalAliasDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between {@link MaterialExternalAlias} entities and the {@link
 * MaterialExternalAliasDto} response shape. Write payloads use the dedicated {@code …Request}
 * records and are handled directly by {@code MaterialExternalAliasService} (no MapStruct entity
 * construction).
 */
@Mapper(config = CentralMapperConfig.class)
public interface MaterialExternalAliasMapper {

  /**
   * Maps an alias entity to its DTO, flattening {@code material.id} and {@code material.name}.
   *
   * @param entity the persistent alias row
   * @return the DTO
   */
  @Mapping(target = "materialId", source = "material.id")
  @Mapping(target = "materialName", source = "material.name")
  @Mapping(target = "sourceSystem", expression = "java(entity.getSourceSystem().name())")
  MaterialExternalAliasDto toDto(MaterialExternalAlias entity);
}
