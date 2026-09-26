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

import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity-to-DTO mapper for {@link PersonalBlueprint}.
 *
 * <p>The {@code ownerUserId} is never exposed; {@code removable} is computed by the service
 * (REQ-INV-016) and passed in.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PersonalBlueprintMapper {

  /**
   * Maps an owned blueprint to its response DTO, flattening {@code outputItem} to its id.
   *
   * @param entity the owned blueprint
   * @param removable whether the owner may delete the entry ({@code false} for a default blueprint)
   * @return the response DTO
   */
  @Mapping(target = "outputItemId", source = "entity.outputItem.id")
  @Mapping(target = "removable", source = "removable")
  PersonalBlueprintResponse toResponse(PersonalBlueprint entity, boolean removable);
}
