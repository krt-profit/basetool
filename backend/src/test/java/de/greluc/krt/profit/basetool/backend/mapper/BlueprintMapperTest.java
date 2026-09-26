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

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintRequirementModifierDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintModifierSegment;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Unit tests for the MapStruct {@link BlueprintMapper}. */
class BlueprintMapperTest {

  private final BlueprintMapper mapper = Mappers.getMapper(BlueprintMapper.class);

  @Test
  void toDto_mapsHeaderGroupsModifiersAndIngredientSnapshotName() {
    Blueprint bp = new Blueprint();
    bp.setScwikiUuid(UUID.randomUUID());
    bp.setScwikiKey("BP_CRAFT_AMRS_LaserCannon_S1");
    bp.setOutputName("Omnisky III Cannon");
    bp.setIsAvailableByDefault(false);
    bp.setCraftTimeSeconds(540);

    BlueprintRequirementGroup group = new BlueprintRequirementGroup();
    group.setOrderIndex(0);
    group.setName("Emitter");
    group.setGroupKey("EMITTER");
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setOrderIndex(0);
    modifier.setPropertyKey("weapon_damage");
    modifier.setLabel("Impact Force");
    modifier.setBetterWhen("higher");
    modifier.setModifierAtMinQuality(0.95);
    modifier.setModifierAtMaxQuality(1.05);
    group.addModifier(modifier);
    bp.addRequirementGroup(group);

    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setOrderIndex(0);
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setWikiNameSnapshot("Hadanite");
    ingredient.setQuantityUnits(7);
    ingredient.setRequirementGroup(group);
    bp.addIngredient(ingredient);

    BlueprintDto dto = mapper.toDto(bp);

    assertEquals("Omnisky III Cannon", dto.outputName());
    assertEquals(540, dto.craftTimeSeconds());
    assertEquals(1, dto.requirementGroups().size());
    assertEquals("Emitter", dto.requirementGroups().get(0).name());
    assertEquals(1, dto.requirementGroups().get(0).modifiers().size());
    assertEquals("Impact Force", dto.requirementGroups().get(0).modifiers().get(0).label());
    assertEquals(1.05, dto.requirementGroups().get(0).modifiers().get(0).modifierAtMaxQuality());
    assertEquals(1, dto.ingredients().size());
    assertEquals("Hadanite", dto.ingredients().get(0).name());
    assertEquals("ITEM", dto.ingredients().get(0).kind());
    assertEquals(7, dto.ingredients().get(0).quantityUnits());
  }

  @Test
  void toModifierDto_linearModifierKeepsRawBandAsEffectiveBand() {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setQualityMin(0.0);
    modifier.setQualityMax(1000.0);
    modifier.setModifierAtMinQuality(0.95);
    modifier.setModifierAtMaxQuality(1.05);

    BlueprintRequirementModifierDto dto = mapper.toModifierDto(modifier);

    assertEquals(0.0, dto.effectiveQualityMin());
    assertEquals(1000.0, dto.effectiveQualityMax());
  }

  @Test
  void toModifierDto_steppedModifierSpansUnionOfSegmentBoundsNotFirstSegmentOnly() {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setQualityMin(0.0);
    modifier.setQualityMax(500.0);

    BlueprintModifierSegment first = new BlueprintModifierSegment();
    first.setOrderIndex(0);
    first.setQualityMin(0.0);
    first.setQualityMax(500.0);
    first.setModifierAtStart(0.8);
    first.setModifierAtEnd(1.0);
    modifier.addSegment(first);

    BlueprintModifierSegment second = new BlueprintModifierSegment();
    second.setOrderIndex(1);
    second.setQualityMin(501.0);
    second.setQualityMax(1000.0);
    second.setModifierAtStart(1.0);
    second.setModifierAtEnd(1.2);
    modifier.addSegment(second);

    BlueprintRequirementModifierDto dto = mapper.toModifierDto(modifier);

    assertEquals(0.0, dto.qualityMin());
    assertEquals(500.0, dto.qualityMax());
    assertEquals(0.0, dto.effectiveQualityMin());
    assertEquals(1000.0, dto.effectiveQualityMax());
  }
}
