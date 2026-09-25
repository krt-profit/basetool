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

import de.greluc.krt.profit.basetool.backend.model.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartNodeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps {@link OrgChartPosition} entities to {@link OrgChartNodeDto} (a chart node) and {@link
 * OrgChartPositionDto} (the write response).
 *
 * <p>Both methods read the lazy {@code user} association and must run in the loading transaction.
 */
@Mapper(config = CentralMapperConfig.class)
public interface OrgChartPositionMapper {

  /**
   * Maps a position to a chart node with the holder's id and effective name.
   *
   * @param position the position with its {@code user} fetched; never {@code null}
   * @return the node DTO; never {@code null}
   */
  @Mapping(target = "positionId", source = "id")
  @Mapping(target = "userId", source = "user.id")
  @Mapping(target = "userName", source = "user.effectiveName")
  OrgChartNodeDto toNode(OrgChartPosition position);

  /**
   * Maps one position to the flat write-response DTO, projecting the OrgUnit id (null for area
   * leadership), the holder, and the parent id (null for a root position).
   *
   * @param position the persisted position with its {@code user} fetched; never {@code null}.
   * @return the flat DTO; never {@code null}.
   */
  @Mapping(target = "orgUnitId", source = "orgUnit.id")
  @Mapping(target = "userId", source = "user.id")
  @Mapping(target = "userName", source = "user.effectiveName")
  @Mapping(target = "parentId", source = "parent.id")
  OrgChartPositionDto toDto(OrgChartPosition position);
}
