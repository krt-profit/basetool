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

package de.greluc.krt.profit.basetool.backend.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the promotion system entity model (Schritt 1). Verifies builder construction,
 * relationships and nullable fields.
 */
class PromotionEntityTest {

  @Test
  void promotionTopic_shouldBuildWithRequiredFields() {
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(1).build();

    assertEquals("Grundlagen", topic.getName());
    assertEquals(1, topic.getSortOrder());
    assertNull(topic.getDescription());
    assertNotNull(topic.getCategories());
    assertTrue(topic.getCategories().isEmpty());
  }

  @Test
  void promotionCategory_shouldReferenceTopicAndHaveOptionalDescription() {
    PromotionTopic topic = PromotionTopic.builder().name("Grundlagen").sortOrder(0).build();

    PromotionCategory category =
        PromotionCategory.builder().topic(topic).name("Flug Kenntnisse").sortOrder(0).build();

    assertEquals("Flug Kenntnisse", category.getName());
    assertSame(topic, category.getTopic());
    assertNull(category.getDescription());
  }

  @Test
  void promotionLevelContent_shouldStoreAllThreeLevels() {
    PromotionCategory category =
        PromotionCategory.builder().name("Flug Kenntnisse").sortOrder(0).build();

    PromotionLevelContent contentA =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_A)
            .description("Kann von A nach B fliegen")
            .build();
    PromotionLevelContent contentB =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_B)
            .description("Kann triangulieren")
            .build();
    PromotionLevelContent contentC =
        PromotionLevelContent.builder()
            .category(category)
            .level(PromotionLevel.LEVEL_C)
            .description("Kann Triangulations-Daten erzeugen")
            .build();

    assertEquals(PromotionLevel.LEVEL_A, contentA.getLevel());
    assertEquals(PromotionLevel.LEVEL_B, contentB.getLevel());
    assertEquals(PromotionLevel.LEVEL_C, contentC.getLevel());
  }

  @Test
  void rankRequirement_shouldAllowNullTopicAndCategory() {
    RankRequirement req =
        RankRequirement.builder()
            .fromRank(20)
            .toRank(19)
            .minimumLevel(PromotionLevel.LEVEL_A)
            .requiredCount(3)
            .description("Grundlagen I-IV mindestens Stufe A in 3 Bereichen")
            .build();

    assertEquals(20, req.getFromRank());
    assertEquals(19, req.getToRank());
    assertNull(req.getTopic());
    assertNull(req.getCategory());
    assertEquals(PromotionLevel.LEVEL_A, req.getMinimumLevel());
    assertEquals(3, req.getRequiredCount());
  }

  @Test
  void memberEvaluation_shouldAllowNullAssignedLevel() {
    PromotionCategory category =
        PromotionCategory.builder().name("Anwesenheit").sortOrder(4).build();

    MemberEvaluation evaluation =
        MemberEvaluation.builder()
            .userId(UUID.fromString("12312312-3123-4123-8123-123123123123"))
            .category(category)
            .assignedLevel(null)
            .build();

    assertEquals(UUID.fromString("12312312-3123-4123-8123-123123123123"), evaluation.getUserId());
    assertSame(category, evaluation.getCategory());
    assertNull(evaluation.getAssignedLevel());
  }

  @Test
  void memberEvaluation_shouldStoreAssignedLevel() {
    PromotionCategory category =
        PromotionCategory.builder().name("Anwesenheit").sortOrder(4).build();

    MemberEvaluation evaluation =
        MemberEvaluation.builder()
            .userId(UUID.fromString("45645645-6456-4456-8456-456456456456"))
            .category(category)
            .assignedLevel(PromotionLevel.LEVEL_B)
            .build();

    assertEquals(PromotionLevel.LEVEL_B, evaluation.getAssignedLevel());
  }

  @Test
  void promotionLevel_shouldHaveThreeValues() {
    PromotionLevel[] levels = PromotionLevel.values();

    assertEquals(3, levels.length);
    assertEquals(PromotionLevel.LEVEL_A, levels[0]);
    assertEquals(PromotionLevel.LEVEL_B, levels[1]);
    assertEquals(PromotionLevel.LEVEL_C, levels[2]);
  }
}
