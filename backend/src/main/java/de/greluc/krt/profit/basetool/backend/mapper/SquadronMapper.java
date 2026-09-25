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

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Squadron entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface SquadronMapper {
  /**
   * Maps a {@link Squadron} to its full DTO, including the {@code isPromotionEnabled} and {@code
   * isProfitEligible} flags.
   *
   * @param entity the squadron to project; {@code null} maps to {@code null}
   * @return the squadron DTO
   */
  @Mapping(target = "isPromotionEnabled", source = "promotionEnabled")
  @Mapping(target = "isProfitEligible", source = "profitEligible")
  SquadronDto toDto(Squadron entity);

  /**
   * Projects a squadron into the slim reference (id, name, shorthand) embedded in aggregate DTOs.
   *
   * @param entity the squadron to project; {@code null} maps to {@code null}
   * @return the reference DTO
   */
  SquadronReferenceDto toReferenceDto(Squadron entity);

  /**
   * Projects any {@link OrgUnit} (Staffel or Spezialkommando) into the slim {@code
   * SquadronReferenceDto} used for aggregate owner fields.
   *
   * <p>Reads the base getters directly, so an uninitialised proxy projects correctly.
   *
   * @param orgUnit the owning org unit; may be {@code null}
   * @return the reference DTO, or {@code null} when {@code orgUnit} is {@code null}
   */
  @Nullable
  default SquadronReferenceDto orgUnitToReferenceDto(OrgUnit orgUnit) {
    if (orgUnit == null) {
      return null;
    }
    return new SquadronReferenceDto(orgUnit.getId(), orgUnit.getName(), orgUnit.getShorthand());
  }

  /**
   * Builds a new {@link Squadron} entity from the DTO.
   *
   * <p>Ignores timestamps, the {@code promotionEnabled} and {@code profitEligible} flags (changed
   * only through their dedicated endpoints) and the {@code parent} hierarchy link.
   */
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "promotionEnabled", ignore = true)
  @Mapping(target = "profitEligible", ignore = true)
  @Mapping(target = "parent", ignore = true)
  @Mapping(target = "department", ignore = true)
  Squadron toEntity(SquadronDto dto);
}
