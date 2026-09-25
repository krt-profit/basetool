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

import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity ↔ DTO mapper for {@link MemberEvaluation}. */
@Mapper(config = CentralMapperConfig.class)
public interface MemberEvaluationMapper {

  /**
   * Converts a {@link MemberEvaluation} into its {@link MemberEvaluationResponse}, flattening the
   * linked {@link de.greluc.krt.profit.basetool.backend.model.PromotionCategory} and its topic into
   * id/name pairs.
   *
   * @param entity the evaluation to convert
   * @return the response DTO
   */
  @Mapping(target = "categoryId", source = "category.id")
  @Mapping(target = "categoryName", source = "category.name")
  @Mapping(target = "topicId", source = "category.topic.id")
  @Mapping(target = "topicName", source = "category.topic.name")
  MemberEvaluationResponse toResponse(MemberEvaluation entity);
}
