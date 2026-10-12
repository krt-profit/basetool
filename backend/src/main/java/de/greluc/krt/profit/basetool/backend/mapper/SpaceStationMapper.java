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

import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SpaceStationDto;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between {@link SpaceStation} entities and their admin-facing {@link
 * SpaceStationDto} projection.
 */
@Mapper(config = CentralMapperConfig.class)
public interface SpaceStationMapper {
  /**
   * Projects a space-station entity into the slim DTO used by the admin UEX-overrides page.
   *
   * @param entity managed space-station entity
   * @return DTO carrying only the fields surfaced to the admin UI
   */
  SpaceStationDto toDto(SpaceStation entity);
}
