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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Pure-Mockito unit tests for {@link PromotionProxyController}.
 *
 * <p>This controller is intentionally a thin pass-through: each endpoint forwards exactly one verb
 * to a deterministic backend URI. The tests therefore focus on the *contract* — URI shape, body
 * propagation, response body propagation, and the {@code 204 No Content} convention on DELETE —
 * rather than on any business logic. The five resource families (topics, categories,
 * rank-requirements, level-contents, evaluations) plus the single PUT endpoint for evaluations
 * yield 13 endpoints total, each covered by a targeted test below.
 *
 * <p>The {@code @PreAuthorize("hasAnyRole('ADMIN', 'OFFICER')")} guard on every endpoint is
 * enforced by Spring Security at the framework layer and is not exercised by these unit tests; the
 * authorization wiring is verified in {@code MissionSecurityRenderingTest} and similar MockMvc
 * tests for the broader stack.
 */
@SuppressWarnings("rawtypes")
@ExtendWith(MockitoExtension.class)
class PromotionProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private PromotionProxyController controller;

  // ── Topics ──────────────────────────────────────────────────────────────

  @Test
  void createTopic_forwardsBodyToBackendTopicsEndpoint() {
    Map<String, Object> body = Map.of("name", "Combat", "sortOrder", 0);
    Map<String, Object> backendResponse = Map.of("id", UUID.randomUUID().toString());
    when(backendApiClient.post(eq("/api/v1/promotion/topics"), eq(body), eq(Map.class)))
        .thenReturn(backendResponse);

    Map<?, ?> result = controller.createTopic(body);

    assertEquals(backendResponse, result);
    verify(backendApiClient).post("/api/v1/promotion/topics", body, Map.class);
  }

  @Test
  void updateTopic_appendsPathVariableAndForwardsBody() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 0, "name", "Renamed");
    when(backendApiClient.put(eq("/api/v1/promotion/topics/" + id), eq(body), eq(Map.class)))
        .thenReturn(body);

    Map<?, ?> result = controller.updateTopic(id, body);

    assertEquals(body, result);
    verify(backendApiClient).put("/api/v1/promotion/topics/" + id, body, Map.class);
  }

  @Test
  void deleteTopic_returnsNoContent_andDelegatesToBackend() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(anyString(), anyClass())).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteTopic(id);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/topics/" + id, Void.class);
  }

  // ── Categories ──────────────────────────────────────────────────────────

  @Test
  void createCategory_forwardsBodyToBackendCategoriesEndpoint() {
    Map<String, Object> body =
        Map.of("topicId", UUID.randomUUID().toString(), "name", "Anwesenheit");
    when(backendApiClient.post(eq("/api/v1/promotion/categories"), eq(body), eq(Map.class)))
        .thenReturn(body);

    assertNotNull(controller.createCategory(body));
    verify(backendApiClient).post("/api/v1/promotion/categories", body, Map.class);
  }

  @Test
  void updateCategory_appendsPathVariable() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 0, "name", "Renamed");
    when(backendApiClient.put(anyString(), eq(body), eq(Map.class))).thenReturn(body);

    controller.updateCategory(id, body);

    verify(backendApiClient).put("/api/v1/promotion/categories/" + id, body, Map.class);
  }

  @Test
  void deleteCategory_returnsNoContent() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(anyString(), anyClass())).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteCategory(id);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/categories/" + id, Void.class);
  }

  // ── Rank Requirements ───────────────────────────────────────────────────

  @Test
  void createRankRequirement_forwardsBodyToBackend() {
    Map<String, Object> body = Map.of("fromRank", 20, "toRank", 19, "minimumLevel", "LEVEL_A");
    when(backendApiClient.post(eq("/api/v1/promotion/rank-requirements"), eq(body), eq(Map.class)))
        .thenReturn(body);

    assertNotNull(controller.createRankRequirement(body));
    verify(backendApiClient).post("/api/v1/promotion/rank-requirements", body, Map.class);
  }

  @Test
  void updateRankRequirement_appendsPathVariable() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 0, "fromRank", 20, "toRank", 19);
    when(backendApiClient.put(anyString(), eq(body), eq(Map.class))).thenReturn(body);

    controller.updateRankRequirement(id, body);

    verify(backendApiClient).put("/api/v1/promotion/rank-requirements/" + id, body, Map.class);
  }

  @Test
  void deleteRankRequirement_returnsNoContent() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(anyString(), anyClass())).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteRankRequirement(id);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/rank-requirements/" + id, Void.class);
  }

  // ── Level Contents ──────────────────────────────────────────────────────

  @Test
  void createLevelContent_forwardsBodyToBackend() {
    Map<String, Object> body =
        Map.of("categoryId", UUID.randomUUID().toString(), "level", "LEVEL_A", "description", "x");
    when(backendApiClient.post(eq("/api/v1/promotion/level-contents"), eq(body), eq(Map.class)))
        .thenReturn(body);

    assertNotNull(controller.createLevelContent(body));
    verify(backendApiClient).post("/api/v1/promotion/level-contents", body, Map.class);
  }

  @Test
  void updateLevelContent_appendsPathVariable() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 0, "description", "updated");
    when(backendApiClient.put(anyString(), eq(body), eq(Map.class))).thenReturn(body);

    controller.updateLevelContent(id, body);

    verify(backendApiClient).put("/api/v1/promotion/level-contents/" + id, body, Map.class);
  }

  @Test
  void deleteLevelContent_returnsNoContent() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(anyString(), anyClass())).thenReturn(null);

    ResponseEntity<Void> response = controller.deleteLevelContent(id);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(backendApiClient).delete("/api/v1/promotion/level-contents/" + id, Void.class);
  }

  // ── Evaluations ─────────────────────────────────────────────────────────

  @Test
  void updateEvaluation_buildsUserCategoryPath_andForwardsBody() {
    // The evaluations endpoint uniquely takes two path variables (userId is a
    // string, not a UUID, because JWT-sub strings can be either UUID-shaped
    // or opaque depending on the IdP). The proxy must concatenate both into
    // the backend URI in the right order.
    String userId = "auth0|abc123-not-a-uuid";
    UUID categoryId = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 0, "assignedLevel", "LEVEL_B");
    String expectedUri = "/api/v1/promotion/evaluations/user/" + userId + "/category/" + categoryId;
    when(backendApiClient.put(eq(expectedUri), eq(body), eq(Map.class))).thenReturn(body);

    Map<?, ?> result = controller.updateEvaluation(userId, categoryId, body);

    assertEquals(body, result);
    verify(backendApiClient).put(expectedUri, body, Map.class);
  }

  @Test
  void updateEvaluation_handlesNullAssignedLevelInBody() {
    // Setting a level back to "Keine" sends assignedLevel: null. The proxy
    // is body-agnostic; we verify the body is passed through unchanged so
    // the backend's optimistic-lock + null-handling logic stays in charge.
    String userId = "user-1";
    UUID categoryId = UUID.randomUUID();
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("version", 0);
    body.put("assignedLevel", null);
    when(backendApiClient.put(anyString(), eq(body), eq(Map.class))).thenReturn(body);

    controller.updateEvaluation(userId, categoryId, body);

    verify(backendApiClient, times(1))
        .put(
            "/api/v1/promotion/evaluations/user/" + userId + "/category/" + categoryId,
            body,
            Map.class);
  }
}
