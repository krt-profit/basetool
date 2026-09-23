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

import de.greluc.krt.profit.basetool.backend.model.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Entity ↔ DTO mapper for {@link PromotionTopic}. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {SquadronMapper.class})
public interface PromotionTopicMapper {

  /**
   * Converts a {@link PromotionTopic} into its {@link PromotionTopicResponse} DTO without exposing
   * the child categories collection.
   *
   * @param entity the managed topic to convert
   * @return the response DTO mirroring the entity's fields
   */
  PromotionTopicResponse toResponse(PromotionTopic entity);

  /**
   * Builds a new {@link PromotionTopic} entity from a creation request. The {@code categories}
   * collection is left empty and must be populated separately. The request's {@code version} is
   * ignored so a client-supplied value never seeds the JPA {@code @Version} on the transient
   * entity.
   *
   * @param request validated payload describing the new topic
   * @return a transient entity ready to be persisted
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "categories", ignore = true)
  @Mapping(target = "owningSquadron", ignore = true)
  PromotionTopic toEntity(PromotionTopicWriteRequest request);

  /**
   * Copies the writable fields from a {@link PromotionTopicWriteRequest} onto an existing managed
   * {@link PromotionTopic}. Identity, audit, and association fields are deliberately ignored to
   * preserve them across the update.
   *
   * @param entity the managed topic to mutate in place
   * @param request validated payload carrying the new field values
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "categories", ignore = true)
  @Mapping(target = "owningSquadron", ignore = true)
  void updateEntity(@MappingTarget PromotionTopic entity, PromotionTopicWriteRequest request);
}
