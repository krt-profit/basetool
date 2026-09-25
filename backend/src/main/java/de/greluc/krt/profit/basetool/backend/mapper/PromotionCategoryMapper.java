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

import de.greluc.krt.profit.basetool.backend.model.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionCategoryWriteRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Entity ↔ DTO mapper for {@link PromotionCategory}. */
@Mapper(config = CentralMapperConfig.class)
public interface PromotionCategoryMapper {

  /**
   * Converts a {@link PromotionCategory} into its {@link PromotionCategoryResponse}, flattening the
   * topic's id and name.
   *
   * @param entity the category to convert
   * @return the response DTO
   */
  @Mapping(target = "topicId", source = "topic.id")
  @Mapping(target = "topicName", source = "topic.name")
  PromotionCategoryResponse toResponse(PromotionCategory entity);

  /**
   * Builds a new {@link PromotionCategory} entity from a creation request. The {@code topic}
   * association and {@code levelContents} collection are left unset and must be wired by the
   * service layer. The request's {@code version} is ignored so a client-supplied value never seeds
   * the JPA {@code @Version} on the transient entity.
   *
   * @param request validated payload describing the new category
   * @return a transient entity ready to be persisted
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "topic", ignore = true)
  @Mapping(target = "levelContents", ignore = true)
  PromotionCategory toEntity(PromotionCategoryWriteRequest request);

  /**
   * Copies the writable fields from a {@link PromotionCategoryWriteRequest} onto an existing
   * managed {@link PromotionCategory}. Identity, audit, and association fields are deliberately
   * ignored to preserve them across the update.
   *
   * @param entity the managed category to mutate in place
   * @param request validated payload carrying the new field values
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "topic", ignore = true)
  @Mapping(target = "levelContents", ignore = true)
  void updateEntity(@MappingTarget PromotionCategory entity, PromotionCategoryWriteRequest request);
}
