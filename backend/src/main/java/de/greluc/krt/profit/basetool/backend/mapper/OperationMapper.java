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

import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionOperationSummary;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Operation entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {SquadronMapper.class})
public interface OperationMapper {

  /**
   * Maps an {@link Operation} to its DTO, publishing the owning org unit as {@code owningSquadron}.
   *
   * @param entity the entity to project; {@code null} returns {@code null}
   * @return the operation DTO
   */
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "payoutPreliminary", ignore = true)
  @Mapping(target = "withPayoutPreliminary", ignore = true)
  OperationDto toDto(Operation entity);

  /**
   * Maps the summary the mission module embeds to the same DTO {@link #toDto(Operation)} builds
   * from the entity; the payout flag stays {@code null}.
   *
   * @param summary the summary to project; {@code null} returns {@code null}
   * @return the operation DTO
   */
  @Mapping(target = "owningSquadron", expression = "java(owningSquadron(summary))")
  @Mapping(target = "payoutPreliminary", ignore = true)
  @Mapping(target = "withPayoutPreliminary", ignore = true)
  OperationDto toDtoFromSummary(MissionOperationSummary summary);

  /**
   * Builds the owning-squadron reference of a summary.
   *
   * @param summary the summary carrying the owning org unit's columns
   * @return the reference, or {@code null} for an ownerless operation
   */
  @Nullable
  default SquadronReferenceDto owningSquadron(MissionOperationSummary summary) {
    if (summary.owningOrgUnitId() == null) {
      return null;
    }
    return new SquadronReferenceDto(
        summary.owningOrgUnitId(), summary.owningOrgUnitName(), summary.owningOrgUnitShorthand());
  }

  /**
   * Builds a new {@link Operation} entity from a create-DTO. Server-managed fields ({@code id},
   * timestamps) and the {@code missions} association are owned by the service layer and stripped
   * here.
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "missions", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "owningOrgUnit", ignore = true)
  Operation toEntity(OperationCreateDto dto);
}
