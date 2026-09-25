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

import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between {@link KommandoGroup} entities and their read model. */
@Mapper(config = CentralMapperConfig.class)
public interface KommandoGroupMapper {

  /**
   * Projects a {@link KommandoGroup} into its read model, flattening the owning Staffel's id into
   * {@code squadronId}.
   *
   * @param group the persisted group; {@code null} maps to {@code null}
   * @return the DTO with id, squadron id, name, sort index and version
   */
  @Mapping(target = "squadronId", source = "squadron.id")
  KommandoGroupDto toDto(KommandoGroup group);
}
