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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDismantleReturnDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementIngredientDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementModifierDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementModifierSegmentDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintSummaryPropertyDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintDismantleReturn;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintModifierSegment;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintSummaryProperty;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from the blueprint recipe-graph entities to their read-side DTOs. Ingredient
 * names come from the always-present {@code wikiNameSnapshot} (so mapping a page never triggers a
 * material / game-item lazy load); the owned collections map element-wise via the per-type methods.
 */
@Mapper(config = CentralMapperConfig.class)
public interface BlueprintMapper {

  /**
   * Maps a blueprint aggregate to its DTO, including the requirement groups (with their modifiers
   * and ingredients), the flat ingredient list, the summary-property roll-up and the dismantle
   * returns.
   *
   * @param entity the blueprint entity
   * @return the blueprint DTO
   */
  BlueprintDto toDto(Blueprint entity);

  /**
   * Maps a requirement group (build slot) to its DTO with nested modifiers and ingredients.
   *
   * @param group the requirement-group entity
   * @return the requirement-group DTO
   */
  BlueprintRequirementGroupDto toGroupDto(BlueprintRequirementGroup group);

  /**
   * Maps requirement groups element-wise via {@link #toGroupDto(BlueprintRequirementGroup)}.
   *
   * @param groups the requirement-group entities (may be {@code null})
   * @return the requirement-group DTOs, or {@code null} when {@code groups} is {@code null}
   */
  List<BlueprintRequirementGroupDto> toGroupDtos(List<BlueprintRequirementGroup> groups);

  /**
   * Maps ingredient lines element-wise via {@link #toIngredientDto(BlueprintIngredient)}.
   *
   * @param ingredients the ingredient entities (may be {@code null})
   * @return the ingredient DTOs, or {@code null} when {@code ingredients} is {@code null}
   */
  List<BlueprintRequirementIngredientDto> toIngredientDtos(List<BlueprintIngredient> ingredients);

  /**
   * Maps a requirement modifier to its DTO, with the effective quality band derived from its
   * segments.
   *
   * @param modifier the modifier entity
   * @return the modifier DTO
   */
  @Mapping(target = "effectiveQualityMin", expression = "java(effectiveQualityMin(modifier))")
  @Mapping(target = "effectiveQualityMax", expression = "java(effectiveQualityMax(modifier))")
  BlueprintRequirementModifierDto toModifierDto(BlueprintRequirementModifier modifier);

  /**
   * Computes the lowest ingredient quality the modifier actually covers. For a stepped modifier the
   * SC Wiki fills the raw {@code qualityMin} with only the first segment's bound, so the smallest
   * segment {@code qualityMin} is used; for the simple linear form (no segments) the raw {@code
   * qualityMin} is returned unchanged.
   *
   * @param modifier the modifier entity
   * @return the effective lower quality bound, or {@code null} when neither segments nor the raw
   *     bound provide one
   */
  default Double effectiveQualityMin(@NotNull BlueprintRequirementModifier modifier) {
    List<BlueprintModifierSegment> segments = modifier.getSegments();
    if (segments == null || segments.isEmpty()) {
      return modifier.getQualityMin();
    }
    return segments.stream()
        .map(BlueprintModifierSegment::getQualityMin)
        .filter(Objects::nonNull)
        .min(Double::compareTo)
        .orElseGet(modifier::getQualityMin);
  }

  /**
   * Computes the highest ingredient quality the modifier covers: the largest segment {@code
   * qualityMax} when stepped, else the raw {@code qualityMax}.
   *
   * @param modifier the modifier entity
   * @return the effective upper quality bound, or {@code null} when neither segments nor the raw
   *     bound provide one
   */
  default Double effectiveQualityMax(@NotNull BlueprintRequirementModifier modifier) {
    List<BlueprintModifierSegment> segments = modifier.getSegments();
    if (segments == null || segments.isEmpty()) {
      return modifier.getQualityMax();
    }
    return segments.stream()
        .map(BlueprintModifierSegment::getQualityMax)
        .filter(Objects::nonNull)
        .max(Double::compareTo)
        .orElseGet(modifier::getQualityMax);
  }

  /**
   * Maps one modifier curve segment to its DTO (field names match, so the mapping is implicit).
   *
   * @param segment the segment entity
   * @return the segment DTO
   */
  BlueprintRequirementModifierSegmentDto toSegmentDto(BlueprintModifierSegment segment);

  /**
   * Maps an ingredient line to its DTO, taking the name from the Wiki snapshot and, for a resolved
   * RESOURCE line, the material's {@code quantityType}; other lines carry a {@code null} quantity
   * type.
   *
   * @param ingredient the ingredient entity
   * @return the ingredient DTO
   */
  @Mapping(target = "name", source = "wikiNameSnapshot")
  @Mapping(
      target = "kind",
      expression = "java(ingredient.getKind() == null ? null : ingredient.getKind().name())")
  @Mapping(
      target = "quantityType",
      expression =
          "java(ingredient.getMaterial() == null || ingredient.getMaterial().getQuantityType()"
              + " == null ? null : ingredient.getMaterial().getQuantityType().name())")
  BlueprintRequirementIngredientDto toIngredientDto(BlueprintIngredient ingredient);

  /**
   * Maps a summary property (aggregated affected stat) to its DTO.
   *
   * @param summary the summary-property entity
   * @return the summary-property DTO
   */
  BlueprintSummaryPropertyDto toSummaryDto(BlueprintSummaryProperty summary);

  /**
   * Maps a dismantle-return line to its DTO, taking the display name from the Wiki snapshot.
   *
   * @param dismantleReturn the dismantle-return entity
   * @return the dismantle-return DTO
   */
  @Mapping(target = "name", source = "wikiNameSnapshot")
  BlueprintDismantleReturnDto toDismantleReturnDto(BlueprintDismantleReturn dismantleReturn);
}
