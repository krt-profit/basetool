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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.PoiDto;
import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between {@link Poi} entities and their admin-facing {@link PoiDto} projection.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PoiMapper {
  /**
   * Projects a POI entity into the slim DTO used by the admin UEX-overrides page.
   *
   * @param entity managed POI entity
   * @return DTO carrying only the fields surfaced to the admin UI
   */
  PoiDto toDto(Poi entity);
}
