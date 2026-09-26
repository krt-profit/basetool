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

import de.greluc.krt.profit.basetool.backend.model.PromotionLevelContent;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionLevelContentWriteRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Entity ↔ DTO mapper for {@link PromotionLevelContent}. */
@Mapper(config = CentralMapperConfig.class)
public interface PromotionLevelContentMapper {

  /**
   * Converts a {@link PromotionLevelContent} into its {@link PromotionLevelContentResponse},
   * flattening the owning category's id and name.
   *
   * @param entity the level content to convert
   * @return the response DTO
   */
  @Mapping(target = "categoryId", source = "category.id")
  @Mapping(target = "categoryName", source = "category.name")
  PromotionLevelContentResponse toResponse(PromotionLevelContent entity);

  /**
   * Builds a new {@link PromotionLevelContent} entity from a creation request. The {@code category}
   * association is left unset and must be wired by the service layer. The request's {@code version}
   * is ignored so a client-supplied value never seeds the JPA {@code @Version} on the transient
   * entity.
   *
   * @param request validated payload describing the new level content
   * @return a transient entity ready to be persisted
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "category", ignore = true)
  PromotionLevelContent toEntity(PromotionLevelContentWriteRequest request);

  /**
   * Copies the writable fields from a {@link PromotionLevelContentWriteRequest} onto an existing
   * managed {@link PromotionLevelContent}. Identity, audit, and association fields are deliberately
   * ignored to preserve them across the update.
   *
   * @param entity the managed level content to mutate in place
   * @param request validated payload carrying the new field values
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "category", ignore = true)
  void updateEntity(
      @MappingTarget PromotionLevelContent entity, PromotionLevelContentWriteRequest request);
}
