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

package de.greluc.krt.profit.basetool.frontend.promotion.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.promotion.client.PromotionBackendClient;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionCategoryWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionLevelContentWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementDto;
import de.greluc.krt.profit.basetool.frontend.promotion.model.RankRequirementWriteRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Mockito unit tests for {@link PromotionProxyController}: URI shape, body and response
 * propagation, and {@code 204 No Content} on DELETE for all 13 endpoints. Authorization is not
 * exercised here.
 */
@SuppressWarnings("rawtypes")
@ExtendWith(MockitoExtension.class)
class PromotionProxyControllerTest {

  private static final UUID ID = UUID.fromString("4a5b6c7d-8e9f-4a0b-8c1d-2e3f4a5b6c7d");
  private static final UUID TOPIC_ID = UUID.fromString("9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a");
  private static final UUID CATEGORY_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-9c8d-0e1f2a3b4c5d");
  private static final UUID USER_ID = UUID.fromString("6c5d4e3f-2a1b-4c0d-9e8f-7a6b5c4d3e2f");

  @Mock private BackendApiClient backendApiClient;

  private PromotionProxyController controller;

  @BeforeEach
  void setUp() {
    controller = new PromotionProxyController(new PromotionBackendClient(backendApiClient));
  }

  @Test
  void createTopic_forwardsBodyToBackendTopicsEndpoint() {
    PromotionTopicWriteRequest body = new PromotionTopicWriteRequest("Combat", null, 0, null);
    PromotionTopicDto backendResponse =
        new PromotionTopicDto(ID, 0L, "Combat", null, 0, null, null, null);
    when(backendApiClient.post(
            eq("/api/v1/promotion/topics"), eq(body), eq(PromotionTopicDto.class)))
        .thenReturn(backendResponse);

    PromotionTopicDto result = controller.createTopic(body);

    assertEquals(backendResponse, result);
    verify(backendApiClient).post("/api/v1/promotion/topics", body, PromotionTopicDto.class);
  }

  @Test
  void updateTopic_appendsPathVariableAndForwardsBody() {
    PromotionTopicWriteRequest body = new PromotionTopicWriteRequest("Renamed", null, 0, 0L);
    PromotionTopicDto backendResponse =
        new PromotionTopicDto(ID, 1L, "Renamed", null, 0, null, null, null);
    when(backendApiClient.put(
            eq("/api/v1/promotion/topics/{id}"), eq(body), eq(PromotionTopicDto.class), eq(ID)))
        .thenReturn(backendResponse);

    PromotionTopicDto result = controller.updateTopic(ID, body);

    assertEquals(backendResponse, result);
    verify(backendApiClient)
        .put("/api/v1/promotion/topics/{id}", body, PromotionTopicDto.class, ID);
  }

  @Test
  void deleteTopic_returnsNoContent_andDelegatesToBackend() {
    when(backendApiClient.delete(anyString(), anyClass(), any(Object[].class))).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteTopic(ID);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/topics/{id}", Void.class, ID);
  }

  @Test
  void createCategory_forwardsBodyToBackendCategoriesEndpoint() {
    PromotionCategoryWriteRequest body =
        new PromotionCategoryWriteRequest(TOPIC_ID, "Anwesenheit", null, 0, null);
    when(backendApiClient.post(
            eq("/api/v1/promotion/categories"), eq(body), eq(PromotionCategoryDto.class)))
        .thenReturn(
            new PromotionCategoryDto(
                ID, 0L, TOPIC_ID, "Combat", "Anwesenheit", null, 0, null, null));

    assertNotNull(controller.createCategory(body));
    verify(backendApiClient).post("/api/v1/promotion/categories", body, PromotionCategoryDto.class);
  }

  @Test
  void updateCategory_appendsPathVariable() {
    PromotionCategoryWriteRequest body =
        new PromotionCategoryWriteRequest(TOPIC_ID, "Renamed", null, 0, 0L);
    when(backendApiClient.put(
            anyString(), eq(body), eq(PromotionCategoryDto.class), any(Object[].class)))
        .thenReturn(null);

    controller.updateCategory(ID, body);

    verify(backendApiClient)
        .put("/api/v1/promotion/categories/{id}", body, PromotionCategoryDto.class, ID);
  }

  @Test
  void deleteCategory_returnsNoContent() {
    when(backendApiClient.delete(anyString(), anyClass(), any(Object[].class))).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteCategory(ID);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/categories/{id}", Void.class, ID);
  }

