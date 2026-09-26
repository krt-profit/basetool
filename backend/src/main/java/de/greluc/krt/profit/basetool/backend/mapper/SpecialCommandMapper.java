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

import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps between {@link SpecialCommand} entities and {@link SpecialCommandDto}, mirroring {@link
 * SquadronMapper} except that {@code isPromotionEnabled} is not exposed.
 */
@Mapper(config = CentralMapperConfig.class)
public interface SpecialCommandMapper {

  /**
   * Maps a {@link SpecialCommand} to its DTO, including {@code isProfitEligible} but omitting audit
   * fields and {@code isPromotionEnabled}.
   *
   * @param entity the persisted entity
   * @return the DTO; never {@code null}
   */
  @Mapping(target = "isProfitEligible", source = "profitEligible")
  SpecialCommandDto toDto(SpecialCommand entity);

  /**
   * Builds a new {@link SpecialCommand} entity from the DTO.
   *
   * <p>Ignores the server-managed fields, {@code promotionEnabled}, {@code profitEligible} (changed
   * only through its dedicated toggle endpoint) and the {@code parent} hierarchy link.
   *
   * @param dto the inbound DTO; never {@code null}
   * @return a transient {@link SpecialCommand} ready to be saved
   */
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "promotionEnabled", ignore = true)
  @Mapping(target = "profitEligible", ignore = true)
  @Mapping(target = "parent", ignore = true)
  @Mapping(target = "department", ignore = true)
  SpecialCommand toEntity(SpecialCommandDto dto);
}
