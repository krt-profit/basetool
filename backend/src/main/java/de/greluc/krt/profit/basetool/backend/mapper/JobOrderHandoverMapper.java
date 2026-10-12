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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverItemDto;
import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Job Order Handover entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {MaterialMapper.class, UserMapper.class, SquadronMapper.class})
public interface JobOrderHandoverMapper {

  /**
   * Maps a {@link JobOrderHandover} entity to its DTO, flattening the parent job-order id and
   * projecting the executing-user + squadron audit snapshot through their reference mappers (slim
   * payload — the handover detail view never needs the full {@link
   * de.greluc.krt.profit.basetool.backend.model.User} aggregate).
   */
  @Mapping(source = "jobOrder.id", target = "jobOrderId")
  @Mapping(source = "executingUser", target = "executingUser")
  @Mapping(source = "executingSquadron", target = "executingSquadron")
  JobOrderHandoverDto toDto(JobOrderHandover jobOrderHandover);

  /** Maps a {@link JobOrderHandoverItem} entity to its DTO, flattening the parent handover id. */
  @Mapping(source = "jobOrderHandover.id", target = "jobOrderHandoverId")
  JobOrderHandoverItemDto toDto(JobOrderHandoverItem jobOrderHandoverItem);
}