  @Test
  void createRankRequirement_forwardsBodyToBackend() {
    RankRequirementWriteRequest body =
        new RankRequirementWriteRequest(20, 19, null, CATEGORY_ID, "LEVEL_A", 1, null, null);
    when(backendApiClient.post(
            eq("/api/v1/promotion/rank-requirements"), eq(body), eq(RankRequirementDto.class)))
        .thenReturn(
            new RankRequirementDto(
                ID,
                0L,
                20,
                19,
                null,
                null,
                CATEGORY_ID,
                "Anwesenheit",
                "LEVEL_A",
                1,
                null,
                null,
                null));

    assertNotNull(controller.createRankRequirement(body));
    verify(backendApiClient)
        .post("/api/v1/promotion/rank-requirements", body, RankRequirementDto.class);
  }

  @Test
  void updateRankRequirement_appendsPathVariable() {
    RankRequirementWriteRequest body =
        new RankRequirementWriteRequest(20, 19, null, CATEGORY_ID, "LEVEL_A", 1, null, 0L);
    when(backendApiClient.put(
            anyString(), eq(body), eq(RankRequirementDto.class), any(Object[].class)))
        .thenReturn(null);

    controller.updateRankRequirement(ID, body);

    verify(backendApiClient)
        .put("/api/v1/promotion/rank-requirements/{id}", body, RankRequirementDto.class, ID);
  }

  @Test
  void deleteRankRequirement_returnsNoContent() {
    when(backendApiClient.delete(anyString(), anyClass(), any(Object[].class))).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteRankRequirement(ID);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/rank-requirements/{id}", Void.class, ID);
  }

  @Test
  void createLevelContent_forwardsBodyToBackend() {
    PromotionLevelContentWriteRequest body =
        new PromotionLevelContentWriteRequest(CATEGORY_ID, "LEVEL_A", "x", null);
    when(backendApiClient.post(
            eq("/api/v1/promotion/level-contents"), eq(body), eq(PromotionLevelContentDto.class)))
        .thenReturn(
            new PromotionLevelContentDto(
                ID, 0L, CATEGORY_ID, "Anwesenheit", "LEVEL_A", "x", null, null));

    assertNotNull(controller.createLevelContent(body));
    verify(backendApiClient)
        .post("/api/v1/promotion/level-contents", body, PromotionLevelContentDto.class);
  }

  @Test
  void updateLevelContent_appendsPathVariable() {
    PromotionLevelContentWriteRequest body =
        new PromotionLevelContentWriteRequest(CATEGORY_ID, "LEVEL_A", "updated", 0L);
    when(backendApiClient.put(
            anyString(), eq(body), eq(PromotionLevelContentDto.class), any(Object[].class)))
        .thenReturn(null);

    controller.updateLevelContent(ID, body);

    verify(backendApiClient)
        .put("/api/v1/promotion/level-contents/{id}", body, PromotionLevelContentDto.class, ID);
  }

  @Test
  void deleteLevelContent_returnsNoContent() {
    when(backendApiClient.delete(anyString(), anyClass(), any(Object[].class))).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteLevelContent(ID);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/level-contents/{id}", Void.class, ID);
  }

  @Test
  void updateEvaluation_buildsUserCategoryPath_andForwardsBody() {
    MemberEvaluationUpdateRequest body = new MemberEvaluationUpdateRequest(0L, "LEVEL_B");
    MemberEvaluationDto backendResponse =
        new MemberEvaluationDto(
            ID,
            1L,
            USER_ID.toString(),
            CATEGORY_ID,
            "Anwesenheit",
            TOPIC_ID,
            "Combat",
            "LEVEL_B",
            null,
            null);
    String expectedUri =
        "/api/v1/promotion/evaluations/user/" + USER_ID + "/category/" + CATEGORY_ID;
    when(backendApiClient.put(eq(expectedUri), eq(body), eq(MemberEvaluationDto.class)))
        .thenReturn(backendResponse);

    MemberEvaluationDto result = controller.updateEvaluation(USER_ID, CATEGORY_ID, body);

    assertEquals(backendResponse, result);
    verify(backendApiClient).put(expectedUri, body, MemberEvaluationDto.class);
  }

  @Test
  void updateEvaluation_handlesNullAssignedLevelInBody() {
    MemberEvaluationUpdateRequest body = new MemberEvaluationUpdateRequest(0L, null);
    when(backendApiClient.put(anyString(), eq(body), eq(MemberEvaluationDto.class)))
        .thenReturn(null);

    controller.updateEvaluation(USER_ID, CATEGORY_ID, body);

    verify(backendApiClient, times(1))
        .put(
            "/api/v1/promotion/evaluations/user/" + USER_ID + "/category/" + CATEGORY_ID,
            body,
            MemberEvaluationDto.class);
  }
}
