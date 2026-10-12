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

import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity → DTO mapper for {@link DefaultBlueprint}, flattening the optional {@code outputItem}
 * association to its id for the admin default-blueprints list.
 */
@Mapper(config = CentralMapperConfig.class)
public interface DefaultBlueprintMapper {

  /**
   * Maps a default-blueprint entry to its response DTO, flattening the optional {@code outputItem}
   * association to {@code outputItemId} ({@code null} when unresolved).
   *
   * @param entity the default-blueprint entry
   * @return the response DTO
   */
  @Mapping(target = "outputItemId", source = "outputItem.id")
  DefaultBlueprintResponse toResponse(DefaultBlueprint entity);
}
