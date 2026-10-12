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

import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link ExternalSyncReport} to its read-only {@link SyncReportDto}. The two
 * enum fields are flattened to their {@code name()} so the wire shape carries plain strings.
 */
@Mapper(config = CentralMapperConfig.class)
public interface SyncReportMapper {

  /**
   * Maps an audit-log entity to its DTO, flattening {@code sourceSystem} / {@code eventType} to
   * their enum names.
   *
   * @param entity the persistent audit row
   * @return the wire DTO
   */
  @Mapping(target = "sourceSystem", expression = "java(entity.getSourceSystem().name())")
  @Mapping(target = "eventType", expression = "java(entity.getEventType().name())")
  SyncReportDto toDto(ExternalSyncReport entity);
}
