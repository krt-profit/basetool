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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity-to-DTO mapper for {@link PersonalBlueprint}.
 *
 * <p>The {@code ownerUserId} is never exposed; {@code removable} (REQ-INV-016) and the source
 * client's display name (REQ-INV-054) are resolved by the service and passed in.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PersonalBlueprintMapper {

  /**
   * Maps an owned blueprint to its response DTO, flattening {@code outputItem} to its id.
   *
   * @param entity the owned blueprint
   * @param removable whether the owner may delete the entry ({@code false} for a default blueprint)
   * @param sourceClientName the registry display name of the entry's source client, or {@code null}
   * @return the response DTO
   */
  @Mapping(target = "outputItemId", source = "entity.outputItem.id")
  @Mapping(target = "removable", source = "removable")
  @Mapping(target = "sourceClientName", source = "sourceClientName")
  PersonalBlueprintResponse toResponse(
      PersonalBlueprint entity, boolean removable, @Nullable String sourceClientName);
}
