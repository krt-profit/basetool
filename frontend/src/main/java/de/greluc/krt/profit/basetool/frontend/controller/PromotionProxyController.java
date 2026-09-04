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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same-origin HTTP proxy used by the promotion-system admin pages to reach the backend's {@code
 * /api/v1/promotion/...} endpoints. Every write operation goes through this controller so the
 * browser never has to know the backend's hostname and the CSRF-protected session cookie is reused
 * via {@link BackendApiClient}.
 *
 * <p>Every endpoint is gated by the {@code ADMIN} or {@code OFFICER} role at the Spring Security
 * layer; the backend re-checks authorization, so this proxy is defence-in-depth.
 */
@RestController
@RequestMapping("/api/proxy/promotion")
@RequiredArgsConstructor
public class PromotionProxyController {

  private final BackendApiClient backendApiClient;

  // --- Topics ---

  /**
   * Forwards a "create promotion topic" request to the backend.
   *
   * @param body the validated topic payload sent by the browser
   * @return the backend's response body, typically the created topic representation
   */
  @PostMapping("/topics")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> createTopic(@RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.post("/api/v1/promotion/topics", body, Map.class);
  }

  /**
   * Forwards an "update promotion topic" request to the backend.
   *
   * @param id the persistent id of the topic to update
   * @param body the validated topic payload including the version for optimistic locking
   * @return the backend's response body for the updated topic
   */
  @PutMapping("/topics/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> updateTopic(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.put("/api/v1/promotion/topics/" + id, body, Map.class);
  }

  /**
   * Forwards a "delete promotion topic" request to the backend.
   *
   * @param id the persistent id of the topic to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/topics/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteTopic(@PathVariable @NotNull UUID id) {
    backendApiClient.delete("/api/v1/promotion/topics/" + id, Void.class);
    return ResponseEntity.noContent().build();
  }

  // --- Categories ---

  /**
   * Forwards a "create promotion category" request to the backend.
   *
   * @param body the validated category payload sent by the browser
   * @return the backend's response body, typically the created category representation
   */
  @PostMapping("/categories")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> createCategory(@RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.post("/api/v1/promotion/categories", body, Map.class);
  }

  /**
   * Forwards an "update promotion category" request to the backend.
   *
   * @param id the persistent id of the category to update
   * @param body the validated category payload including the version for optimistic locking
   * @return the backend's response body for the updated category
   */
  @PutMapping("/categories/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> updateCategory(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.put("/api/v1/promotion/categories/" + id, body, Map.class);
  }

  /**
   * Forwards a "delete promotion category" request to the backend.
   *
   * @param id the persistent id of the category to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/categories/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteCategory(@PathVariable @NotNull UUID id) {
    backendApiClient.delete("/api/v1/promotion/categories/" + id, Void.class);
    return ResponseEntity.noContent().build();
  }

  // --- Rank Requirements ---

  /**
   * Forwards a "create rank requirement" request to the backend.
   *
   * @param body the validated requirement payload sent by the browser
   * @return the backend's response body, typically the created requirement representation
   */
  @PostMapping("/rank-requirements")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> createRankRequirement(@RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.post("/api/v1/promotion/rank-requirements", body, Map.class);
  }

  /**
   * Forwards an "update rank requirement" request to the backend.
   *
   * @param id the persistent id of the requirement to update
   * @param body the validated requirement payload including the version for optimistic locking
   * @return the backend's response body for the updated requirement
   */
  @PutMapping("/rank-requirements/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> updateRankRequirement(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.put("/api/v1/promotion/rank-requirements/" + id, body, Map.class);
  }

  /**
   * Forwards a "delete rank requirement" request to the backend.
   *
   * @param id the persistent id of the requirement to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/rank-requirements/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteRankRequirement(@PathVariable @NotNull UUID id) {
    backendApiClient.delete("/api/v1/promotion/rank-requirements/" + id, Void.class);
    return ResponseEntity.noContent().build();
  }

  // --- Level Contents ---

  /**
   * Forwards a "create level content" request to the backend.
   *
   * @param body the validated level-content payload sent by the browser
   * @return the backend's response body, typically the created entry representation
   */
  @PostMapping("/level-contents")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> createLevelContent(@RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.post("/api/v1/promotion/level-contents", body, Map.class);
  }

  /**
   * Forwards an "update level content" request to the backend.
   *
   * @param id the persistent id of the entry to update
   * @param body the validated payload including the version for optimistic locking
   * @return the backend's response body for the updated entry
   */
  @PutMapping("/level-contents/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> updateLevelContent(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return backendApiClient.put("/api/v1/promotion/level-contents/" + id, body, Map.class);
  }

  /**
   * Forwards a "delete level content" request to the backend.
   *
   * @param id the persistent id of the entry to delete
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/level-contents/{id}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Void> deleteLevelContent(@PathVariable @NotNull UUID id) {
    backendApiClient.delete("/api/v1/promotion/level-contents/" + id, Void.class);
    return ResponseEntity.noContent().build();
  }

  // --- Evaluations ---

  /**
   * Forwards an "upsert member evaluation" request to the backend. Personal evaluations are not
   * routed through this endpoint – the manage page targets a {@code (userId, categoryId)} pair so
   * officers can edit any member's grades.
   *
   * @param userId the JWT-sub identifier of the evaluated member
   * @param categoryId the category the evaluation applies to
   * @param body the validated payload including the version for optimistic locking
   * @return the backend's response body for the upserted evaluation
   */
  @PutMapping("/evaluations/user/{userId}/category/{categoryId}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public Map<?, ?> updateEvaluation(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID categoryId,
      @RequestBody @NotNull Map<String, Object> body) {
    // userId is a Keycloak sub, which Keycloak issues as a UUID — and the backend's
    // MemberEvaluationController already declares it `@PathVariable UUID userId`, so a non-UUID
    // could never have reached the evaluation anyway. Binding it as a UUID here rather than as a
    // String is what makes the relayed path safe: `buildAndExpand(...)` does NOT encode (it returns
    // RAW UriComponents, so `toUriString()` emits the expanded value verbatim), which the earlier
    // comment on this call site claimed it did. A UUID cannot express `?`, `#`, `&` or `/`, so the
    // expansion has nothing left to reshape, and the caller now gets a 400 at this seam instead of
    // one hop later.
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/promotion/evaluations/user/{userId}/category/{categoryId}")
            .buildAndExpand(userId, categoryId)
            .toUriString();
    return backendApiClient.put(uri, body, Map.class);
  }
}
