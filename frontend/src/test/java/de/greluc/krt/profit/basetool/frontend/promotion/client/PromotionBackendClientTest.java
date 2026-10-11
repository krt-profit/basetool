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

package de.greluc.krt.profit.basetool.frontend.promotion.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementWriteRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link PromotionBackendClient} sends (plan F3), each the exact verb, path and
 * body the promotion controllers sent before the client existed, and the typed answers.
 */
class PromotionBackendClientTest {

  private static final UUID ID = UUID.fromString("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c");
  private static final UUID TOPIC = UUID.fromString("6a7b8c9d-0e1f-4a2b-9c3d-4e5f6a7b8c9d");
  private static final UUID CATEGORY = UUID.fromString("7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e");
  private static final UUID USER = UUID.fromString("8c9d0e1f-2a3b-4c4d-9e5f-6a7b8c9d0e1f");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":1000,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private PromotionBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new PromotionBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void pageReads() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"rank\":17}");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.topics()).isEmpty();
    assertThat(client.categoriesByTopic(TOPIC.toString())).isEmpty();
    assertThat(client.allCategories()).isNotNull();
    assertThat(client.levelContents(CATEGORY.toString())).isEmpty();
    assertThat(client.rankRequirements()).isNotNull();
    assertThat(client.currentUser().rank()).isEqualTo(17);
    assertThat(client.myEvaluations()).isEmpty();
    assertThat(client.allEvaluations(1000, 2)).isNotNull();
    assertThat(client.evaluatableMembers(1000, 0)).isNotNull();
    assertThat(client.myEligibility()).isEmpty();
    assertThat(client.eligibilityOf(USER)).isEmpty();

    backend.expect("GET", "/api/v1/promotion/topics/all");
    backend.expect("GET", "/api/v1/promotion/categories/by-topic/" + TOPIC + "/all");
    backend.expect("GET", "/api/v1/promotion/categories?size=1000");
    backend.expect("GET", "/api/v1/promotion/level-contents/by-category/" + CATEGORY);
    backend.expect("GET", "/api/v1/promotion/rank-requirements?size=1000&sort=fromRank");
    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", "/api/v1/promotion/evaluations/my");
    backend.expect("GET", "/api/v1/promotion/evaluations/all?size=1000&page=2");
    backend.expect("GET", "/api/v1/promotion/evaluations/members?size=1000&page=0");
    backend.expect("GET", "/api/v1/promotion/eligibility/my");
    backend.expect("GET", "/api/v1/promotion/eligibility/user/" + USER);
  }

  @Test
  void topicAndCategoryWrites() {
    backend.answerJson(
        "{\"id\":\""
            + ID
            + "\",\"version\":0,\"name\":\"Combat\",\"description\":null,\"sortOrder\":1,"
            + "\"owningSquadron\":null,\"createdAt\":\"2026-09-01T10:15:30Z\",\"updatedAt\":null}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    PromotionTopicDto created =
        client.createTopic(new PromotionTopicWriteRequest("Combat", null, 1, null));
    client.updateTopic(ID, new PromotionTopicWriteRequest("Combat", "d", 1, 0L));
    client.deleteTopic(ID);
    client.createCategory(new PromotionCategoryWriteRequest(TOPIC, "Anwesenheit", null, 0, null));
    client.updateCategory(ID, new PromotionCategoryWriteRequest(TOPIC, "Anwesenheit", null, 0, 2L));
    client.deleteCategory(ID);

    assertThat(created)
        .isEqualTo(
            new PromotionTopicDto(
                ID, 0L, "Combat", null, 1, null, Instant.parse("2026-09-01T10:15:30Z"), null));
    backend.expect(
        "POST",
        "/api/v1/promotion/topics",
        "{\"name\":\"Combat\",\"description\":null,\"sortOrder\":1,\"version\":null}");
    backend.expect(
        "PUT",
        "/api/v1/promotion/topics/" + ID,
        "{\"name\":\"Combat\",\"description\":\"d\",\"sortOrder\":1,\"version\":0}");
    backend.expect("DELETE", "/api/v1/promotion/topics/" + ID, null);
    backend.expect(
        "POST",
        "/api/v1/promotion/categories",
        "{\"topicId\":\""
            + TOPIC
            + "\",\"name\":\"Anwesenheit\",\"description\":null,\"sortOrder\":0,\"version\":null}");
    backend.expect(
        "PUT",
        "/api/v1/promotion/categories/" + ID,
        "{\"topicId\":\""
            + TOPIC
            + "\",\"name\":\"Anwesenheit\",\"description\":null,\"sortOrder\":0,\"version\":2}");
    backend.expect("DELETE", "/api/v1/promotion/categories/" + ID, null);
  }

  @Test
  void requirementLevelTextAndEvaluationWrites() {
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson(
        "{\"id\":\""
            + ID
            + "\",\"version\":1,\"userId\":\""
            + USER
            + "\",\"categoryId\":\""
            + CATEGORY
            + "\",\"categoryName\":\"Anwesenheit\",\"topicId\":\""
            + TOPIC
            + "\",\"topicName\":\"Combat\",\"assignedLevel\":\"LEVEL_B\",\"createdAt\":null,"
            + "\"updatedAt\":null}");

    client.createRankRequirement(
        new RankRequirementWriteRequest(20, 19, null, CATEGORY, "LEVEL_A", 2, null, null));
    client.updateRankRequirement(
        ID, new RankRequirementWriteRequest(20, 19, TOPIC, null, "LEVEL_B", 1, "x", 3L));
    client.deleteRankRequirement(ID);
    client.createLevelContent(
        new PromotionLevelContentWriteRequest(CATEGORY, "LEVEL_A", "Text", null));
    client.updateLevelContent(
        ID, new PromotionLevelContentWriteRequest(CATEGORY, "LEVEL_A", "Text", 4L));
    client.deleteLevelContent(ID);
    MemberEvaluationDto evaluation =
        client.upsertEvaluation(USER, CATEGORY, new MemberEvaluationUpdateRequest(0L, "LEVEL_B"));

    assertThat(evaluation.assignedLevel()).isEqualTo("LEVEL_B");
    backend.expect(
        "POST",
        "/api/v1/promotion/rank-requirements",
        "{\"fromRank\":20,\"toRank\":19,\"topicId\":null,\"categoryId\":\""
            + CATEGORY
            + "\",\"minimumLevel\":\"LEVEL_A\",\"requiredCount\":2,\"description\":null,"
            + "\"version\":null}");
    backend.expect(
        "PUT",
        "/api/v1/promotion/rank-requirements/" + ID,
        "{\"fromRank\":20,\"toRank\":19,\"topicId\":\""
            + TOPIC
            + "\",\"categoryId\":null,\"minimumLevel\":\"LEVEL_B\",\"requiredCount\":1,"
            + "\"description\":\"x\",\"version\":3}");
    backend.expect("DELETE", "/api/v1/promotion/rank-requirements/" + ID, null);
    backend.expect(
        "POST",
        "/api/v1/promotion/level-contents",
        "{\"categoryId\":\""
            + CATEGORY
            + "\",\"level\":\"LEVEL_A\",\"description\":\"Text\",\"version\":null}");
    backend.expect(
        "PUT",
        "/api/v1/promotion/level-contents/" + ID,
        "{\"categoryId\":\""
            + CATEGORY
            + "\",\"level\":\"LEVEL_A\",\"description\":\"Text\",\"version\":4}");
    backend.expect("DELETE", "/api/v1/promotion/level-contents/" + ID, null);
    backend.expect(
        "PUT",
        "/api/v1/promotion/evaluations/user/" + USER + "/category/" + CATEGORY,
        "{\"version\":0,\"assignedLevel\":\"LEVEL_B\"}");
  }
}
