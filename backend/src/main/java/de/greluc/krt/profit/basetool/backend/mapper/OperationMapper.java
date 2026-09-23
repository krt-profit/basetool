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
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Operation entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {SquadronMapper.class})
public interface OperationMapper {

  /**
   * Maps an {@link Operation} entity to its outbound DTO.
   *
   * <p>After R9 Step 2 the operation entity exposes {@code owningOrgUnit} (typed {@code OrgUnit});
   * the DTO still publishes {@code owningSquadron} as {@code SquadronReferenceDto} for API
   * stability. The explicit mapping routes the source through {@code
   * SquadronMapper.orgUnitToReferenceDto}, which projects either kind — a Staffel or a
   * Spezialkommando — into the slim owner reference (id/name/shorthand), so SK-owned operations now
   * surface their SK badge instead of a blank cell.
   *
   * @param entity the operation entity to project; {@code null} returns {@code null}.
   * @return the populated operation DTO.
   */
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  // Stamped by the read path through OperationDto#withPayoutPreliminary, not an entity column.
  @Mapping(target = "payoutPreliminary", ignore = true)
  // MapStruct reads a record's wither as a fluent setter of a property named after it; there
  // is no such property, so it is ignored.
  @Mapping(target = "withPayoutPreliminary", ignore = true)
  OperationDto toDto(Operation entity);

  /**
   * Builds a new {@link Operation} entity from a create-DTO. Server-managed fields ({@code id},
   * timestamps) and the {@code missions} association are owned by the service layer and stripped
   * here.
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "missions", ignore = true)
  // A new row starts unversioned; the owning org unit is stamped by the service at create.
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "owningOrgUnit", ignore = true)
  Operation toEntity(OperationCreateDto dto);
}
